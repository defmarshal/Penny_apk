package com.example.penny.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class Wallet(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val initialBalance: Double = 0.0
)