package com.example.autocall

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SmsHistoryAdapter(private var smsRecords: List<SmsRecord>) :
    RecyclerView.Adapter<SmsHistoryAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "SmsHistoryAdapter"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sms_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = smsRecords[position]
        holder.bind(record)
    }

    override fun getItemCount(): Int = smsRecords.size

    fun updateData(newRecords: List<SmsRecord>) {
        Log.d(TAG, "updateData() 호출됨 - 이전 레코드: ${smsRecords.size}, 새 레코드: ${newRecords.size}")
        smsRecords = newRecords
        notifyDataSetChanged()
        Log.d(TAG, "notifyDataSetChanged() 호출 완료")
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPhoneNumber: TextView = itemView.findViewById(R.id.tvPhoneNumber)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(record: SmsRecord) {
            tvPhoneNumber.text = record.phoneNumber
            tvMessage.text = record.message
            tvTimestamp.text = record.getFormattedTimestamp()
        }
    }
}
