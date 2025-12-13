package com.example.autocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive 호출됨 - Action: ${intent.action ?: "null"}")

        val pendingResult = goAsync()

        // WakeLock 획득
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoCallSms::SmsReceiverWakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L /* 10 minutes */)

        Thread {
            try {
                handleSmsReceive(context, intent)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }.start()
    }

    private fun handleSmsReceive(context: Context, intent: Intent) {
        if (intent.action == SMS_RECEIVED) {
            Log.d(TAG, "SMS_RECEIVED 액션 확인됨")
            val bundle = intent.extras

            if (bundle != null) {
                Log.d(TAG, "Bundle이 null이 아님")
                try {
                    val pdus = bundle.get("pdus") as? Array<*>
                    val format = bundle.getString("format")

                    if (pdus != null) {
                        Log.d(TAG, "PDU 개수: ${pdus.size}")
                        for (pdu in pdus) {
                            val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                SmsMessage.createFromPdu(pdu as ByteArray, format)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsMessage.createFromPdu(pdu as ByteArray)
                            }

                            val phoneNumber = smsMessage.displayOriginatingAddress
                            val message = smsMessage.messageBody
                            val timestamp = smsMessage.timestampMillis

                            Log.d(TAG, "========================================")
                            Log.d(TAG, "SMS 수신 성공!")
                            Log.d(TAG, "발신번호: $phoneNumber")
                            Log.d(TAG, "메시지: $message")
                            Log.d(TAG, "타임스탬프: $timestamp")
                            Log.d(TAG, "========================================")

                            // 최근 통화 여부와 상관없이 저장
                            // 로컬 데이터베이스에 저장
                            val dbHelper = DatabaseHelper(context)
                            val id = dbHelper.insertSmsRecord(phoneNumber, message, timestamp)
                            dbHelper.close()

                            if (id != -1L) {
                                Log.d(TAG, "SMS 저장 성공: ID = $id")

                                // MainActivity에 SMS 수신 알림 (명시적 Intent 사용)
                                val updateIntent = Intent(MainActivity.ACTION_SMS_RECEIVED)
                                updateIntent.setPackage(context.packageName)
                                context.sendBroadcast(updateIntent)
                                Log.d(TAG, "UI 갱신 브로드캐스트 전송됨 (패키지: ${context.packageName})")

                                // 동기화를 위한 객체
                                val lock = Object()

                                ApiClient.recordSms(phoneNumber, message) {
                                    synchronized(lock) {
                                        lock.notify()
                                    }
                                }

                                // API 호출 완료 대기 (최대 10초)
                                synchronized(lock) {
                                    try {
                                        lock.wait(10000)
                                    } catch (e: InterruptedException) {
                                        Log.e(TAG, "API 응답 대기 중 인터럽트 발생")
                                    }
                                }

                                // 메인 스레드에서 Toast 표시
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(
                                        context,
                                        "응답 SMS 저장됨: $phoneNumber",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                Log.e(TAG, "SMS 저장 실패!")
                            }
                        }
                    } else {
                        Log.w(TAG, "pdus가 null임")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SMS 처리 오류: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Bundle이 null임")
            }
        } else {
            Log.w(TAG, "SMS_RECEIVED 액션이 아님")
        }
    }

    /**
     * 전화번호 매칭 (국가 코드 등 다양한 형식 처리)
     */
    private fun isPhoneNumberMatch(number1: String?, number2: String?): Boolean {
        if (number1 == null || number2 == null) {
            return false
        }

        // 숫자만 추출
        val cleaned1 = number1.replace(Regex("[^0-9]"), "")
        val cleaned2 = number2.replace(Regex("[^0-9]"), "")

        // 완전 일치
        if (cleaned1 == cleaned2) {
            return true
        }

        // 국가 코드 적용 여부에 따른 매칭
        // 예: 01012345678 vs +821012345678
        if (cleaned1.length >= 10 && cleaned2.length >= 10) {
            val suffix1 = cleaned1.substring(cleaned1.length - 10)
            val suffix2 = cleaned2.substring(cleaned2.length - 10)
            return suffix1 == suffix2
        }

        return false
    }
}
