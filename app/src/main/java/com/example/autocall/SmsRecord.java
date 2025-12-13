package com.example.autocall;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsRecord {
    private long id;
    private String phoneNumber;
    private String message;
    private long timestamp;

    public SmsRecord() {
    }

    public SmsRecord(String phoneNumber, String message, long timestamp) {
        this.phoneNumber = phoneNumber;
        this.message = message;
        this.timestamp = timestamp;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 타임스탬프를 포맷된 날짜 문자열로 변환
     */
    public String getFormattedTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    @Override
    public String toString() {
        return "SmsRecord{" +
                "id=" + id +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
