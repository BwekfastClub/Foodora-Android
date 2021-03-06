package club.bweakfast.foodora.recipe

import android.database.sqlite.SQLiteConstraintException
import androidx.core.content.contentValuesOf
import androidx.core.database.getInt
import androidx.core.database.getString
import androidx.core.database.sqlite.transaction
import club.bweakfast.foodora.db.COLUMN_CATEGORY_NAME
import club.bweakfast.foodora.db.COLUMN_RECIPE_COOK_MINS
import club.bweakfast.foodora.db.COLUMN_RECIPE_ID
import club.bweakfast.foodora.db.COLUMN_RECIPE_IMG_URL
import club.bweakfast.foodora.db.COLUMN_RECIPE_PREP_MINS
import club.bweakfast.foodora.db.COLUMN_RECIPE_READY_MINS
import club.bweakfast.foodora.db.COLUMN_RECIPE_SERVINGS
import club.bweakfast.foodora.db.COLUMN_RECIPE_TITLE
import club.bweakfast.foodora.db.COLUMN_REL_RECIPE_ID
import club.bweakfast.foodora.db.FoodoraDB
import club.bweakfast.foodora.db.TABLE_LIKED_RECIPES_NAME
import club.bweakfast.foodora.db.TABLE_MEAL_PLAN_NAME
import club.bweakfast.foodora.db.TABLE_RECIPE_NAME
import club.bweakfast.foodora.db.createRecipeFromCursor
import club.bweakfast.foodora.recipe.ingredient.IngredientDao
import club.bweakfast.foodora.recipe.nutrition.NutritionDao
import club.bweakfast.foodora.util.insertOrThrow
import club.bweakfast.foodora.util.map
import club.bweakfast.foodora.util.query
import club.bweakfast.foodora.util.useFirst
import io.reactivex.Completable
import io.reactivex.Single
import javax.inject.Inject

class RecipeDaoImpl @Inject constructor(
    private val foodoraDB: FoodoraDB,
    private val ingredientDao: IngredientDao,
    private val nutritionDao: NutritionDao
) : RecipeDao {
    override fun getRecipes(recipeIDs: List<Int>?): Single<List<Recipe>> = Single.create { it.onSuccess(getRecipesList(recipeIDs)) }

    override fun getRecipesList(recipeIDs: List<Int>?): List<Recipe> {
        val db = foodoraDB.readableDatabase
        val recipeCursor = if (recipeIDs == null) {
            db.query(TABLE_RECIPE_NAME)
        } else {
            db.query(
                table = TABLE_RECIPE_NAME,
                selection = "$COLUMN_RECIPE_ID IN (${recipeIDs.joinToString(", ") { "?" }})",
                selectionArgs = recipeIDs.map { it.toString() }.toTypedArray()
            )
        }

        return recipeCursor.use {
            it.map {
                val recipeID = recipeCursor.getInt(COLUMN_RECIPE_ID)
                val ingredients = ingredientDao.getIngredientsList(recipeID)
                val nutrition = nutritionDao.getNutritionList(recipeID)

                createRecipeFromCursor(this, ingredients, nutrition)
            }
        }
    }

    override fun getRecipe(recipeID: Int): Single<Recipe> = Single.create { it.onSuccess(getRecipeVal(recipeID)) }

    override fun getRecipeVal(recipeID: Int): Recipe {
        val db = foodoraDB.readableDatabase
        val recipeCursor = db.query(TABLE_RECIPE_NAME, selection = "$COLUMN_RECIPE_ID = ?", selectionArgs = arrayOf(recipeID.toString()))

        val ingredients = ingredientDao.getIngredientsList(recipeID)
        val nutrition = nutritionDao.getNutritionList(recipeID)

        return recipeCursor.useFirst { createRecipeFromCursor(recipeCursor, ingredients, nutrition) }
    }

    override fun addRecipe(recipe: Recipe): Completable {
        return Completable.create {
            val db = foodoraDB.writableDatabase
            with(recipe) {
                try {
                    db.insertOrThrow(
                        TABLE_RECIPE_NAME,
                        contentValuesOf(
                            COLUMN_RECIPE_ID to id,
                            COLUMN_RECIPE_TITLE to title,
                            COLUMN_RECIPE_SERVINGS to servings,
                            COLUMN_RECIPE_PREP_MINS to prepMinutes,
                            COLUMN_RECIPE_COOK_MINS to cookMinutes,
                            COLUMN_RECIPE_READY_MINS to readyMinutes,
                            COLUMN_RECIPE_IMG_URL to imageURL
                        )
                    )
                } catch (e: SQLiteConstraintException) {
                    e.message?.let {
                        if (!it.startsWith("UNIQUE constraint failed: recipes.id") && !it.startsWith("PRIMARY KEY must be unique")) {
                            throw e
                        }
                    }
                        ?: throw e
                }
                it.onComplete()
            }
        }
            .andThen(
                Completable.merge(
                    listOf(
                        ingredientDao.saveIngredients(recipe.ingredients, recipe.id),
                        nutritionDao.saveNutrition(recipe.nutrition.values.toList(), recipe.id)
                    )
                )
            )
    }

    override fun removeRecipe(recipe: Recipe): Completable {
        return Completable.create {
            val db = foodoraDB.writableDatabase
            db.delete(TABLE_RECIPE_NAME, "$COLUMN_RECIPE_ID = ?", arrayOf(recipe.id.toString()))
            it.onComplete()
        }
    }

    override fun getLikedRecipes(): Single<List<Recipe>> {
        return Single.create {
            val db = foodoraDB.readableDatabase
            val likedRecipeIDs = db.query(TABLE_LIKED_RECIPES_NAME).use { cursor -> cursor.map { getInt(COLUMN_REL_RECIPE_ID) } }

            it.onSuccess(getRecipesList(likedRecipeIDs))
        }
    }

    override fun isLikedRecipe(recipeID: Int): Single<Boolean> {
        return Single.create {
            val db = foodoraDB.readableDatabase

            db.query(TABLE_LIKED_RECIPES_NAME, selection = "$COLUMN_REL_RECIPE_ID = ?", selectionArgs = arrayOf(recipeID.toString()))
                .use { cursor ->
                    it.onSuccess(cursor.count > 0)
                }
        }
    }

    override fun addLikedRecipe(recipeID: Int): Completable {
        return Completable.create {
            val db = foodoraDB.writableDatabase

            try {
                db.insertOrThrow(TABLE_LIKED_RECIPES_NAME, contentValuesOf(COLUMN_REL_RECIPE_ID to recipeID))
            } catch (e: SQLiteConstraintException) {
                e.message?.let { if (!it.startsWith("UNIQUE constraint failed: liked_recipes")) throw e } ?: throw e
            }
            it.onComplete()
        }
    }

    override fun removeLikedRecipe(recipeID: Int): Completable {
        return Completable.create {
            val db = foodoraDB.writableDatabase

            db.delete(TABLE_LIKED_RECIPES_NAME, "$COLUMN_REL_RECIPE_ID = ?", arrayOf(recipeID.toString()))
            it.onComplete()
        }
    }

    override fun getRecipesInMealPlan(): Single<Map<String, List<Recipe>>> {
        return Single.create {
            val db = foodoraDB.readableDatabase
            val mealPlanRecipeIDs = db.query(TABLE_MEAL_PLAN_NAME, orderBy = COLUMN_CATEGORY_NAME)
                .use { cursor ->
                    cursor.map { getString(COLUMN_CATEGORY_NAME) to getInt(COLUMN_REL_RECIPE_ID) }
                        .fold(mutableMapOf<String, MutableList<Int>>()) { map, (category, recipeID) ->
                            val list = map.getOrPut(category) { mutableListOf() }
                            list.add(recipeID)
                            map
                        }
                }

            it.onSuccess(mealPlanRecipeIDs.mapValues { (_, recipeIDs) -> getRecipesList(recipeIDs) })
        }
    }

    override fun getCategoryNamesForRecipeInMealPlan(recipeID: Int): Single<List<String>> {
        return Single.create {
            val db = foodoraDB.readableDatabase
            val categoryNames = db.query(TABLE_MEAL_PLAN_NAME, arrayOf(COLUMN_CATEGORY_NAME), "$COLUMN_REL_RECIPE_ID = ?", arrayOf(recipeID.toString()))
                .use { cursor -> cursor.map { getString(COLUMN_CATEGORY_NAME) } }

            it.onSuccess(categoryNames)
        }
    }

    override fun isRecipeInMealPlan(recipeID: Int): Single<Boolean> {
        return Single.create {
            val db = foodoraDB.readableDatabase

            db.query(TABLE_MEAL_PLAN_NAME, selection = "$COLUMN_REL_RECIPE_ID = ?", selectionArgs = arrayOf(recipeID.toString()))
                .use { cursor ->
                    it.onSuccess(cursor.count > 0)
                }
        }
    }

    override fun addRecipeToMealPlan(recipeID: Int, categoryNames: List<String>): Completable {
        return Completable.create {
            val db = foodoraDB.writableDatabase

            db.transaction {
                categoryNames.forEach { categoryName ->
                    try {
                        db.insertOrThrow(
                            TABLE_MEAL_PLAN_NAME,
                            contentValuesOf(
                                COLUMN_REL_RECIPE_ID to recipeID,
                                COLUMN_CATEGORY_NAME to categoryName
                            )
                        )
                    } catch (e: SQLiteConstraintException) {
                        e.message?.let { if (!it.startsWith("UNIQUE constraint failed: meal_plan")) throw e } ?: throw e
                    }
                }
            }
            it.onComplete()
        }
    }

    override fun removeRecipeFromMealPlan(recipeID: Int, categoryName: String): Completable {
        return Completable.create {
            val db = foodoraDB.writableDatabase

            db.delete(TABLE_MEAL_PLAN_NAME, "$COLUMN_REL_RECIPE_ID = ? AND $COLUMN_CATEGORY_NAME = ?", arrayOf(recipeID.toString(), categoryName))
            it.onComplete()
        }
    }
}