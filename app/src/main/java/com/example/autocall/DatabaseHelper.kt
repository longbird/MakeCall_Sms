package com.example.autocall

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"

        // 데이터베이스 정보
        private const val DATABASE_NAME = "AutoCallSms.db"
        private const val DATABASE_VERSION = 1

        // 테이블 정보
        private const val TABLE_SMS = "sms_records"
        private const val COLUMN_ID = "id"
        private const val COLUMN_PHONE_NUMBER = "phone_number"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_CREATED_AT = "created_at"

        // 테이블 생성 쿼리
        private const val CREATE_TABLE_SMS =
            "CREATE TABLE $TABLE_SMS (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_PHONE_NUMBER TEXT NOT NULL, " +
                    "$COLUMN_MESSAGE TEXT NOT NULL, " +
                    "$COLUMN_TIMESTAMP INTEGER NOT NULL, " +
                    "$COLUMN_CREATED_AT DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SMS)
        Log.d(TAG, "데이터베이스 테이블 생성 완료")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SMS")
        onCreate(db)
    }

    /**
     * SMS 레코드 삽입
     */
    fun insertSmsRecord(phoneNumber: String, message: String, timestamp: Long): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_PHONE_NUMBER, phoneNumber)
            put(COLUMN_MESSAGE, message)
            put(COLUMN_TIMESTAMP, timestamp)
        }

        val id = db.insert(TABLE_SMS, null, values)

        if (id != -1L) {
            Log.d(TAG, "SMS 레코드 삽입 성공: ID = $id")
        } else {
            Log.e(TAG, "SMS 레코드 삽입 실패")
        }

        return id
    }

    /**
     * 모든 SMS 레코드 조회 (최신순)
     */
    fun getAllSmsRecords(): List<SmsRecord> {
        val records = mutableListOf<SmsRecord>()
        val db = this.readableDatabase

        val query = "SELECT * FROM $TABLE_SMS ORDER BY $COLUMN_TIMESTAMP DESC"

        db.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val record = SmsRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
                        message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                    )
                    records.add(record)
                } while (cursor.moveToNext())
            }
        }

        Log.d(TAG, "조회된 레코드 수: ${records.size}")
        return records
    }

    /**
     * 특정 전화번호의 SMS 레코드 조회
     */
    fun getSmsRecordsByPhoneNumber(phoneNumber: String): List<SmsRecord> {
        val records = mutableListOf<SmsRecord>()
        val db = this.readableDatabase

        val query = "SELECT * FROM $TABLE_SMS WHERE $COLUMN_PHONE_NUMBER = ? ORDER BY $COLUMN_TIMESTAMP DESC"

        db.rawQuery(query, arrayOf(phoneNumber)).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val record = SmsRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
                        message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                    )
                    records.add(record)
                } while (cursor.moveToNext())
            }
        }

        return records
    }

    /**
     * SMS 레코드 삭제
     */
    fun deleteSmsRecord(id: Long): Boolean {
        val db = this.writableDatabase
        val result = db.delete(TABLE_SMS, "$COLUMN_ID = ?", arrayOf(id.toString()))
        return result > 0
    }

    /**
     * 모든 SMS 레코드 삭제
     */
    fun deleteAllSmsRecords() {
        val db = this.writableDatabase
        db.delete(TABLE_SMS, null, null)
        Log.d(TAG, "모든 SMS 레코드 삭제 완료")
    }

    /**
     * 레코드 수 조회
     */
    fun getRecordCount(): Int {
        val db = this.readableDatabase
        val query = "SELECT COUNT(*) FROM $TABLE_SMS"

        db.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }

        return 0
    }
}
