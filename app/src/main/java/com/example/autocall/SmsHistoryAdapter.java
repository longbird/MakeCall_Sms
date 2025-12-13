package com.example.autocall;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class SmsHistoryAdapter extends RecyclerView.Adapter<SmsHistoryAdapter.ViewHolder> {

    private static final String TAG = "SmsHistoryAdapter";
    private List<SmsRecord> smsRecords;

    public SmsHistoryAdapter(List<SmsRecord> smsRecords) {
        this.smsRecords = smsRecords;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sms_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmsRecord record = smsRecords.get(position);
        holder.bind(record);
    }

    @Override
    public int getItemCount() {
        return smsRecords.size();
    }

    public void updateData(List<SmsRecord> newRecords) {
        Log.d(TAG, "updateData() 호출됨 - 이전 레코드: " + this.smsRecords.size() + ", 새 레코드: " + newRecords.size());
        this.smsRecords = newRecords;
        notifyDataSetChanged();
        Log.d(TAG, "notifyDataSetChanged() 호출 완료");
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private TextView tvPhoneNumber;
        private TextView tvMessage;
        private TextView tvTimestamp;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPhoneNumber = itemView.findViewById(R.id.tvPhoneNumber);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }

        public void bind(SmsRecord record) {
            tvPhoneNumber.setText(record.getPhoneNumber());
            tvMessage.setText(record.getMessage());
            tvTimestamp.setText(record.getFormattedTimestamp());
        }
    }
}
