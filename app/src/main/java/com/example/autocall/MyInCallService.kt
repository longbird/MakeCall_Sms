package com.example.autocall

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

/**
 * InCallService를 사용하여 정확한 통화 상태를 감지합니다.
 *
 * 이 서비스를 사용하려면 앱이 "기본 전화 앱"으로 설정되어야 합니다.
 *
 * 통화 상태:
 * - STATE_DIALING (1): 발신 중 (상대방 전화벨 울리기 전)
 * - STATE_RINGING (2): 수신 중 (벨 울림)
 * - STATE_HOLDING (3): 통화 보류
 * - STATE_ACTIVE (4): 통화 연결됨 (상대방이 받음) ★
 * - STATE_DISCONNECTED (7): 통화 종료
 * - STATE_CONNECTING (9): 연결 중
 * - STATE_DISCONNECTING (10): 연결 해제 중
 * - STATE_PULLING_CALL (11): 다른 기기에서 통화 가져오는 중
 */
class MyInCallService : InCallService() {

    companion object {
        private const val TAG = "MyInCallService"

        // 현재 통화 상태를 저장하는 싱글톤
        @Volatile
        var currentCallState: Int = Call.STATE_NEW
            private set

        @Volatile
        var isCallActive: Boolean = false
            private set

        @Volatile
        var activeTime: Long = 0
            private set

        // 통화 상태 변경 리스너
        interface CallStateListener {
            fun onCallStateChanged(state: Int, stateName: String)
            fun onCallActive() // 상대방이 전화를 받았을 때
            fun onCallDisconnected(disconnectCause: Int)
        }

        private var callStateListener: CallStateListener? = null

        fun setCallStateListener(listener: CallStateListener?) {
            callStateListener = listener
        }

        /**
         * 통화 상태 이름 반환
         */
        fun getStateName(state: Int): String {
            return when (state) {
                Call.STATE_NEW -> "NEW"
                Call.STATE_DIALING -> "DIALING"
                Call.STATE_RINGING -> "RINGING"
                Call.STATE_HOLDING -> "HOLDING"
                Call.STATE_ACTIVE -> "ACTIVE"
                Call.STATE_DISCONNECTED -> "DISCONNECTED"
                Call.STATE_CONNECTING -> "CONNECTING"
                Call.STATE_DISCONNECTING -> "DISCONNECTING"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
                Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
                Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
                else -> "UNKNOWN($state)"
            }
        }

        /**
         * 상태 초기화
         */
        fun reset() {
            currentCallState = Call.STATE_NEW
            isCallActive = false
            activeTime = 0
        }
    }

    private var currentCall: Call? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            handleCallStateChanged(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            Log.d(TAG, "통화 상세 정보 변경: ${details.handle}")
        }

        override fun onCallDestroyed(call: Call) {
            super.onCallDestroyed(call)
            Log.d(TAG, "통화 객체 파괴됨")
            if (currentCall == call) {
                currentCall = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========================================")
        Log.d(TAG, "MyInCallService 생성됨")
        Log.d(TAG, "========================================")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MyInCallService 파괴됨")
        reset()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        currentCall = call
        val phoneNumber = getPhoneNumber(call)
        val state = call.state
        val stateName = getStateName(state)

        Log.d(TAG, "========================================")
        Log.d(TAG, "★ 통화 추가됨 ★")
        Log.d(TAG, "전화번호: $phoneNumber")
        Log.d(TAG, "초기 상태: $stateName ($state)")
        Log.d(TAG, "========================================")

        // 콜백 등록
        call.registerCallback(callCallback)

        // 초기 상태 처리
        handleCallStateChanged(call, state)

        // 통화 화면 Activity 시작
        startInCallActivity()
    }

    /**
     * InCallActivity 시작
     */
    private fun startInCallActivity() {
        try {
            val intent = Intent(this, InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
            Log.d(TAG, "InCallActivity 시작됨")
        } catch (e: Exception) {
            Log.e(TAG, "InCallActivity 시작 실패: ${e.message}", e)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)

        val phoneNumber = getPhoneNumber(call)

        Log.d(TAG, "========================================")
        Log.d(TAG, "★ 통화 제거됨 ★")
        Log.d(TAG, "전화번호: $phoneNumber")
        Log.d(TAG, "========================================")

        // 콜백 해제
        call.unregisterCallback(callCallback)

        if (currentCall == call) {
            currentCall = null
        }

        // 통화 종료 처리
        val disconnectCause = call.details?.disconnectCause?.code ?: -1
        callStateListener?.onCallDisconnected(disconnectCause)

        // 통화 화면 Activity 종료
        InCallActivity.finishIfExists()
    }

    /**
     * 통화 상태 변경 처리
     */
    private fun handleCallStateChanged(call: Call, state: Int) {
        val previousState = currentCallState
        currentCallState = state

        val phoneNumber = getPhoneNumber(call)
        val stateName = getStateName(state)
        val previousStateName = getStateName(previousState)

        Log.d(TAG, "========================================")
        Log.d(TAG, "통화 상태 변경: $previousStateName → $stateName")
        Log.d(TAG, "전화번호: $phoneNumber")
        Log.d(TAG, "========================================")

        // InCallActivity UI 업데이트
        InCallActivity.updateUI()

        // 리스너에 상태 변경 알림
        callStateListener?.onCallStateChanged(state, stateName)

        when (state) {
            Call.STATE_DIALING -> {
                Log.d(TAG, "발신 중... (상대방 전화벨 울리기 전)")
            }

            Call.STATE_ACTIVE -> {
                // ★★★ 상대방이 전화를 받았을 때 ★★★
                if (!isCallActive) {
                    isCallActive = true
                    activeTime = System.currentTimeMillis()

                    Log.d(TAG, "╔════════════════════════════════════════╗")
                    Log.d(TAG, "║  ★★★ 상대방이 전화를 받았습니다! ★★★  ║")
                    Log.d(TAG, "║  전화번호: $phoneNumber")
                    Log.d(TAG, "║  연결 시간: $activeTime")
                    Log.d(TAG, "╚════════════════════════════════════════╝")

                    // 리스너에 통화 연결 알림
                    callStateListener?.onCallActive()
                }
            }

            Call.STATE_DISCONNECTED -> {
                val wasActive = isCallActive
                val disconnectCause = call.details?.disconnectCause

                Log.d(TAG, "통화 종료")
                Log.d(TAG, "  이전 활성 상태: $wasActive")
                Log.d(TAG, "  종료 원인: ${disconnectCause?.code} - ${disconnectCause?.reason}")
                Log.d(TAG, "  종료 설명: ${disconnectCause?.description}")

                // 상태 초기화
                isCallActive = false
            }

            Call.STATE_RINGING -> {
                Log.d(TAG, "수신 전화 울림 중...")
            }

            Call.STATE_CONNECTING -> {
                Log.d(TAG, "연결 중...")
            }

            Call.STATE_HOLDING -> {
                Log.d(TAG, "통화 보류 중...")
            }
        }
    }

    /**
     * Call 객체에서 전화번호 추출
     */
    private fun getPhoneNumber(call: Call): String {
        return try {
            call.details?.handle?.schemeSpecificPart ?: "알 수 없음"
        } catch (e: Exception) {
            Log.e(TAG, "전화번호 추출 실패: ${e.message}")
            "알 수 없음"
        }
    }

    /**
     * 현재 통화 끊기
     */
    fun disconnectCurrentCall() {
        currentCall?.let { call ->
            Log.d(TAG, "현재 통화 끊기 요청")
            call.disconnect()
        } ?: run {
            Log.w(TAG, "끊을 통화가 없음")
        }
    }

    /**
     * 현재 통화 보류
     */
    fun holdCurrentCall() {
        currentCall?.let { call ->
            if (call.state == Call.STATE_ACTIVE) {
                Log.d(TAG, "현재 통화 보류 요청")
                call.hold()
            }
        }
    }

    /**
     * 통화 보류 해제
     */
    fun unholdCurrentCall() {
        currentCall?.let { call ->
            if (call.state == Call.STATE_HOLDING) {
                Log.d(TAG, "통화 보류 해제 요청")
                call.unhold()
            }
        }
    }

    /**
     * 수신 전화 받기
     */
    fun answerCall() {
        currentCall?.let { call ->
            if (call.state == Call.STATE_RINGING) {
                Log.d(TAG, "수신 전화 받기")
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }
    }

    /**
     * 수신 전화 거부
     */
    fun rejectCall() {
        currentCall?.let { call ->
            if (call.state == Call.STATE_RINGING) {
                Log.d(TAG, "수신 전화 거부")
                call.reject(false, null)
            }
        }
    }
}
