package com.example.penny.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.penny.data.AppDatabase
import com.example.penny.data.Transaction
import com.example.penny.data.TransactionRepository
import kotlinx.coroutines.launch

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TransactionRepository

    init {
        val transactionDao = AppDatabase.getDatabase(application).transactionDao()
        repository = TransactionRepository(transactionDao)
    }

    // Convert Flows to LiveData so the UI can observe them automatically
    val allTransactions = repository.allTransactions.asLiveData()
    val totalIncome = repository.totalIncome.asLiveData()
    val totalExpense = repository.totalExpense.asLiveData()

    // Launch database writes in a background coroutine
    fun insert(transaction: Transaction) = viewModelScope.launch {
        repository.insert(transaction)
    }
}