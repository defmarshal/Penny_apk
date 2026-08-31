package com.example.penny

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.penny.data.AppDatabase
import com.example.penny.data.Transaction
import java.util.Calendar

class RecurringTransactionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val transactionDao = db.transactionDao()

        // 1. Get today's day of the month (e.g., the 30th)
        val todayCalendar = Calendar.getInstance()
        val todayDay = todayCalendar.get(Calendar.DAY_OF_MONTH)

        // 2. Fetch all templates marked as recurring
        val recurringTemplates = transactionDao.getRecurringTransactions()

        // 3. Check if any templates match today's date
        for (template in recurringTemplates) {
            val templateCalendar = Calendar.getInstance().apply { timeInMillis = template.date }
            val templateDay = templateCalendar.get(Calendar.DAY_OF_MONTH)

            if (todayDay == templateDay) {
                // It's billing day! Clone the transaction and save it as a new entry for today.
                val newBill = Transaction(
                    type = template.type,
                    amount = template.amount,
                    date = System.currentTimeMillis(), // Today's exact time
                    note = "${template.note} (Auto-Billed)",
                    walletId = template.walletId,
                    categoryId = template.categoryId,
                    subcategoryId = template.subcategoryId,
                    isRecurring = false, // The clone isn't the template, it's just a receipt
                    isReimbursable = false
                )
                transactionDao.insertTransaction(newBill)
            }
        }

        return Result.success()
    }
}