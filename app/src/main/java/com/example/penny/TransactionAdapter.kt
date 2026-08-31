package com.example.penny

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.penny.data.Transaction
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private val onTransactionClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    private var transactions = emptyList<Transaction>()

    fun submitList(newList: List<Transaction>) {
        transactions = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction = transactions[position]
        holder.bind(transaction)
        holder.itemView.setOnClickListener { onTransactionClick(transaction) }
    }

    override fun getItemCount() = transactions.size

    class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNote = itemView.findViewById<TextView>(R.id.tvNote)
        private val tvDate = itemView.findViewById<TextView>(R.id.tvDate)
        private val tvAmount = itemView.findViewById<TextView>(R.id.tvTransactionAmount)

        fun bind(transaction: Transaction) {
            // NEW: Format the database number into IDR with thousands separators and no decimals
            val formatter = NumberFormat.getNumberInstance(Locale("id", "ID"))
            formatter.maximumFractionDigits = 0
            val formattedAmount = formatter.format(transaction.amount)

            if (transaction.isReimbursable) {
                tvNote.text = "⏳ " + transaction.note
            } else {
                tvNote.text = transaction.note
            }

            if (transaction.type == "income") {
                tvAmount.text = "+Rp $formattedAmount"
                tvAmount.setTextColor(android.graphics.Color.parseColor("#388E3C"))
            } else {
                tvAmount.text = "-Rp $formattedAmount"
                tvAmount.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
            }

            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            tvDate.text = sdf.format(Date(transaction.date))
        }
    }
}