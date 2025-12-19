package com.example.autocall

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
        private var connectedTime: Long = 0 // 실제 통화 연결 시간
        private var isCallConnected = false
        private var disconnectRunnable: Runnable? = null
        private var noAnswerRunnable: Runnable? = null

        // 타이머 설정 (동적으로 변경 가능)
        private var noAnswerTimeout = 30000L // 30초 (연결 대기 시간) - 기본값
        private var connectedDisconnectDelay = 20000L // 20초 (통화 연결 후 자동 종료) - 기본값

        private var callEndedListener: OnCallEndedListener? = null

        // 오디오 상태 저장 (복원용)
        private var previousSpeakerphoneOn: Boolean? = null
        private var previousMicMute: Boolean? = null

        /**
         * 통화 타이머 설정
         * @param timeoutSeconds 전화 시도 시간(초)
         * @param durationSeconds 연결 후 대기 시간(초)
         */
        fun setCallTimeouts(timeoutSeconds: Int, durationSeconds: Int) {
            noAnswerTimeout = timeoutSeconds * 1000L
            connectedDisconnectDelay = durationSeconds * 1000L
            Log.d(TAG, "타이머 설정 업데이트: 시도=${timeoutSeconds}초, 대기=${durationSeconds}초")
        }

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
            connectedTime = 0
            callStartTime = System.currentTimeMillis()

            // 30초 타이머 시작 (OFFHOOK 상태에 도달하지 못하면 연결 실패)
            noAnswerRunnable?.let {
                handler.removeCallbacks(it)
            }

            noAnswerRunnable = Runnable {
                if (offhookTime == 0L && currentPhoneNumber != null) {
                    // OFFHOOK 상태에 도달하지 못함 = 전화 받지 않음
                    ApiClient.recordCall(currentPhoneNumber!!, "rejected")
                    Log.d(TAG, "${noAnswerTimeout/1000}초 이내 OFFHOOK 도달 실패 (전화 받지 않음): $currentPhoneNumber")

                    // 다음 전화로 진행
                    callEndedListener?.onCallEnded()
                }
            }
            handler.postDelayed(noAnswerRunnable!!, noAnswerTimeout)
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
            connectedTime = 0
            callStartTime = 0
            previousSpeakerphoneOn = null
            previousMicMute = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // 발신 전화번호 가져오기 (CallManager에서)
        if (currentPhoneNumber == null) {
            currentPhoneNumber = CallManager.getLastCalledNumber()
        }

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // 전화 수신 중 (발신이 아닌 경우) - 자동으로 거부
                Log.d(TAG, "========================================")
                Log.d(TAG, "수신 전화 감지: $incomingNumber")
                Log.d(TAG, "자동 거부 처리 시작...")
                Log.d(TAG, "========================================")

                // 수신 전화 자동 거부
                rejectIncomingCall(context)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // 통화 중 (전화가 연결됨)
                if (currentPhoneNumber != null) {
                    Log.d(TAG, "발신 통화 중 - 전화번호: $currentPhoneNumber")
                    handleCallConnected(context)
                } else {
                    Log.d(TAG, "OFFHOOK 상태이지만 발신 전화번호 없음 (수신 전화일 수 있음)")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // 통화 종료
                Log.d(TAG, "통화 종료")
                if (currentPhoneNumber != null) {
                    handleCallEnded(context)
                } else {
                    Log.d(TAG, "발신 전화 없음 - 수신 전화 종료됨")
                }
            }
        }
    }

    /**
     * 전화 연결 처리 (OFFHOOK 상태)
     * OFFHOOK 상태 도달 = 통화 연결로 간주
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

        // OFFHOOK 상태 = 통화 연결됨
        connectedTime = System.currentTimeMillis()
        isCallConnected = true

        currentPhoneNumber?.let {
            ApiClient.recordCall(it, "connected")
        }

        Log.d(TAG, "OFFHOOK 상태 (통화 연결됨): $currentPhoneNumber")

        // 오디오 설정 적용 (스피커/마이크 끄기)
        applyAudioSettings(context)

        // Context의 약한 참조 저장 (메모리 누수 방지)
        val contextRef = WeakReference(context)

        // 설정된 시간 후 자동으로 전화 끊기
        disconnectRunnable = Runnable {
            contextRef.get()?.let { ctx ->
                Log.d(TAG, "${connectedDisconnectDelay/1000}초 경과, 전화 종료 시도: $currentPhoneNumber")
                disconnectCall(ctx)
            }
        }
        handler.postDelayed(disconnectRunnable!!, connectedDisconnectDelay)
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

        // 오디오 설정 복원
        restoreAudioSettings(context)

        // 통화 종료 상태 기록
        currentPhoneNumber?.let { number ->
            val now = System.currentTimeMillis()

            if (offhookTime > 0) {
                // OFFHOOK 상태 도달 = 통화 연결됨
                val callDuration = now - offhookTime

                Log.d(TAG, "========================================")
                Log.d(TAG, "통화 종료 분석: $number")
                Log.d(TAG, "OFFHOOK 도달: ${offhookTime > 0}")
                Log.d(TAG, "통화 시간: ${callDuration}ms")
                Log.d(TAG, "========================================")

                // OFFHOOK 도달 시 connected가 기록되었으므로 ended로 종료
                ApiClient.recordCall(number, "ended")
                Log.d(TAG, "✓ 정상 통화 종료: $number (통화 시간: ${callDuration}ms)")
            } else {
                // OFFHOOK에 도달하지 못했으면 전화 받지 않음
                ApiClient.recordCall(number, "rejected")
                Log.d(TAG, "✗ 전화 받지 않음: $number (OFFHOOK 도달 실패)")
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
     * 수신 전화 자동 거부
     */
    private fun rejectIncomingCall(context: Context) {
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
                        Log.d(TAG, "✓ TelecomManager로 수신 전화 거부 성공")
                    } else {
                        Log.w(TAG, "TelecomManager.endCall() 실패 - 기본 전화 앱이 아닐 수 있음")
                    }
                    return
                } else {
                    Log.w(TAG, "ANSWER_PHONE_CALLS 권한 없음")
                }
            }

            // Android 8.1 이하에서 Reflection 시도
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                try {
                    // ITelephony 인터페이스를 통한 전화 거부
                    val telephonyClass = Class.forName(telephonyManager.javaClass.name)
                    val getITelephonyMethod = telephonyClass.getDeclaredMethod("getITelephony")
                    getITelephonyMethod.isAccessible = true
                    val iTelephony = getITelephonyMethod.invoke(telephonyManager)

                    if (iTelephony != null) {
                        val iTelephonyClass = Class.forName(iTelephony.javaClass.name)
                        val endCallMethod = iTelephonyClass.getDeclaredMethod("endCall")
                        endCallMethod.isAccessible = true
                        endCallMethod.invoke(iTelephony)
                        Log.d(TAG, "✓ Reflection으로 수신 전화 거부 성공")
                        return
                    }
                } catch (e: NoSuchMethodException) {
                    Log.w(TAG, "endCall 메서드를 찾을 수 없음: ${e.message}")
                } catch (e: SecurityException) {
                    Log.w(TAG, "수신 전화 거부 권한 거부됨: ${e.message}")
                } catch (e: ClassNotFoundException) {
                    Log.w(TAG, "ITelephony 클래스를 찾을 수 없음: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "수신 전화 거부 실패: ${e.message}", e)
        }

        Log.w(TAG, "✗ 수신 전화 자동 거부 실패 - 기본 전화 앱으로 설정하거나 수동으로 거부해야 합니다")
    }

    /**
     * 상태 초기화
     */
    private fun resetState() {
        isCallConnected = false
        callStartTime = 0
        offhookTime = 0
        connectedTime = 0
        currentPhoneNumber = null
        previousSpeakerphoneOn = null
        previousMicMute = null
    }

    /**
     * 오디오 설정 적용 (스피커/마이크 끄기)
     */
    private fun applyAudioSettings(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.w(TAG, "AudioManager를 가져올 수 없음")
                return
            }

            // 현재 상태 저장 (복원용)
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            previousMicMute = audioManager.isMicrophoneMute

            Log.d(TAG, "========================================")
            Log.d(TAG, "오디오 설정 적용")
            Log.d(TAG, "이전 스피커폰 상태: $previousSpeakerphoneOn")
            Log.d(TAG, "이전 마이크 뮤트 상태: $previousMicMute")
            Log.d(TAG, "스피커 끄기 설정: ${MainActivity.isSpeakerMuted}")
            Log.d(TAG, "마이크 끄기 설정: ${MainActivity.isMicMuted}")
            Log.d(TAG, "========================================")

            // 스피커 끄기 설정이 체크되어 있으면 스피커폰 끄기
            if (MainActivity.isSpeakerMuted) {
                audioManager.isSpeakerphoneOn = false
                Log.d(TAG, "스피커폰 끄기 적용")
            }

            // 마이크 끄기 설정이 체크되어 있으면 마이크 음소거
            if (MainActivity.isMicMuted) {
                audioManager.isMicrophoneMute = true
                Log.d(TAG, "마이크 음소거 적용")
            }
        } catch (e: Exception) {
            Log.e(TAG, "오디오 설정 적용 실패: ${e.message}", e)
        }
    }

    /**
     * 오디오 설정 복원 (원래 상태로)
     */
    private fun restoreAudioSettings(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.w(TAG, "AudioManager를 가져올 수 없음")
                return
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "오디오 설정 복원")
            Log.d(TAG, "복원할 스피커폰 상태: $previousSpeakerphoneOn")
            Log.d(TAG, "복원할 마이크 뮤트 상태: $previousMicMute")
            Log.d(TAG, "========================================")

            // 이전 상태로 복원
            previousSpeakerphoneOn?.let { prev ->
                audioManager.isSpeakerphoneOn = prev
                Log.d(TAG, "스피커폰 상태 복원: $prev")
            }

            previousMicMute?.let { prev ->
                audioManager.isMicrophoneMute = prev
                Log.d(TAG, "마이크 뮤트 상태 복원: $prev")
            }

            // 복원 후 초기화
            previousSpeakerphoneOn = null
            previousMicMute = null
        } catch (e: Exception) {
            Log.e(TAG, "오디오 설정 복원 실패: ${e.message}", e)
        }
    }
}
