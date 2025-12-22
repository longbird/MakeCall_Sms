package com.example.autocall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.*

/**
 * 자동 전화 걸기 Foreground Service
 * 백그라운드에서도 안정적으로 동작하도록 함
 */
class AutoCallService : Service() {

    companion object {
        private const val TAG = "AutoCallService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "auto_call_channel"
        const val CHANNEL_NAME = "자동 전화 서비스"

        // Service 액션
        const val ACTION_START = "com.example.autocall.ACTION_START"
        const val ACTION_STOP = "com.example.autocall.ACTION_STOP"

        // Intent Extra 키
        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_CALL_TIMEOUT = "call_timeout"
        const val EXTRA_CALL_DURATION = "call_duration"
        const val EXTRA_PHONE_NUMBER_LIMIT = "phone_number_limit"
        const val EXTRA_END_TIME = "end_time"
        const val EXTRA_MUTE_SPEAKER = "mute_speaker"
        const val EXTRA_MUTE_MIC = "mute_mic"

        // 서비스 상태
        var isRunning = false
            private set
    }

    // Binder for Activity communication
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AutoCallService = this@AutoCallService
    }

    // 전화번호 큐 및 상태
    private val phoneNumberQueue: Queue<String> = LinkedList()
    private var isAutoCalling = false
    private var totalPhoneNumbersProcessed = 0
    private var currentBatchSize = 0
    private var currentPhoneNumber: String? = null

    // 설정값
    private var serverAddress: String = ""
    private var callTimeout: Int = 30
    private var callDuration: Int = 20
    private var phoneNumberLimit: Int = 10
    private var endTimeStr: String = "18:00"

    // Handler 및 Runnable
    private val handler = Handler(Looper.getMainLooper())
    private var endTimeCheckRunnable: Runnable? = null

    // WakeLock (CPU 깨어있게 유지)
    private var wakeLock: PowerManager.WakeLock? = null

    // 상태 변경 리스너
    interface ServiceCallback {
        fun onStatusChanged(status: String, current: Int, total: Int, phoneNumber: String?)
        fun onServiceStopped()
    }

    private var callback: ServiceCallback? = null

    fun setCallback(callback: ServiceCallback?) {
        this.callback = callback
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AutoCallService onCreate()")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() - action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // 설정값 읽기
                serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: ""
                callTimeout = intent.getIntExtra(EXTRA_CALL_TIMEOUT, 30)
                callDuration = intent.getIntExtra(EXTRA_CALL_DURATION, 20)
                phoneNumberLimit = intent.getIntExtra(EXTRA_PHONE_NUMBER_LIMIT, 10)
                endTimeStr = intent.getStringExtra(EXTRA_END_TIME) ?: "18:00"
                MainActivity.isSpeakerMuted = intent.getBooleanExtra(EXTRA_MUTE_SPEAKER, true)
                MainActivity.isMicMuted = intent.getBooleanExtra(EXTRA_MUTE_MIC, true)

                startForegroundService()
            }
            ACTION_STOP -> {
                stopAutoCallProcess(manual = true)
            }
        }

        return START_STICKY
    }

    /**
     * Foreground Service 시작
     */
    private fun startForegroundService() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Foreground Service 시작")
        Log.d(TAG, "서버: $serverAddress")
        Log.d(TAG, "전화 시도: ${callTimeout}초, 대기: ${callDuration}초")
        Log.d(TAG, "전화번호 개수: $phoneNumberLimit")
        Log.d(TAG, "종료 시간: $endTimeStr")
        Log.d(TAG, "========================================")

        // Foreground 알림 표시
        val notification = createNotification("시작 중...", 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires foregroundServiceType
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        isAutoCalling = true

        // WakeLock 획득
        acquireWakeLock()

        // API 클라이언트 설정
        ApiClient.setBaseUrl(serverAddress)

        // 오디오 설정 적용
        PhoneStateReceiver.applyAudioSettings(this)

        // PhoneStateReceiver에 콜백 설정
        PhoneStateReceiver.setOnCallEndedListener(object : PhoneStateReceiver.Companion.OnCallEndedListener {
            override fun onCallEnded() {
                handler.post {
                    processNextPhoneCall()
                }
            }
        })

        // 종료 시간 체크 시작
        startEndTimeCheck()

        // 전화번호 가져오기 시작
        fetchMorePhoneNumbers()
    }

    /**
     * WakeLock 획득 (CPU 깨어있게 유지)
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AutoCallService::WakeLock"
            )
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 최대 10시간
            Log.d(TAG, "WakeLock 획득됨")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock 획득 실패: ${e.message}", e)
        }
    }

    /**
     * WakeLock 해제
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock 해제됨")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock 해제 실패: ${e.message}", e)
        }
    }

    /**
     * 알림 채널 생성 (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "자동 전화 걸기 서비스 상태 표시"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "알림 채널 생성됨: $CHANNEL_ID")
        }
    }

    /**
     * 알림 생성
     */
    private fun createNotification(status: String, current: Int, total: Int): Notification {
        // 알림 클릭 시 MainActivity 열기
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 중지 버튼
        val stopIntent = Intent(this, AutoCallService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (total > 0) {
            "$status ($current/$total)"
        } else {
            status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("자동 전화 서비스")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", stopPendingIntent)
            .setProgress(total, current, total == 0)
            .build()
    }

    /**
     * 알림 업데이트
     */
    private fun updateNotification(status: String, current: Int, total: Int) {
        val notification = createNotification(status, current, total)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 종료 시간 체크 시작
     */
    private fun startEndTimeCheck() {
        Log.d(TAG, "종료 시간 체크 시작: $endTimeStr")

        endTimeCheckRunnable = object : Runnable {
            override fun run() {
                if (!isAutoCalling) return

                if (isEndTimeReached(endTimeStr)) {
                    Log.d(TAG, "종료 시간 도달: $endTimeStr")
                    stopAutoCallProcess(manual = false)
                } else {
                    handler.postDelayed(this, 60000L)
                }
            }
        }
        handler.postDelayed(endTimeCheckRunnable!!, 60000L)
    }

    /**
     * 종료 시간 도달 여부 확인
     */
    private fun isEndTimeReached(endTimeStr: String): Boolean {
        return try {
            val parts = endTimeStr.split(":")
            if (parts.size != 2) return false

            val endHour = parts[0].toIntOrNull() ?: return false
            val endMinute = parts[1].toIntOrNull() ?: return false

            val calendar = java.util.Calendar.getInstance()
            val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(java.util.Calendar.MINUTE)

            val currentTimeInMinutes = currentHour * 60 + currentMinute
            val endTimeInMinutes = endHour * 60 + endMinute

            currentTimeInMinutes >= endTimeInMinutes
        } catch (e: Exception) {
            Log.e(TAG, "종료 시간 체크 오류: ${e.message}", e)
            false
        }
    }

    /**
     * 추가 전화번호 가져오기
     */
    private fun fetchMorePhoneNumbers() {
        if (!isAutoCalling) return

        updateNotification("전화번호 가져오는 중...", totalPhoneNumbersProcessed, currentBatchSize)
        notifyStatusChanged("전화번호 가져오는 중...", totalPhoneNumbersProcessed, currentBatchSize, null)

        ApiClient.getPhoneNumbers(phoneNumberLimit, object : ApiClient.PhoneNumbersCallback {
            override fun onSuccess(phoneNumbers: List<String>) {
                handler.post {
                    if (phoneNumbers.isNotEmpty()) {
                        phoneNumberQueue.addAll(phoneNumbers)
                        currentBatchSize += phoneNumbers.size
                        Log.d(TAG, "${phoneNumbers.size}개의 전화번호를 가져왔습니다")
                        processNextPhoneCall()
                    } else {
                        Log.d(TAG, "더 이상 전화번호가 없습니다. 서비스 종료")
                        stopAutoCallProcess(manual = false)
                    }
                }
            }

            override fun onFailure(error: String) {
                handler.post {
                    Log.e(TAG, "전화번호 가져오기 실패: $error")
                    stopAutoCallProcess(manual = false)
                }
            }
        })
    }

    /**
     * 다음 전화번호로 전화 걸기
     */
    private fun processNextPhoneCall() {
        if (!isAutoCalling) return

        if (phoneNumberQueue.isEmpty()) {
            fetchMorePhoneNumbers()
            return
        }

        val nextPhoneNumber = phoneNumberQueue.poll()
        if (!nextPhoneNumber.isNullOrEmpty()) {
            totalPhoneNumbersProcessed++
            currentPhoneNumber = nextPhoneNumber

            updateNotification("전화 걸기: $nextPhoneNumber", totalPhoneNumbersProcessed, currentBatchSize)
            notifyStatusChanged("전화 걸기", totalPhoneNumbersProcessed, currentBatchSize, nextPhoneNumber)

            makePhoneCall(nextPhoneNumber)
        } else {
            processNextPhoneCall()
        }
    }

    /**
     * 전화 걸기
     */
    private fun makePhoneCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE 권한 없음")
            processNextPhoneCall()
            return
        }

        Log.d(TAG, "전화 걸기: $phoneNumber (${totalPhoneNumbersProcessed}/${currentBatchSize})")

        // 전화번호 저장
        CallManager.setLastCalledNumber(phoneNumber)

        // PhoneStateReceiver 설정
        PhoneStateReceiver.setCurrentPhoneNumber(phoneNumber)
        PhoneStateReceiver.setCallTimeouts(callTimeout, callDuration)

        // 전화 걸기 시작 상태 기록
        ApiClient.recordCall(phoneNumber, "started")

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
        } catch (e: Exception) {
            Log.e(TAG, "전화 걸기 실패: ${e.message}", e)
            ApiClient.recordCall(phoneNumber, "failed")
            handler.postDelayed({
                processNextPhoneCall()
            }, 1000)
        }
    }

    /**
     * 자동 전화 프로세스 종료
     */
    private fun stopAutoCallProcess(manual: Boolean) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "자동 전화 프로세스 종료 (manual=$manual)")
        Log.d(TAG, "========================================")

        isAutoCalling = false

        // 종료 시간 체크 중단
        endTimeCheckRunnable?.let {
            handler.removeCallbacks(it)
            endTimeCheckRunnable = null
        }

        // 남은 전화번호 리셋
        val remainingNumbers = phoneNumberQueue.toList()
        phoneNumberQueue.clear()

        if (remainingNumbers.isNotEmpty()) {
            Log.d(TAG, "남은 전화번호 ${remainingNumbers.size}개 리셋 요청")
            for (phoneNumber in remainingNumbers) {
                ApiClient.resetNumber(phoneNumber)
            }
        }

        // 오디오 설정 복원
        PhoneStateReceiver.restoreAudioSettings(this)

        // WakeLock 해제
        releaseWakeLock()

        // 콜백 호출
        notifyStatusChanged(
            if (manual) "중지됨" else "종료됨",
            totalPhoneNumbersProcessed,
            currentBatchSize,
            null
        )
        callback?.onServiceStopped()

        // 서비스 종료
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 상태 변경 알림
     */
    private fun notifyStatusChanged(status: String, current: Int, total: Int, phoneNumber: String?) {
        callback?.onStatusChanged(status, current, total, phoneNumber)
    }

    /**
     * 현재 진행 상황 가져오기
     */
    fun getProgress(): Triple<Int, Int, String?> {
        return Triple(totalPhoneNumbersProcessed, currentBatchSize, currentPhoneNumber)
    }

    /**
     * 서비스 중지 요청
     */
    fun requestStop() {
        stopAutoCallProcess(manual = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AutoCallService onDestroy()")

        // 정리 작업
        isAutoCalling = false
        isRunning = false

        endTimeCheckRunnable?.let {
            handler.removeCallbacks(it)
        }

        releaseWakeLock()
        PhoneStateReceiver.cleanup()
    }
}
