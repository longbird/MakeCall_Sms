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
 * SMS/MMS Provider를 직접 모니터링하는 ContentObserver
 * BroadcastReceiver가 차단된 경우를 대비한 대체 방법
 */
class SmsContentObserver(private val context: Context, handler: Handler) : ContentObserver(handler) {

    companion object {
        private const val TAG = "SmsContentObserver"
    }

    private var lastSmsId = -1L
    private var lastMmsId = -1L
    private var lastCheckTime = System.currentTimeMillis()

    init {
        Log.d(TAG, "SmsContentObserver 생성됨")
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        Log.d(TAG, "onChange 호출됨 - selfChange: $selfChange")
        checkNewMessages()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "onChange 호출됨 - URI: $uri")
        checkNewMessages()
    }

    /**
     * 새로운 SMS/MMS가 있는지 확인
     */
    private fun checkNewMessages() {
        checkNewSms()
        checkNewMms()
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
                            if (id != lastSmsId && date > lastCheckTime) {
                                lastSmsId = id
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
     * 새로운 MMS가 있는지 확인
     */
    private fun checkNewMms() {
        try {
            // MMS inbox URI
            val mmsUri = Uri.parse("content://mms/inbox")

            // 최근 MMS만 조회 (지난 10초 이내)
            val currentTime = System.currentTimeMillis()
            val selection = "date > ?"
            val selectionArgs = arrayOf(((lastCheckTime - 5000) / 1000).toString()) // MMS date는 초 단위

            // MMS 조회
            val cursor = context.contentResolver.query(
                mmsUri,
                arrayOf("_id", "date", "m_type"),
                selection,
                selectionArgs,
                "date DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val idIndex = it.getColumnIndex("_id")
                        val dateIndex = it.getColumnIndex("date")
                        val typeIndex = it.getColumnIndex("m_type")

                        if (idIndex >= 0 && dateIndex >= 0) {
                            val id = it.getLong(idIndex)
                            val date = it.getLong(dateIndex) * 1000 // 초를 밀리초로 변환
                            val mType = if (typeIndex >= 0) it.getInt(typeIndex) else 0

                            // 중복 방지 및 수신 MMS만 처리 (m_type = 132)
                            if (id != lastMmsId && date > lastCheckTime && mType == 132) {
                                lastMmsId = id

                                Log.d(TAG, "========================================")
                                Log.d(TAG, "새로운 MMS 감지!")
                                Log.d(TAG, "ID: $id")
                                Log.d(TAG, "수신시간: $date")
                                Log.d(TAG, "========================================")

                                // MMS 발신번호와 내용 추출
                                val address = getMmsAddress(id)
                                val body = getMmsText(id)

                                if (!address.isNullOrEmpty()) {
                                    Log.d(TAG, "MMS 발신번호: $address")
                                    Log.d(TAG, "MMS 내용: $body")

                                    // 데이터베이스에 저장
                                    saveSmsToDatabase(address, body ?: "[MMS - 텍스트 없음]", date)

                                    // 메인 스레드에서 Toast 표시
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(
                                            context,
                                            "MMS 수신됨 (ContentObserver): $address",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    Log.w(TAG, "MMS 발신번호를 가져올 수 없음")
                                }
                            }
                        }
                    } while (it.moveToNext())
                } else {
                    Log.d(TAG, "조회된 MMS 없음")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 확인 중 오류: ${e.message}", e)
        }
    }

    /**
     * MMS 발신번호 가져오기
     */
    private fun getMmsAddress(mmsId: Long): String? {
        try {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "type"),
                null,
                null,
                null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val addressIndex = it.getColumnIndex("address")
                    val typeIndex = it.getColumnIndex("type")

                    if (addressIndex >= 0 && typeIndex >= 0) {
                        val type = it.getInt(typeIndex)
                        // type 137 = 발신자 (FROM)
                        if (type == 137) {
                            return it.getString(addressIndex)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 발신번호 조회 오류: ${e.message}", e)
        }
        return null
    }

    /**
     * MMS 텍스트 내용 가져오기
     */
    private fun getMmsText(mmsId: Long): String? {
        try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = context.contentResolver.query(
                partUri,
                arrayOf("_id", "ct", "text", "_data"),
                "mid = ?",
                arrayOf(mmsId.toString()),
                null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val ctIndex = it.getColumnIndex("ct")
                    val textIndex = it.getColumnIndex("text")
                    val dataIndex = it.getColumnIndex("_data")

                    if (ctIndex >= 0) {
                        val contentType = it.getString(ctIndex)

                        // text/plain 타입의 part에서 텍스트 추출
                        if (contentType == "text/plain") {
                            if (textIndex >= 0) {
                                val text = it.getString(textIndex)
                                if (!text.isNullOrEmpty()) {
                                    return text
                                }
                            }

                            // text가 null이면 _data를 사용하여 파일에서 읽기
                            if (dataIndex >= 0) {
                                val data = it.getString(dataIndex)
                                if (!data.isNullOrEmpty()) {
                                    val partId = it.getLong(it.getColumnIndex("_id"))
                                    return readMmsPartText(partId)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 텍스트 조회 오류: ${e.message}", e)
        }
        return null
    }

    /**
     * MMS part에서 텍스트 읽기
     */
    private fun readMmsPartText(partId: Long): String? {
        try {
            val partUri = Uri.parse("content://mms/part/$partId")
            val inputStream = context.contentResolver.openInputStream(partUri)
            inputStream?.use {
                return it.bufferedReader().use { reader -> reader.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS part 텍스트 읽기 오류: ${e.message}", e)
        }
        return null
    }

    /**
     * SMS/MMS를 데이터베이스에 저장
     */
    private fun saveSmsToDatabase(phoneNumber: String, message: String, timestamp: Long) {
        try {
            val dbHelper = DatabaseHelper(context)
            val id = dbHelper.insertSmsRecord(phoneNumber, message, timestamp)
            dbHelper.close()

            if (id != -1L) {
                Log.d(TAG, "SMS/MMS 저장 성공: ID = $id")

                // MainActivity에 SMS 수신 알림 (명시적 Intent 사용)
                val updateIntent = Intent(MainActivity.ACTION_SMS_RECEIVED)
                updateIntent.setPackage(context.packageName)
                context.sendBroadcast(updateIntent)
                Log.d(TAG, "UI 갱신 브로드캐스트 전송됨 (ContentObserver, 패키지: ${context.packageName})")

                // REST API로 SMS 기록 전송
                ApiClient.recordSms(phoneNumber, message)
            } else {
                Log.e(TAG, "SMS/MMS 저장 실패!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS/MMS 저장 중 오류: ${e.message}", e)
        }
    }
}
