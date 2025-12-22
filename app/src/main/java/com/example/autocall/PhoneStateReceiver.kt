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

        // 중복 처리 방지 플래그
        private var isProcessingCallEnd = false

        // 우리가 전화를 끊었는지 여부 (타이머에 의한 자동 종료)
        private var wasDisconnectedByUs = false

        // 오디오 상태 저장 (복원용) - 시작/중지 시에만 사용
        private var previousSpeakerphoneOn: Boolean? = null
        private var previousMicMute: Boolean? = null
        private var previousVoiceCallVolume: Int? = null
        private var isAudioSettingsApplied = false

        /**
         * 오디오 설정 초기화 (시작 시 1회 - 원래 상태 저장 및 즉시 음소거 적용)
         * 발신음을 들리지 않게 하려면 전화 걸기 전에 볼륨을 0으로 설정해야 함
         */
        fun applyAudioSettings(context: Context) {
            if (isAudioSettingsApplied) {
                Log.d(TAG, "오디오 설정 이미 초기화됨 - 건너뜀")
                return
            }

            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager == null) {
                    Log.w(TAG, "AudioManager를 가져올 수 없음")
                    return
                }

                // 현재 상태 저장 (복원용) - 시작 시 1회만
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                previousMicMute = audioManager.isMicrophoneMute
                previousVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

                Log.d(TAG, "========================================")
                Log.d(TAG, "오디오 원래 상태 저장 (시작)")
                Log.d(TAG, "이전 스피커폰 상태: $previousSpeakerphoneOn")
                Log.d(TAG, "이전 마이크 뮤트 상태: $previousMicMute")
                Log.d(TAG, "이전 통화 볼륨: $previousVoiceCallVolume")
                Log.d(TAG, "스피커 끄기 설정: ${MainActivity.isSpeakerMuted}")
                Log.d(TAG, "마이크 끄기 설정: ${MainActivity.isMicMuted}")
                Log.d(TAG, "========================================")

                // 발신음을 들리지 않게 하기 위해 시작 시 바로 볼륨 0으로 설정
                if (MainActivity.isSpeakerMuted) {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                    Log.d(TAG, "시작 시 통화 볼륨 0으로 설정 (발신음 제거)")
                }

                if (MainActivity.isMicMuted) {
                    audioManager.isMicrophoneMute = true
                    Log.d(TAG, "시작 시 마이크 음소거 적용")
                }

                isAudioSettingsApplied = true
            } catch (e: Exception) {
                Log.e(TAG, "오디오 설정 초기화 실패: ${e.message}", e)
            }
        }

        /**
         * 통화 중 오디오 음소거 적용 (OFFHOOK 시 매번 호출)
         */
        fun applyCallAudioMute(context: Context) {
            if (!isAudioSettingsApplied) {
                Log.d(TAG, "오디오 설정 초기화 안 됨 - 음소거 건너뜀")
                return
            }

            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager == null) {
                    Log.w(TAG, "AudioManager를 가져올 수 없음")
                    return
                }

                Log.d(TAG, "========================================")
                Log.d(TAG, "통화 중 오디오 음소거 적용")
                Log.d(TAG, "스피커 끄기 설정: ${MainActivity.isSpeakerMuted}")
                Log.d(TAG, "마이크 끄기 설정: ${MainActivity.isMicMuted}")
                Log.d(TAG, "========================================")

                // 스피커 끄기 설정이 체크되어 있으면 통화 볼륨을 0으로 설정
                if (MainActivity.isSpeakerMuted) {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                    Log.d(TAG, "스피커폰 끄기 및 통화 볼륨 0으로 설정 적용")
                }

                // 마이크 끄기 설정이 체크되어 있으면 마이크 음소거
                if (MainActivity.isMicMuted) {
                    audioManager.isMicrophoneMute = true
                    Log.d(TAG, "마이크 음소거 적용")
                }
            } catch (e: Exception) {
                Log.e(TAG, "통화 중 오디오 음소거 적용 실패: ${e.message}", e)
            }
        }

        /**
         * 오디오 설정 복원 (중지 시 1회만 호출)
         */
        fun restoreAudioSettings(context: Context) {
            if (!isAudioSettingsApplied) {
                Log.d(TAG, "오디오 설정 적용된 적 없음 - 복원 건너뜀")
                return
            }

            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager == null) {
                    Log.w(TAG, "AudioManager를 가져올 수 없음")
                    return
                }

                Log.d(TAG, "========================================")
                Log.d(TAG, "오디오 설정 복원 (중지)")
                Log.d(TAG, "복원할 스피커폰 상태: $previousSpeakerphoneOn")
                Log.d(TAG, "복원할 마이크 뮤트 상태: $previousMicMute")
                Log.d(TAG, "복원할 통화 볼륨: $previousVoiceCallVolume")
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

                previousVoiceCallVolume?.let { prev ->
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prev, 0)
                    Log.d(TAG, "통화 볼륨 복원: $prev")
                }

                // 복원 후 초기화
                previousSpeakerphoneOn = null
                previousMicMute = null
                previousVoiceCallVolume = null
                isAudioSettingsApplied = false
            } catch (e: Exception) {
                Log.e(TAG, "오디오 설정 복원 실패: ${e.message}", e)
            }
        }

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
            isProcessingCallEnd = false
            wasDisconnectedByUs = false
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
            isProcessingCallEnd = false
            wasDisconnectedByUs = false
            offhookTime = 0
            connectedTime = 0
            callStartTime = 0
            // 오디오 관련 상태는 여기서 초기화하지 않음 (restoreAudioSettings에서 처리)
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

        // 통화 중 오디오 음소거 적용 (OFFHOOK 시 매번)
        applyCallAudioMute(context)

        // Context의 약한 참조 저장 (메모리 누수 방지)
        val contextRef = WeakReference(context)

        // 설정된 시간 후 자동으로 전화 끊기
        disconnectRunnable = Runnable {
            contextRef.get()?.let { ctx ->
                Log.d(TAG, "${connectedDisconnectDelay/1000}초 경과, 전화 종료 시도: $currentPhoneNumber")
                wasDisconnectedByUs = true  // 우리가 끊었음을 표시
                disconnectCall(ctx)
            }
        }
        handler.postDelayed(disconnectRunnable!!, connectedDisconnectDelay)
    }

    /**
     * 전화 종료 처리
     */
    private fun handleCallEnded(context: Context) {
        // 중복 처리 방지
        if (isProcessingCallEnd) {
            Log.d(TAG, "이미 통화 종료 처리 중 - 중복 호출 무시")
            return
        }
        isProcessingCallEnd = true

        // 타이머 정리
        disconnectRunnable?.let {
            handler.removeCallbacks(it)
            disconnectRunnable = null
        }

        noAnswerRunnable?.let {
            handler.removeCallbacks(it)
            noAnswerRunnable = null
        }

        // 통화 종료 상태 분석을 위한 정보 저장
        val phoneNumberForLog = currentPhoneNumber
        val appMeasuredDuration = if (offhookTime > 0) System.currentTimeMillis() - offhookTime else 0L
        val wasOffhookReached = offhookTime > 0
        val disconnectedByUs = wasDisconnectedByUs  // 우리가 끊었는지 여부 저장

        Log.d(TAG, "========================================")
        Log.d(TAG, "통화 종료: $phoneNumberForLog")
        Log.d(TAG, "OFFHOOK 도달: $wasOffhookReached")
        Log.d(TAG, "앱 측정 통화 시간: ${appMeasuredDuration}ms")
        Log.d(TAG, "우리가 끊음: $disconnectedByUs")
        Log.d(TAG, "CallLog 분석 대기 중... (1초 후)")
        Log.d(TAG, "========================================")

        // 상태 초기화
        resetState()

        // DisconnectCause 초기화
        CallDisconnectListener.resetLastCause()

        // CallLog 분석 후 상태 결정 (1초 후 - CallLog에 기록이 반영되는 시간 필요)
        phoneNumberForLog?.let { number ->
            handler.postDelayed({
                analyzeAndDetermineCallStatus(context, number, appMeasuredDuration, wasOffhookReached, disconnectedByUs)
            }, 1000)
        } ?: run {
            // 전화번호가 없으면 바로 다음 전화 진행
            isProcessingCallEnd = false
            callEndedListener?.onCallEnded()
        }
    }

    /**
     * CallLog 분석 후 통화 상태 결정
     *
     * 판단 기준:
     * - wasOffhookReached: OFFHOOK 상태 도달 여부
     * - callLogDuration: CallLog에 기록된 통화 시간
     * - disconnectedByUs: 우리 타이머가 전화를 끊었는지 여부
     *
     * 로직:
     * 1. OFFHOOK 미도달 → rejected
     * 2. CallLog > 0 → ended (실제 통화)
     * 3. CallLog = 0:
     *    - 우리가 끊음 → no_answer (상대방 안 받음)
     *    - 통신사가 끊음 → rejected (연결 실패)
     */
    private fun analyzeAndDetermineCallStatus(
        context: Context,
        phoneNumber: String,
        appMeasuredDuration: Long,
        wasOffhookReached: Boolean,
        disconnectedByUs: Boolean
    ) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "CallLog 기반 통화 상태 분석: $phoneNumber")
            Log.d(TAG, "========================================")

            val callLogs = CallLogAnalyzer.getRecentCallLog(context, phoneNumber, 1)

            val status: String
            val callLogDuration: Long

            if (callLogs.isNotEmpty()) {
                val entry = callLogs[0]
                callLogDuration = entry.duration

                CallLogAnalyzer.logCallEntry(entry)

                Log.d(TAG, "----------------------------------------")
                Log.d(TAG, "비교 분석:")
                Log.d(TAG, "  CallLog duration: ${callLogDuration}초")
                Log.d(TAG, "  앱 측정 duration: ${appMeasuredDuration}ms (${appMeasuredDuration / 1000}초)")
                Log.d(TAG, "  OFFHOOK 도달: $wasOffhookReached")
                Log.d(TAG, "  우리가 끊음: $disconnectedByUs")
                Log.d(TAG, "----------------------------------------")

                // 판단 로직
                status = when {
                    // OFFHOOK에 도달하지 못한 경우 = 전화 연결 안 됨
                    !wasOffhookReached -> {
                        Log.d(TAG, "✗ 전화 연결 안 됨: $phoneNumber (OFFHOOK 도달 실패)")
                        "rejected"
                    }
                    // CallLog duration > 0 = 실제 통화 발생
                    callLogDuration > 0 -> {
                        Log.d(TAG, "✓ 정상 통화 종료: $phoneNumber")
                        Log.d(TAG, "  → CallLog=${callLogDuration}초")
                        "ended"
                    }
                    // CallLog duration = 0 + 우리가 끊음 = 상대방 안 받음
                    callLogDuration == 0L && disconnectedByUs -> {
                        Log.d(TAG, "✗ 상대방 안 받음 (타이머 종료): $phoneNumber")
                        Log.d(TAG, "  → CallLog=0초, 우리가 끊음")
                        "no_answer"
                    }
                    // CallLog duration = 0 + 통신사가 끊음 = 연결 실패
                    callLogDuration == 0L && !disconnectedByUs -> {
                        Log.d(TAG, "✗ 연결 실패 (통신사 종료): $phoneNumber")
                        Log.d(TAG, "  → CallLog=0초, 앱=${appMeasuredDuration}ms, 통신사가 끊음")
                        "rejected"
                    }
                    // 기타
                    else -> {
                        Log.d(TAG, "? 판단 불가: $phoneNumber")
                        "rejected"
                    }
                }
            } else {
                Log.w(TAG, "CallLog에서 해당 번호의 통화 기록을 찾을 수 없음: $phoneNumber")

                // CallLog가 없으면 disconnectedByUs로 판단
                status = when {
                    !wasOffhookReached -> "rejected"
                    disconnectedByUs -> "no_answer"
                    else -> "rejected"
                }

                Log.d(TAG, "판단 결과: $status")
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "최종 판단: $status")
            Log.d(TAG, "========================================")

            // 서버에 통화 상태 기록
            ApiClient.recordCall(phoneNumber, status)

        } catch (e: Exception) {
            Log.e(TAG, "CallLog 분석 실패: ${e.message}", e)

            // 예외 발생 시 기본값으로 처리
            val fallbackStatus = if (wasOffhookReached) "ended" else "rejected"
            ApiClient.recordCall(phoneNumber, fallbackStatus)
        }

        // 중복 처리 방지 플래그 초기화
        isProcessingCallEnd = false

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
        wasDisconnectedByUs = false
        // 오디오 관련 상태는 여기서 초기화하지 않음 (시작/중지 시에만 처리)
    }
}
