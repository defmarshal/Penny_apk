package com.example.penny.data

import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val transactionDao: TransactionDao) {

    // Room automatically executes Flows on a background thread
    val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()
    val totalIncome: Flow<Double?> = transactionDao.getTotalIncome()
    val totalExpense: Flow<Double?> = transactionDao.getTotalExpense()

    suspend fun insert(transaction: Transaction) {
        transactionDao.insertTransaction(transaction)
    }
}