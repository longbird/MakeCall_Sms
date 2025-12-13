package com.example.autocall

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.lang.ref.WeakReference

class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"
        private val handler = Handler(Looper.getMainLooper())
        private var currentPhoneNumber: String? = null
        private var callStartTime: Long = 0
        private var offhookTime: Long = 0
        private var isCallConnected = false
        private var disconnectRunnable: Runnable? = null
        private var noAnswerRunnable: Runnable? = null

        // OFFHOOK은 다이얼링 시작 시점이므로 충분한 시간을 줌 (통화 연결 + 3초 대기)
        private const val CONNECTED_DISCONNECT_DELAY = 20000L // 20초 (다이얼링 시간 포함)
        private const val NO_ANSWER_TIMEOUT = 30000L // 30초 (연결 대기 시간)
        private var callEndedListener: OnCallEndedListener? = null

        /**
         * 전화 종료 시 호출될 리스너 인터페이스
         */
        interface OnCallEndedListener {
            fun onCallEnded()
        }

        /**
         * 전화 종료 리스너 설정
         */
        fun setOnCallEndedListener(listener: OnCallEndedListener?) {
            callEndedListener = listener
        }

        /**
         * 전화 걸기 시작 시 호출 (전화번호 설정)
         */
        fun setCurrentPhoneNumber(phoneNumber: String) {
            currentPhoneNumber = phoneNumber
            isCallConnected = false
            offhookTime = 0
            callStartTime = System.currentTimeMillis()

            // 30초 타이머 시작 (OFFHOOK 상태에 도달하지 못하면 연결 실패)
            noAnswerRunnable?.let {
                handler.removeCallbacks(it)
            }

            noAnswerRunnable = Runnable {
                if (offhookTime == 0L && currentPhoneNumber != null) {
                    // OFFHOOK 상태에 도달하지 못함 = 연결 실패
                    ApiClient.recordCall(currentPhoneNumber!!, "no_answer")
                    Log.d(TAG, "30초 이내 OFFHOOK 도달 실패 (연결 안 됨): $currentPhoneNumber")

                    // 다음 전화로 진행
                    callEndedListener?.onCallEnded()
                }
            }
            handler.postDelayed(noAnswerRunnable!!, NO_ANSWER_TIMEOUT)
        }

        /**
         * 리스너 및 타이머 정리 (메모리 누수 방지)
         */
        fun cleanup() {
            disconnectRunnable?.let {
                handler.removeCallbacks(it)
                disconnectRunnable = null
            }
            noAnswerRunnable?.let {
                handler.removeCallbacks(it)
                noAnswerRunnable = null
            }
            callEndedListener = null
            currentPhoneNumber = null
            isCallConnected = false
            offhookTime = 0
            callStartTime = 0
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // 발신 전화번호 가져오기 (CallManager에서)
        if (currentPhoneNumber == null) {
            currentPhoneNumber = CallManager.getLastCalledNumber()
        }

        if (state != null && currentPhoneNumber != null) {
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // 전화 수신 중 (발신이 아닌 경우)
                    Log.d(TAG, "전화 수신 중: $phoneNumber")
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // 통화 중 (전화가 연결됨)
                    Log.d(TAG, "통화 중 - 전화번호: $currentPhoneNumber")
                    handleCallConnected(context)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // 통화 종료
                    Log.d(TAG, "통화 종료")
                    handleCallEnded(context)
                }
            }
        }
    }

    /**
     * 전화 연결 처리 (OFFHOOK 상태)
     * 주의: OFFHOOK은 다이얼링 시작 시점이며, 실제 통화 연결 시점이 아님
     */
    private fun handleCallConnected(context: Context) {
        if (offhookTime > 0) {
            return // 이미 처리됨
        }

        offhookTime = System.currentTimeMillis()

        // 30초 이내 연결안됨 체크를 위한 타이머 취소
        noAnswerRunnable?.let {
            handler.removeCallbacks(it)
            noAnswerRunnable = null
        }

        // OFFHOOK 상태 기록 (다이얼링 시작)
        currentPhoneNumber?.let {
            ApiClient.recordCall(it, "dialing")
        }

        // Context의 약한 참조 저장 (메모리 누수 방지)
        val contextRef = WeakReference(context)

        // 20초 후 자동으로 전화 끊기 (다이얼링 + 통화 시간 포함)
        disconnectRunnable = Runnable {
            contextRef.get()?.let { ctx ->
                Log.d(TAG, "20초 경과, 전화 종료 시도: $currentPhoneNumber")
                disconnectCall(ctx)
            }
        }
        handler.postDelayed(disconnectRunnable!!, CONNECTED_DISCONNECT_DELAY)

        Log.d(TAG, "OFFHOOK 상태 (다이얼링 시작): $currentPhoneNumber, 20초 후 자동 종료 예정")
    }

    /**
     * 전화 종료 처리
     */
    private fun handleCallEnded(context: Context) {
        // 타이머 정리
        disconnectRunnable?.let {
            handler.removeCallbacks(it)
            disconnectRunnable = null
        }

        noAnswerRunnable?.let {
            handler.removeCallbacks(it)
            noAnswerRunnable = null
        }

        // 통화 종료 상태 기록
        currentPhoneNumber?.let { number ->
            if (offhookTime > 0) {
                // OFFHOOK 상태까지 도달했으면 통화 종료로 기록
                ApiClient.recordCall(number, "ended")
                Log.d(TAG, "통화 종료 기록: $number")
            } else {
                // OFFHOOK에 도달하지 못했으면 연결 실패
                ApiClient.recordCall(number, "no_answer")
                Log.d(TAG, "연결안됨으로 기록: $number")
            }
        }

        // 상태 초기화
        resetState()

        // 다음 전화 걸기 콜백 호출
        callEndedListener?.onCallEnded()
    }

    /**
     * 전화 자동 끊기
     * 주의: Android 9+ 에서는 기본 전화 앱이 아니면 endCall()이 작동하지 않음
     */
    private fun disconnectCall(context: Context) {
        try {
            // Android 9 (API 28) 이상
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null &&
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ANSWER_PHONE_CALLS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val success = telecomManager.endCall()
                    if (success) {
                        Log.d(TAG, "TelecomManager를 사용하여 전화 종료 성공: $currentPhoneNumber")
                    } else {
                        Log.w(TAG, "TelecomManager.endCall() 실패 - 기본 전화 앱이 아닐 수 있음")
                    }
                    return
                } else {
                    Log.w(TAG, "ANSWER_PHONE_CALLS 권한 없음")
                }
            }

            // Android 8.1 이하에서 Reflection 시도 (deprecated, 작동 안 할 수 있음)
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                try {
                    val telephonyClass = Class.forName(telephonyManager.javaClass.name)
                    val endCallMethod = telephonyClass.getDeclaredMethod("endCall")
                    endCallMethod.isAccessible = true
                    endCallMethod.invoke(telephonyManager)
                    Log.d(TAG, "Reflection을 사용하여 전화 종료: $currentPhoneNumber")
                } catch (e: NoSuchMethodException) {
                    Log.w(TAG, "endCall 메서드를 찾을 수 없음 (Android 버전에서 지원 안 함)")
                } catch (e: SecurityException) {
                    Log.w(TAG, "전화 끊기 권한 거부됨")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "전화 끊기 실패: ${e.message}", e)
        }

        // 모든 시도가 실패했을 때의 로그
        Log.w(TAG, "자동 전화 종료 실패. 사용자가 수동으로 종료해야 할 수 있음.")
    }

    /**
     * 상태 초기화
     */
    private fun resetState() {
        isCallConnected = false
        callStartTime = 0
        offhookTime = 0
        currentPhoneNumber = null
    }
}
