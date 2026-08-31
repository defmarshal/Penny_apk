package com.example.penny.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: Wallet)

    @Update
    suspend fun updateWallet(wallet: Wallet)

    @Delete
    suspend fun deleteWallet(wallet: Wallet)

    @Query("SELECT * FROM wallets ORDER BY name ASC")
    fun getAllWallets(): Flow<List<Wallet>>

    @Query("""
        SELECT w.initialBalance + 
        COALESCE((SELECT SUM(amount) FROM transactions WHERE walletId = w.id AND type = 'income'), 0) - 
        COALESCE((SELECT SUM(amount) FROM transactions WHERE type = 'expense'), 0) 
        FROM wallets w WHERE w.id = :walletId
    """)
    fun getWalletBalance(walletId: Int): Flow<Double>

    // NEW: Master Net Worth Query
    @Query("""
        SELECT 
            COALESCE((SELECT SUM(initialBalance) FROM wallets), 0.0) + 
            COALESCE((SELECT SUM(amount) FROM transactions WHERE type = 'income'), 0.0) - 
            COALESCE((SELECT SUM(amount) FROM transactions WHERE type = 'expense'), 0.0)
    """)
    fun getTotalNetWorth(): Flow<Double>
}