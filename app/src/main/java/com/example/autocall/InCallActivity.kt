package com.example.autocall

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 통화 중 화면을 표시하는 Activity.
 * 기본 전화 앱으로 설정되면 이 화면이 통화 중에 표시됩니다.
 */
class InCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InCallActivity"

        // 현재 Activity 인스턴스 (싱글톤처럼 사용)
        @Volatile
        var instance: InCallActivity? = null
            private set

        /**
         * Activity가 표시되어 있으면 UI 업데이트
         */
        fun updateUI() {
            instance?.runOnUiThread {
                instance?.updateCallInfo()
            }
        }

        /**
         * Activity 종료
         */
        fun finishIfExists() {
            instance?.runOnUiThread {
                instance?.finish()
            }
        }
    }

    private lateinit var tvPhoneNumber: TextView
    private lateinit var tvCallState: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var btnEndCall: Button
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button

    private val handler = Handler(Looper.getMainLooper())
    private var dialStartTime: Long = 0  // 발신 시작 시간
    private var connectedTime: Long = 0  // 통화 연결 시간
    private var durationUpdateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 잠금화면 위에 표시
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_in_call)

        instance = this

        // 발신 시작 시간 기록
        dialStartTime = System.currentTimeMillis()

        initViews()
        setupListeners()
        updateCallInfo()
        startDurationUpdate()

        Log.d(TAG, "InCallActivity 생성됨")
    }

    private fun initViews() {
        tvPhoneNumber = findViewById(R.id.tvPhoneNumber)
        tvCallState = findViewById(R.id.tvCallState)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
    }

    private fun setupListeners() {
        btnEndCall.setOnClickListener {
            Log.d(TAG, "통화 종료 버튼 클릭")
            // MyInCallService를 통해 통화 종료는 어려우므로 TelecomManager 사용
            // 또는 그냥 Activity만 종료 (백그라운드에서 계속 통화)
            finish()
        }

        btnMute.setOnClickListener {
            Log.d(TAG, "음소거 버튼 클릭")
            // 음소거 토글
        }

        btnSpeaker.setOnClickListener {
            Log.d(TAG, "스피커 버튼 클릭")
            // 스피커 토글
        }
    }

    private fun updateCallInfo() {
        val state = MyInCallService.currentCallState
        val stateName = MyInCallService.getStateName(state)
        val isActive = MyInCallService.isCallActive

        // 전화번호 표시 (CallManager에서 가져오기)
        val phoneNumber = CallManager.getLastCalledNumber() ?: "알 수 없음"
        tvPhoneNumber.text = phoneNumber

        // 통화 상태 표시
        val stateText = when (state) {
            Call.STATE_DIALING -> "발신 중..."
            Call.STATE_RINGING -> "수신 중..."
            Call.STATE_ACTIVE -> "통화 중"
            Call.STATE_HOLDING -> "보류 중"
            Call.STATE_DISCONNECTED -> "통화 종료"
            Call.STATE_CONNECTING -> "연결 중..."
            else -> stateName
        }
        tvCallState.text = stateText

        // 통화 연결되면 연결 시간 기록
        if (isActive && connectedTime == 0L) {
            connectedTime = MyInCallService.activeTime
            if (connectedTime == 0L) {
                connectedTime = System.currentTimeMillis()
            }
        }

        Log.d(TAG, "UI 업데이트: 번호=$phoneNumber, 상태=$stateText")
    }

    private fun startDurationUpdate() {
        // 바로 시작 (1초 기다리지 않음)
        tvCallDuration.visibility = View.VISIBLE
        tvCallDuration.text = "발신 00:00"

        durationUpdateRunnable = object : Runnable {
            override fun run() {
                // MyInCallService에서 연결 상태 확인
                val isActive = MyInCallService.isCallActive

                // 연결되었는데 connectedTime이 0이면 지금 시간으로 설정
                if (isActive && connectedTime == 0L) {
                    connectedTime = MyInCallService.activeTime
                    if (connectedTime == 0L) {
                        connectedTime = System.currentTimeMillis()
                    }
                    Log.d(TAG, "통화 연결 감지! connectedTime 설정: $connectedTime")
                }

                if (connectedTime > 0) {
                    // 통화 연결됨 - 연결 후 경과 시간 표시
                    val duration = (System.currentTimeMillis() - connectedTime) / 1000
                    val minutes = duration / 60
                    val seconds = duration % 60
                    tvCallDuration.text = String.format("통화 %02d:%02d", minutes, seconds)
                    tvCallDuration.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // 녹색

                    // 통화 상태 텍스트도 업데이트
                    tvCallState.text = "통화 중"
                } else {
                    // 발신 중 - 발신 후 경과 시간 표시
                    val duration = (System.currentTimeMillis() - dialStartTime) / 1000
                    val minutes = duration / 60
                    val seconds = duration % 60
                    tvCallDuration.text = String.format("발신 %02d:%02d", minutes, seconds)
                    tvCallDuration.setTextColor(android.graphics.Color.parseColor("#FF9800")) // 주황색
                }

                handler.postDelayed(this, 500) // 0.5초마다 체크 (더 빠른 반응)
            }
        }
        handler.post(durationUpdateRunnable!!)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        updateCallInfo()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        durationUpdateRunnable?.let { handler.removeCallbacks(it) }
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "InCallActivity 파괴됨")
    }

    override fun onBackPressed() {
        // 뒤로 가기 시 통화를 종료하지 않고 백그라운드로
        moveTaskToBack(true)
    }
}
