package com.example.penny.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val type: String,
    val amount: Double,
    val date: Long,
    val note: String? = null,
    val isRecurring: Boolean = false,

    val walletId: Int,
    val categoryId: Int,
    val subcategoryId: Int? = null, // NEW: Links to a subcategory (nullable)
    val isReimbursable: Boolean = false
)