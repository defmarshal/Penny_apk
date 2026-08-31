package com.example.penny.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    // Flow allows the UI to automatically update whenever the database changes
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'income'")
    fun getTotalIncome(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'expense'")
    fun getTotalExpense(): Flow<Double?>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId ORDER BY date DESC")
    fun getTransactionsByWallet(walletId: Int): Flow<List<Transaction>>

    @Query("DELETE FROM transactions WHERE walletId = :walletId")
    suspend fun deleteTransactionsByWallet(walletId: Int)

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'expense' AND date >= :startOfMonth AND date <= :endOfMonth")
    fun getMonthlyExpenses(startOfMonth: Long, endOfMonth: Long): Flow<Double?>

    @Query("SELECT * FROM transactions WHERE isRecurring = 1")
    suspend fun getRecurringTransactions(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND date >= :startOfMonth AND date <= :endOfMonth ORDER BY date DESC")
    fun getTransactionsByWalletAndMonth(walletId: Int, startOfMonth: Long, endOfMonth: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAllTransactionsList(): List<Transaction>
}