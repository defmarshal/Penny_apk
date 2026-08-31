package com.example.penny

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.penny.data.AppDatabase
import com.example.penny.data.Category
import com.example.penny.data.Subcategory
import com.example.penny.data.SubcategoryDao
import com.example.penny.data.Transaction
import com.example.penny.data.TransactionDao
import com.example.penny.data.Wallet
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var activeWallets = emptyList<Wallet>()
    private var activeCategories = emptyList<Category>()
    private var selectedWalletId: Int = -1

    private var viewingCalendar = Calendar.getInstance()
    private var currentMonthTransactions = emptyList<Transaction>() // NEW: Tracks data for the chart

    private var balanceObservationJob: Job? = null
    private var transactionsObservationJob: Job? = null

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { writeCsvToUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val workRequest = PeriodicWorkRequestBuilder<RecurringTransactionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("RecurringBillingJob", ExistingPeriodicWorkPolicy.KEEP, workRequest)

        val spinnerWallets = findViewById<Spinner>(R.id.spinnerWallets)
        val btnManageWallets = findViewById<Button>(R.id.btnManageWallets)
        val btnManageCategories = findViewById<Button>(R.id.btnManageCategories)
        val btnTransfer = findViewById<Button>(R.id.btnTransfer)
        val btnExportCsv = findViewById<Button>(R.id.btnExportCsv)
        val btnViewAnalytics = findViewById<Button>(R.id.btnViewAnalytics) // NEW

        val tvTotalNetWorth = findViewById<TextView>(R.id.tvTotalNetWorth)
        val tvMonthlyExpense = findViewById<TextView>(R.id.tvMonthlyExpense)
        val tvBalance = findViewById<TextView>(R.id.tvBalance)
        val fabAddTransaction = findViewById<FloatingActionButton>(R.id.fabAddTransaction)

        val btnPrevMonth = findViewById<Button>(R.id.btnPrevMonth)
        val btnNextMonth = findViewById<Button>(R.id.btnNextMonth)
        val tvCurrentMonth = findViewById<TextView>(R.id.tvCurrentMonth)

        val db = AppDatabase.getDatabase(this)
        val walletDao = db.walletDao()
        val transactionDao = db.transactionDao()
        val categoryDao = db.categoryDao()
        val subcategoryDao = db.subcategoryDao()

        val rvTransactions = findViewById<RecyclerView>(R.id.rvTransactions)
        val transactionAdapter = TransactionAdapter { clickedTransaction ->
            showTransactionOptionsDialog(clickedTransaction, transactionDao, subcategoryDao)
        }
        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvTransactions.adapter = transactionAdapter

        lifecycleScope.launch {
            if (categoryDao.getAllCategories().first().isEmpty()) {
                categoryDao.insertCategory(Category(id = 1, name = "Food", type = "expense", colorHex = "#F44336"))
                categoryDao.insertCategory(Category(id = 2, name = "Transport", type = "expense", colorHex = "#2196F3"))
                categoryDao.insertCategory(Category(id = 3, name = "Salary", type = "income", colorHex = "#4CAF50"))
                categoryDao.insertCategory(Category(id = 4, name = "General", type = "expense", colorHex = "#9E9E9E"))
                categoryDao.insertCategory(Category(id = 5, name = "Transfer", type = "transfer", colorHex = "#FF9800"))
                subcategoryDao.insertSubcategory(Subcategory(id = 0, name = "Groceries", categoryId = 1))
                subcategoryDao.insertSubcategory(Subcategory(id = 0, name = "Dining Out", categoryId = 1))
            }
        }

        lifecycleScope.launch { categoryDao.getAllCategories().collect { activeCategories = it } }
        lifecycleScope.launch { walletDao.getTotalNetWorth().collect { tvTotalNetWorth.text = formatIdr(it) } }

        val realCurrentMonth = Calendar.getInstance()
        val (startOfRealMonth, endOfRealMonth) = getBoundsForCalendar(realCurrentMonth)
        lifecycleScope.launch {
            transactionDao.getMonthlyExpenses(startOfRealMonth, endOfRealMonth).collect { spent -> tvMonthlyExpense.text = "Spent this month: ${formatIdr(spent)}" }
        }

        lifecycleScope.launch {
            walletDao.getAllWallets().collect { wallets ->
                activeWallets = wallets
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, wallets.map { it.name })
                spinnerWallets.adapter = adapter
            }
        }

        fun loadTransactionsForViewingMonth() {
            updateMonthTextView(tvCurrentMonth)
            if (selectedWalletId == -1) return
            transactionsObservationJob?.cancel()
            val (startMillis, endMillis) = getBoundsForCalendar(viewingCalendar)
            transactionsObservationJob = lifecycleScope.launch {
                transactionDao.getTransactionsByWalletAndMonth(selectedWalletId, startMillis, endMillis).collect { list ->
                    currentMonthTransactions = list // Save state for analytics
                    transactionAdapter.submitList(list)
                }
            }
        }

        updateMonthTextView(tvCurrentMonth)
        btnPrevMonth.setOnClickListener { viewingCalendar.add(Calendar.MONTH, -1); loadTransactionsForViewingMonth() }
        btnNextMonth.setOnClickListener { viewingCalendar.add(Calendar.MONTH, 1); loadTransactionsForViewingMonth() }

        // --- NEW: Trigger Analytics Dashboard ---
        btnViewAnalytics.setOnClickListener {
            showAnalyticsDialog()
        }

        spinnerWallets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (activeWallets.isNotEmpty()) {
                    selectedWalletId = activeWallets[position].id
                    balanceObservationJob?.cancel()
                    balanceObservationJob = lifecycleScope.launch { walletDao.getWalletBalance(selectedWalletId).collect { balance -> tvBalance.text = "Balance: ${formatIdr(balance)}" } }
                    loadTransactionsForViewingMonth()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnManageWallets.setOnClickListener {
            val walletNames = activeWallets.map { it.name }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Manage Wallets")
                .setItems(walletNames) { _, which -> showEditDeleteWalletDialog(activeWallets[which], walletDao, transactionDao) }
                .setPositiveButton("Add New") { _, _ -> showAddWalletDialog(walletDao) }
                .setNegativeButton("Close", null).show()
        }

        btnManageCategories.setOnClickListener {
            val types = arrayOf("Expense Categories", "Income Categories")
            AlertDialog.Builder(this).setTitle("Manage Categories")
                .setItems(types) { _, which ->
                    val selectedType = if (which == 0) "expense" else "income"
                    showCategoryListDialog(selectedType, categoryDao, subcategoryDao)
                }.setNegativeButton("Close", null).show()
        }

        btnTransfer.setOnClickListener {
            if (activeWallets.size < 2) { Toast.makeText(this, "You need at least 2 wallets to make a transfer", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_transfer, null)
            val spinnerSource = dialogView.findViewById<Spinner>(R.id.spinnerSourceWallet)
            val spinnerDest = dialogView.findViewById<Spinner>(R.id.spinnerDestWallet)
            val etAmount = dialogView.findViewById<EditText>(R.id.etTransferAmount)

            etAmount.addThousandsSeparator()

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, activeWallets.map { it.name })
            spinnerSource.adapter = adapter
            spinnerDest.adapter = adapter
            if (activeWallets.size > 1) spinnerDest.setSelection(1)

            AlertDialog.Builder(this).setTitle("Transfer Between Wallets").setView(dialogView)
                .setPositiveButton("Transfer") { _, _ ->
                    val sourceIndex = spinnerSource.selectedItemPosition
                    val destIndex = spinnerDest.selectedItemPosition
                    if (sourceIndex == destIndex) { Toast.makeText(this, "Cannot transfer to the same wallet", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    val amount = etAmount.getCleanDouble()
                    if (amount <= 0) return@setPositiveButton

                    val sourceWallet = activeWallets[sourceIndex]
                    val destWallet = activeWallets[destIndex]
                    val transferCatId = activeCategories.find { it.type == "transfer" }?.id ?: 1
                    val currentTime = System.currentTimeMillis()

                    lifecycleScope.launch {
                        transactionDao.insertTransaction(Transaction(type = "expense", amount = amount, date = currentTime, note = "Transfer to ${destWallet.name}", walletId = sourceWallet.id, categoryId = transferCatId, isReimbursable = false))
                        transactionDao.insertTransaction(Transaction(type = "income", amount = amount, date = currentTime, note = "Transfer from ${sourceWallet.name}", walletId = destWallet.id, categoryId = transferCatId, isReimbursable = false))
                        runOnUiThread { Toast.makeText(this@MainActivity, "Transfer Successful!", Toast.LENGTH_SHORT).show() }
                    }
                }.setNegativeButton("Cancel", null).show()
        }

        btnExportCsv.setOnClickListener { exportCsvLauncher.launch("Penny_Backup_${System.currentTimeMillis()}.csv") }

        fabAddTransaction.setOnClickListener {
            if (selectedWalletId == -1) { Toast.makeText(this, "Please create and select a wallet first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_transaction, null)
            val rgTransactionType = dialogView.findViewById<RadioGroup>(R.id.rgTransactionType)
            val rbIncome = dialogView.findViewById<RadioButton>(R.id.rbIncome)
            val etAmount = dialogView.findViewById<EditText>(R.id.etAmount)
            val etDescription = dialogView.findViewById<EditText>(R.id.etDescription)
            val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
            val spinnerSubcategory = dialogView.findViewById<Spinner>(R.id.spinnerSubcategory)
            val cbRecurring = dialogView.findViewById<CheckBox>(R.id.cbRecurring)
            val cbReimbursable = dialogView.findViewById<CheckBox>(R.id.cbReimbursable)

            etAmount.addThousandsSeparator()
            var filteredCategories = emptyList<Category>()

            fun refreshCategorySpinner() {
                val selectedType = if (rbIncome.isChecked) "income" else "expense"
                filteredCategories = activeCategories.filter { it.type == selectedType }
                val categoryAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, filteredCategories.map { it.name })
                spinnerCategory.adapter = categoryAdapter
            }

            refreshCategorySpinner()
            rgTransactionType.setOnCheckedChangeListener { _, _ -> refreshCategorySpinner() }

            var currentSubcategories = emptyList<Subcategory>()
            var subJob: Job? = null

            spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (filteredCategories.isEmpty()) return
                    val selectedCatId = filteredCategories[position].id
                    subJob?.cancel()
                    subJob = lifecycleScope.launch {
                        subcategoryDao.getSubcategoriesByCategory(selectedCatId).collect { subcats ->
                            currentSubcategories = subcats
                            val subNames = mutableListOf("None")
                            subNames.addAll(subcats.map { it.name })
                            val subAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, subNames)
                            spinnerSubcategory.adapter = subAdapter
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            AlertDialog.Builder(this).setTitle("Add Transaction").setView(dialogView)
                .setPositiveButton("Save") { _, _ ->
                    val amount = etAmount.getCleanDouble()
                    val desc = etDescription.text.toString()
                    if (amount <= 0 || desc.isBlank()) return@setPositiveButton

                    val transactionType = if (rbIncome.isChecked) "income" else "expense"
                    val catId = filteredCategories.getOrNull(spinnerCategory.selectedItemPosition)?.id ?: 1
                    val subPos = spinnerSubcategory.selectedItemPosition
                    val subId = if (subPos > 0 && currentSubcategories.isNotEmpty()) currentSubcategories[subPos - 1].id else null

                    lifecycleScope.launch {
                        val transaction = Transaction(
                            type = transactionType, amount = amount, date = System.currentTimeMillis(),
                            note = desc, walletId = selectedWalletId, categoryId = catId, subcategoryId = subId,
                            isRecurring = cbRecurring.isChecked, isReimbursable = cbReimbursable.isChecked
                        )
                        transactionDao.insertTransaction(transaction)
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    // --- Helper Functions ---

    // NEW: Dashboard Generation Engine
    private fun showAnalyticsDialog() {
        val expenseList = currentMonthTransactions.filter { it.type == "expense" }
        if (expenseList.isEmpty()) {
            Toast.makeText(this, "No expenses to analyze this month!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_analytics, null)
        val pieChart = dialogView.findViewById<com.github.mikephil.charting.charts.PieChart>(R.id.pieChart)

        val categoryMap = activeCategories.associateBy { it.id }

        // Group all expenses by Category ID and sum the amounts
        val totals = expenseList.groupBy { it.categoryId }.mapValues { entry -> entry.value.sumOf { it.amount } }

        val entries = ArrayList<com.github.mikephil.charting.data.PieEntry>()
        val colors = ArrayList<Int>()

        for ((catId, total) in totals) {
            val cat = categoryMap[catId]
            entries.add(com.github.mikephil.charting.data.PieEntry(total.toFloat(), cat?.name ?: "Unknown"))
            // Map the exact database color (e.g. #F44336) to the pie slice
            colors.add(android.graphics.Color.parseColor(cat?.colorHex ?: "#9E9E9E"))
        }

        val dataSet = com.github.mikephil.charting.data.PieDataSet(entries, "")
        dataSet.colors = colors
        dataSet.valueTextSize = 14f
        dataSet.valueTextColor = android.graphics.Color.WHITE

        // Format the pie slice numbers as IDR
        dataSet.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return formatIdr(value.toDouble())
            }
        }

        val data = com.github.mikephil.charting.data.PieData(dataSet)
        pieChart.data = data
        pieChart.description.isEnabled = false
        pieChart.centerText = "Expenses"
        pieChart.setCenterTextSize(18f)
        pieChart.setUsePercentValues(false)
        pieChart.setDrawEntryLabels(false) // Keeps the chart clean; relies on the legend
        pieChart.legend.textSize = 14f
        pieChart.animateY(1000) // Beautiful 1-second spinning pop-in animation
        pieChart.invalidate()

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun writeCsvToUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MainActivity)
                val transactions = db.transactionDao().getAllTransactionsList()
                val walletsMap = activeWallets.associateBy { it.id }
                val categoriesMap = activeCategories.associateBy { it.id }

                val csvBuilder = StringBuilder()
                csvBuilder.append("Date,Type,Amount,Wallet,Category,Note\n")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                for (t in transactions) {
                    val dateStr = sdf.format(t.date)
                    val typeStr = t.type.replaceFirstChar { it.uppercase() }
                    val amountStr = t.amount.toLong().toString()
                    val walletName = walletsMap[t.walletId]?.name ?: "Unknown"
                    val catName = categoriesMap[t.categoryId]?.name ?: "Unknown"
                    val noteStr = t.note?.replace(",", " ")?.replace("\n", " ") ?: ""
                    csvBuilder.append("$dateStr,$typeStr,$amountStr,$walletName,$catName,$noteStr\n")
                }
                contentResolver.openOutputStream(uri)?.use { it.write(csvBuilder.toString().toByteArray()) }
                runOnUiThread { Toast.makeText(this@MainActivity, "Backup Saved to Downloads!", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this@MainActivity, "Export Failed: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun updateMonthTextView(tvMonth: TextView) {
        val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        tvMonth.text = sdf.format(viewingCalendar.time)
    }

    private fun getBoundsForCalendar(calendar: Calendar): Pair<Long, Long> {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH)); cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        return Pair(start, end)
    }

    private fun showAddWalletDialog(walletDao: com.example.penny.data.WalletDao) {
        val input = EditText(this).apply { hint = "e.g. BCA / Flazz"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Add New Wallet").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) lifecycleScope.launch { walletDao.insertWallet(Wallet(name = name, initialBalance = 0.0)) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showEditDeleteWalletDialog(wallet: Wallet, walletDao: com.example.penny.data.WalletDao, transactionDao: TransactionDao) {
        val input = EditText(this).apply { setText(wallet.name); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Edit or Delete Wallet").setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) lifecycleScope.launch { walletDao.updateWallet(wallet.copy(name = newName)) }
            }.setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this).setTitle("Confirm Deletion").setMessage("Are you sure?")
                    .setPositiveButton("Delete Everything") { _, _ ->
                        lifecycleScope.launch { transactionDao.deleteTransactionsByWallet(wallet.id); walletDao.deleteWallet(wallet); runOnUiThread { Toast.makeText(this@MainActivity, "Wallet Deleted", Toast.LENGTH_SHORT).show() } }
                    }.setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun showCategoryListDialog(type: String, categoryDao: com.example.penny.data.CategoryDao, subcategoryDao: SubcategoryDao) {
        val filteredCats = activeCategories.filter { it.type == type }
        val catNames = filteredCats.map { it.name }.toTypedArray()
        val titleStr = if (type == "expense") "Expense Categories" else "Income Categories"

        AlertDialog.Builder(this).setTitle(titleStr).setItems(catNames) { _, index -> showCategoryOptionsDialog(filteredCats[index], categoryDao, subcategoryDao) }
            .setPositiveButton("Add New") { _, _ -> showAddCategoryDialog(categoryDao, type) }.setNegativeButton("Close", null).show()
    }

    private fun showAddCategoryDialog(categoryDao: com.example.penny.data.CategoryDao, defaultType: String = "expense") {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val input = EditText(this).apply { hint = "e.g. Freelance Work"; inputType = InputType.TYPE_CLASS_TEXT }
        val rgType = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rbExpense = RadioButton(this).apply { text = "Expense"; isChecked = (defaultType == "expense") }
        val rbIncome = RadioButton(this).apply { text = "Income"; isChecked = (defaultType == "income") }

        rgType.addView(rbExpense); rgType.addView(rbIncome); layout.addView(input); layout.addView(rgType)

        AlertDialog.Builder(this).setTitle("Add New Category").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                val type = if (rbIncome.isChecked) "income" else "expense"
                if (name.isNotBlank()) lifecycleScope.launch { categoryDao.insertCategory(Category(id = 0, name = name, type = type, colorHex = "#9E9E9E")) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCategoryOptionsDialog(category: Category, categoryDao: com.example.penny.data.CategoryDao, subcategoryDao: SubcategoryDao) {
        val options = arrayOf("Manage Subcategories", "Edit Category Name", "Delete Category")
        AlertDialog.Builder(this).setTitle(category.name).setItems(options) { _, which ->
            when (which) {
                0 -> showManageSubcategoriesDialog(category, subcategoryDao)
                1 -> showEditDeleteCategoryDialog(category, categoryDao)
                2 -> lifecycleScope.launch { categoryDao.deleteCategory(category); runOnUiThread { Toast.makeText(this@MainActivity, "Deleted", Toast.LENGTH_SHORT).show() } }
            }
        }.show()
    }

    private fun showManageSubcategoriesDialog(category: Category, subcategoryDao: SubcategoryDao) {
        lifecycleScope.launch {
            val subcats = subcategoryDao.getSubcategoriesByCategory(category.id).first()
            val subNames = subcats.map { it.name }.toTypedArray()
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity).setTitle("Subcategories for ${category.name}")
                    .setItems(subNames) { _, which -> showEditDeleteSubcategoryDialog(subcats[which], subcategoryDao) }
                    .setPositiveButton("Add New") { _, _ -> showAddSubcategoryDialog(category, subcategoryDao) }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    private fun showAddSubcategoryDialog(category: Category, subcategoryDao: SubcategoryDao) {
        val input = EditText(this).apply { hint = "e.g. Groceries"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Add Subcategory").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) lifecycleScope.launch { subcategoryDao.insertSubcategory(Subcategory(name = name, categoryId = category.id)) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showEditDeleteSubcategoryDialog(subcategory: Subcategory, subcategoryDao: SubcategoryDao) {
        val input = EditText(this).apply { setText(subcategory.name); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Edit Subcategory").setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) lifecycleScope.launch { subcategoryDao.updateSubcategory(subcategory.copy(name = newName)) }
            }.setNeutralButton("Delete") { _, _ -> lifecycleScope.launch { subcategoryDao.deleteSubcategory(subcategory) } }.show()
    }

    private fun showEditDeleteCategoryDialog(category: Category, categoryDao: com.example.penny.data.CategoryDao) {
        val input = EditText(this).apply { setText(category.name); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Edit Category").setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) lifecycleScope.launch { categoryDao.updateCategory(category.copy(name = newName)) }
            }.show()
    }

    private fun showTransactionOptionsDialog(transaction: Transaction, transactionDao: TransactionDao, subcategoryDao: SubcategoryDao) {
        val options = if (transaction.isReimbursable) { arrayOf("✅ Mark as Reimbursed", "Edit Transaction", "Delete Transaction") } else { arrayOf("Edit Transaction", "Delete Transaction") }
        AlertDialog.Builder(this).setTitle("Manage Transaction").setItems(options) { _, which ->
            if (transaction.isReimbursable) {
                when (which) {
                    0 -> processReimbursement(transaction, transactionDao)
                    1 -> showEditTransactionDialog(transaction, transactionDao, subcategoryDao)
                    2 -> lifecycleScope.launch { transactionDao.deleteTransaction(transaction) }
                }
            } else {
                when (which) {
                    0 -> showEditTransactionDialog(transaction, transactionDao, subcategoryDao)
                    1 -> lifecycleScope.launch { transactionDao.deleteTransaction(transaction) }
                }
            }
        }.show()
    }

    private fun processReimbursement(transaction: Transaction, transactionDao: TransactionDao) {
        lifecycleScope.launch {
            transactionDao.updateTransaction(transaction.copy(isReimbursable = false))
            val reimbursementIncome = Transaction(type = "income", amount = transaction.amount, date = System.currentTimeMillis(), note = "Reimbursed: ${transaction.note}", walletId = transaction.walletId, categoryId = transaction.categoryId, subcategoryId = transaction.subcategoryId, isRecurring = false, isReimbursable = false)
            transactionDao.insertTransaction(reimbursementIncome)
            runOnUiThread { Toast.makeText(this@MainActivity, "Reimbursement logged!", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showEditTransactionDialog(transaction: Transaction, transactionDao: TransactionDao, subcategoryDao: SubcategoryDao) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_transaction, null)
        val rgTransactionType = dialogView.findViewById<RadioGroup>(R.id.rgTransactionType)
        val rbIncome = dialogView.findViewById<RadioButton>(R.id.rbIncome)
        val rbExpense = dialogView.findViewById<RadioButton>(R.id.rbExpense)
        val etAmount = dialogView.findViewById<EditText>(R.id.etAmount)
        val etDescription = dialogView.findViewById<EditText>(R.id.etDescription)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val spinnerSubcategory = dialogView.findViewById<Spinner>(R.id.spinnerSubcategory)
        val cbRecurring = dialogView.findViewById<CheckBox>(R.id.cbRecurring)
        val cbReimbursable = dialogView.findViewById<CheckBox>(R.id.cbReimbursable)

        etAmount.addThousandsSeparator(); etAmount.setText(transaction.amount.toLong().toString())
        etDescription.setText(transaction.note); cbRecurring.isChecked = transaction.isRecurring; cbReimbursable.isChecked = transaction.isReimbursable
        if (transaction.type == "income") rbIncome.isChecked = true else rbExpense.isChecked = true

        var filteredCategories = emptyList<Category>()
        var isFirstLoad = true

        fun refreshCategorySpinner() {
            val currentType = if (rbIncome.isChecked) "income" else "expense"
            filteredCategories = activeCategories.filter { it.type == currentType }
            val categoryAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, filteredCategories.map { it.name })
            spinnerCategory.adapter = categoryAdapter
            if (isFirstLoad) {
                val catIndex = filteredCategories.indexOfFirst { it.id == transaction.categoryId }
                if (catIndex >= 0) spinnerCategory.setSelection(catIndex)
            }
        }
        refreshCategorySpinner(); rgTransactionType.setOnCheckedChangeListener { _, _ -> refreshCategorySpinner() }

        var currentSubcategories = emptyList<Subcategory>()
        var subJob: Job? = null
        spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (filteredCategories.isEmpty()) return
                val selectedCatId = filteredCategories[position].id
                subJob?.cancel()
                subJob = lifecycleScope.launch {
                    subcategoryDao.getSubcategoriesByCategory(selectedCatId).collect { subcats ->
                        currentSubcategories = subcats
                        val subNames = mutableListOf("None")
                        subNames.addAll(subcats.map { it.name })
                        spinnerSubcategory.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, subNames)
                        if (isFirstLoad && transaction.categoryId == selectedCatId) {
                            val subIndex = subcats.indexOfFirst { it.id == transaction.subcategoryId }
                            if (subIndex >= 0) spinnerSubcategory.setSelection(subIndex + 1)
                            isFirstLoad = false
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this).setTitle("Edit Transaction").setView(dialogView)
            .setPositiveButton("Update") { _, _ ->
                val amount = etAmount.getCleanDouble(); val desc = etDescription.text.toString()
                if (amount <= 0 || desc.isBlank()) return@setPositiveButton
                val transactionType = if (rbIncome.isChecked) "income" else "expense"
                val catId = filteredCategories.getOrNull(spinnerCategory.selectedItemPosition)?.id ?: 1
                val subPos = spinnerSubcategory.selectedItemPosition
                val subId = if (subPos > 0 && currentSubcategories.isNotEmpty()) currentSubcategories[subPos - 1].id else null
                lifecycleScope.launch { transactionDao.updateTransaction(transaction.copy(amount = amount, note = desc, type = transactionType, categoryId = catId, subcategoryId = subId, isRecurring = cbRecurring.isChecked, isReimbursable = cbReimbursable.isChecked)) }
            }.setNegativeButton("Cancel", null).show()
    }
}

fun formatIdr(amount: Double?): String {
    val formatter = java.text.NumberFormat.getNumberInstance(java.util.Locale("id", "ID"))
    formatter.maximumFractionDigits = 0
    return "Rp " + formatter.format(amount ?: 0.0)
}

fun android.widget.EditText.addThousandsSeparator() {
    this.addTextChangedListener(object : android.text.TextWatcher {
        private var current = ""
        override fun afterTextChanged(s: android.text.Editable?) {
            if (s.toString() != current) {
                this@addThousandsSeparator.removeTextChangedListener(this)
                val cleanString = s.toString().replace(Regex("[^\\d]"), "")
                if (cleanString.isNotEmpty()) {
                    val formatted = java.text.NumberFormat.getNumberInstance(java.util.Locale("id", "ID")).apply { maximumFractionDigits = 0 }.format(cleanString.toDouble())
                    current = formatted
                    this@addThousandsSeparator.setText(formatted)
                    this@addThousandsSeparator.setSelection(formatted.length)
                } else {
                    current = ""; this@addThousandsSeparator.setText("")
                }
                this@addThousandsSeparator.addTextChangedListener(this)
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

fun android.widget.EditText.getCleanDouble(): Double {
    val cleanString = this.text.toString().replace(Regex("[^\\d]"), "")
    return if (cleanString.isNotEmpty()) cleanString.toDouble() else 0.0
}