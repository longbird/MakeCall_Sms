package com.example.autocall

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * SMS Provider를 직접 모니터링하는 ContentObserver
 * BroadcastReceiver가 차단된 경우를 대비한 대체 방법
 */
class SmsContentObserver(private val context: Context, handler: Handler) : ContentObserver(handler) {

    companion object {
        private const val TAG = "SmsContentObserver"
    }

    private var lastMessageId = -1L
    private var lastCheckTime = System.currentTimeMillis()

    init {
        Log.d(TAG, "SmsContentObserver 생성됨")
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        Log.d(TAG, "onChange 호출됨 - selfChange: $selfChange")
        checkNewSms()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "onChange 호출됨 - URI: $uri")
        checkNewSms()
    }

    /**
     * 새로운 SMS가 있는지 확인
     */
    private fun checkNewSms() {
        try {
            // SMS inbox URI
            val smsUri = Uri.parse("content://sms/inbox")

            // 최근 SMS만 조회 (지난 10초 이내)
            val currentTime = System.currentTimeMillis()
            val selection = "date > ?"
            val selectionArgs = arrayOf((lastCheckTime - 5000).toString()) // 5초 여유

            // SMS 조회
            val cursor = context.contentResolver.query(
                smsUri,
                arrayOf("_id", "address", "body", "date"),
                selection,
                selectionArgs,
                "date DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val idIndex = it.getColumnIndex("_id")
                        val addressIndex = it.getColumnIndex("address")
                        val bodyIndex = it.getColumnIndex("body")
                        val dateIndex = it.getColumnIndex("date")

                        if (idIndex >= 0 && addressIndex >= 0 && bodyIndex >= 0 && dateIndex >= 0) {
                            val id = it.getLong(idIndex)
                            val address = it.getString(addressIndex)
                            val body = it.getString(bodyIndex)
                            val date = it.getLong(dateIndex)

                            // 중복 방지
                            if (id != lastMessageId && date > lastCheckTime) {
                                lastMessageId = id
                                lastCheckTime = currentTime

                                Log.d(TAG, "========================================")
                                Log.d(TAG, "새로운 SMS 감지!")
                                Log.d(TAG, "ID: $id")
                                Log.d(TAG, "발신번호: $address")
                                Log.d(TAG, "메시지: $body")
                                Log.d(TAG, "수신시간: $date")
                                Log.d(TAG, "========================================")

                                // 데이터베이스에 저장
                                saveSmsToDatabase(address, body, date)

                                // 메인 스레드에서 Toast 표시
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(
                                        context,
                                        "SMS 수신됨 (ContentObserver): $address",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    } while (it.moveToNext())
                } else {
                    Log.d(TAG, "조회된 SMS 없음")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS 확인 중 오류: ${e.message}", e)
        }
    }

    /**
     * SMS를 데이터베이스에 저장
     */
    private fun saveSmsToDatabase(phoneNumber: String, message: String, timestamp: Long) {
        try {
            val dbHelper = DatabaseHelper(context)
            val id = dbHelper.insertSmsRecord(phoneNumber, message, timestamp)
            dbHelper.close()

            if (id != -1L) {
                Log.d(TAG, "SMS 저장 성공: ID = $id")

                // MainActivity에 SMS 수신 알림 (명시적 Intent 사용)
                val updateIntent = Intent(MainActivity.ACTION_SMS_RECEIVED)
                updateIntent.setPackage(context.packageName)
                context.sendBroadcast(updateIntent)
                Log.d(TAG, "UI 갱신 브로드캐스트 전송됨 (ContentObserver, 패키지: ${context.packageName})")

                // REST API로 SMS 기록 전송
                ApiClient.recordSms(phoneNumber, message)
            } else {
                Log.e(TAG, "SMS 저장 실패!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS 저장 중 오류: ${e.message}", e)
        }
    }
}
