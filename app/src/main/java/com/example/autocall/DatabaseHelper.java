package com.example.autocall;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "DatabaseHelper";
    
    // 데이터베이스 정보
    private static final String DATABASE_NAME = "AutoCallSms.db";
    private static final int DATABASE_VERSION = 1;

    // 테이블 정보
    private static final String TABLE_SMS = "sms_records";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_PHONE_NUMBER = "phone_number";
    private static final String COLUMN_MESSAGE = "message";
    private static final String COLUMN_TIMESTAMP = "timestamp";
    private static final String COLUMN_CREATED_AT = "created_at";

    // 테이블 생성 쿼리
    private static final String CREATE_TABLE_SMS = 
        "CREATE TABLE " + TABLE_SMS + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_PHONE_NUMBER + " TEXT NOT NULL, " +
        COLUMN_MESSAGE + " TEXT NOT NULL, " +
        COLUMN_TIMESTAMP + " INTEGER NOT NULL, " +
        COLUMN_CREATED_AT + " DATETIME DEFAULT CURRENT_TIMESTAMP" +
        ")";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_SMS);
        Log.d(TAG, "데이터베이스 테이블 생성 완료");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SMS);
        onCreate(db);
    }

    /**
     * SMS 레코드 삽입
     */
    public long insertSmsRecord(String phoneNumber, String message, long timestamp) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        
        values.put(COLUMN_PHONE_NUMBER, phoneNumber);
        values.put(COLUMN_MESSAGE, message);
        values.put(COLUMN_TIMESTAMP, timestamp);
        
        long id = db.insert(TABLE_SMS, null, values);
        
        if (id != -1) {
            Log.d(TAG, "SMS 레코드 삽입 성공: ID = " + id);
        } else {
            Log.e(TAG, "SMS 레코드 삽입 실패");
        }
        
        return id;
    }

    /**
     * 모든 SMS 레코드 조회 (최신순)
     */
    public List<SmsRecord> getAllSmsRecords() {
        List<SmsRecord> records = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_SMS + 
                       " ORDER BY " + COLUMN_TIMESTAMP + " DESC";
        
        Cursor cursor = db.rawQuery(query, null);
        
        if (cursor.moveToFirst()) {
            do {
                SmsRecord record = new SmsRecord();
                record.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                record.setPhoneNumber(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)));
                record.setMessage(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)));
                record.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)));
                
                records.add(record);
            } while (cursor.moveToNext());
        }
        
        cursor.close();
        Log.d(TAG, "조회된 레코드 수: " + records.size());
        
        return records;
    }

    /**
     * 특정 전화번호의 SMS 레코드 조회
     */
    public List<SmsRecord> getSmsRecordsByPhoneNumber(String phoneNumber) {
        List<SmsRecord> records = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_SMS + 
                       " WHERE " + COLUMN_PHONE_NUMBER + " = ?" +
                       " ORDER BY " + COLUMN_TIMESTAMP + " DESC";
        
        Cursor cursor = db.rawQuery(query, new String[]{phoneNumber});
        
        if (cursor.moveToFirst()) {
            do {
                SmsRecord record = new SmsRecord();
                record.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                record.setPhoneNumber(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)));
                record.setMessage(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)));
                record.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)));
                
                records.add(record);
            } while (cursor.moveToNext());
        }
        
        cursor.close();
        return records;
    }

    /**
     * SMS 레코드 삭제
     */
    public boolean deleteSmsRecord(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        int result = db.delete(TABLE_SMS, COLUMN_ID + " = ?", 
                              new String[]{String.valueOf(id)});
        return result > 0;
    }

    /**
     * 모든 SMS 레코드 삭제
     */
    public void deleteAllSmsRecords() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_SMS, null, null);
        Log.d(TAG, "모든 SMS 레코드 삭제 완료");
    }

    /**
     * 레코드 수 조회
     */
    public int getRecordCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        String query = "SELECT COUNT(*) FROM " + TABLE_SMS;
        Cursor cursor = db.rawQuery(query, null);
        
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        
        return count;
    }
}
