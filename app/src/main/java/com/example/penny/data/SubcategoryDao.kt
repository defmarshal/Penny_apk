package com.example.penny.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubcategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubcategory(subcategory: Subcategory)

    @Update
    suspend fun updateSubcategory(subcategory: Subcategory)

    @Delete
    suspend fun deleteSubcategory(subcategory: Subcategory)

    // Fetch all subcategories that belong to a specific parent category
    @Query("SELECT * FROM subcategories WHERE categoryId = :categoryId ORDER BY name ASC")
    fun getSubcategoriesByCategory(categoryId: Int): Flow<List<Subcategory>>
}