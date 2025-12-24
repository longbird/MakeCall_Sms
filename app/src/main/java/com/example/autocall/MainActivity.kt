package com.example.autocall

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_CODE_SET_DEFAULT_DIALER = 101
        const val ACTION_SMS_RECEIVED = "com.example.autocall.SMS_RECEIVED"

        // SharedPreferences 관련 상수
        private const val PREF_NAME = "AutoCallSettings"
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val DEFAULT_SERVER_ADDRESS = "192.168.0.210:8080"

        // 오디오 설정 상태 (PhoneStateReceiver에서 접근)
        var isSpeakerMuted = true
        var isMicMuted = true
    }

    private lateinit var etServerAddress: EditText
    private lateinit var etCallTimeout: EditText
    private lateinit var etCallDuration: EditText
    private lateinit var etPhoneNumberLimit: EditText
    private lateinit var etEndTime: EditText
    private lateinit var btnStart: Button
    private lateinit var btnCheckPermissions: Button
    private lateinit var btnTestSms: Button
    private lateinit var cbMuteSpeaker: CheckBox
    private lateinit var cbMuteMic: CheckBox
    private lateinit var btnSetDefaultDialer: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SmsHistoryAdapter
    private lateinit var dbHelper: DatabaseHelper
    private val handler = Handler(Looper.getMainLooper())
    private var endTimeCheckRunnable: Runnable? = null

    // 진행 상황 표시 뷰
    private lateinit var statusCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvCurrentNumber: TextView
    private lateinit var progressBar: ProgressBar

    // SMS ContentObserver (BroadcastReceiver 대체)
    private var smsContentObserver: SmsContentObserver? = null
    private var isObserverRegistered = false

    // AutoCallService 바인딩
    private var autoCallService: AutoCallService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AutoCallService.LocalBinder
            autoCallService = binder.getService()
            isServiceBound = true
            Log.d(TAG, "AutoCallService 바인딩됨")

            // Service 콜백 설정
            autoCallService?.setCallback(object : AutoCallService.ServiceCallback {
                override fun onStatusChanged(status: String, current: Int, total: Int, phoneNumber: String?) {
                    runOnUiThread {
                        updateStatus(status, current, total, phoneNumber)
                    }
                }

                override fun onServiceStopped() {
                    runOnUiThread {
                        isAutoCalling = false
                        updateStartStopButton()
                        Toast.makeText(this@MainActivity, "자동 전화 서비스가 종료되었습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            // Service가 실행 중이면 UI 업데이트
            if (AutoCallService.isRunning) {
                isAutoCalling = true
                updateStartStopButton()
                val (current, total, phoneNumber) = autoCallService!!.getProgress()
                updateStatus("진행 중", current, total, phoneNumber)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            autoCallService = null
            isServiceBound = false
            Log.d(TAG, "AutoCallService 바인딩 해제됨")
        }
    }

    // SMS 수신 BroadcastReceiver
    private val smsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "BroadcastReceiver.onReceive() 호출됨 - Action: ${intent.action}")
            if (ACTION_SMS_RECEIVED == intent.action) {
                Log.d(TAG, "========================================")
                Log.d(TAG, "SMS 수신 브로드캐스트 받음 - UI 갱신 시작")
                Log.d(TAG, "========================================")
                loadSmsHistory()
            }
        }
    }

    // 전화번호 리스트 관리
    private val phoneNumberQueue: Queue<String> = LinkedList()
    private var isAutoCalling = false
    private var hasAutoStarted = false // 자동 시작 플래그
    private var totalPhoneNumbersProcessed = 0 // 현재까지 처리한 전화번호 개수
    private var currentBatchSize = 0 // 현재 배치의 전화번호 개수

    // 기본 전화 앱 설정을 위한 ActivityResultLauncher (Android 10+)
    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isDefaultDialer()) {
            Log.d(TAG, "기본 전화 앱으로 설정 성공!")
            Toast.makeText(this, "기본 전화 앱으로 설정되었습니다.\n이제 정확한 통화 상태 감지가 가능합니다.", Toast.LENGTH_LONG).show()
            updateDefaultDialerButton()
        } else {
            Log.w(TAG, "기본 전화 앱 설정 취소됨")
            Toast.makeText(this, "기본 전화 앱 설정이 취소되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 데이터베이스 헬퍼 초기화
        dbHelper = DatabaseHelper(this)

        // UI 컴포넌트 초기화
        etServerAddress = findViewById(R.id.etServerAddress)
        etCallTimeout = findViewById(R.id.etCallTimeout)
        etCallDuration = findViewById(R.id.etCallDuration)
        etPhoneNumberLimit = findViewById(R.id.etPhoneNumberLimit)
        etEndTime = findViewById(R.id.etEndTime)
        // 저장된 서버 주소 로드 (없으면 기본값 사용)
        val savedServerAddress = loadServerAddress()
        etServerAddress.setText(savedServerAddress)
        Log.d(TAG, "서버 주소 로드: $savedServerAddress")
        // 기본 타이머, 전화번호 개수, 종료 시간 설정 (이미 XML에서 설정되어 있음)
        btnStart = findViewById(R.id.btnStart)
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions)
        btnTestSms = findViewById(R.id.btnTestSms)
        cbMuteSpeaker = findViewById(R.id.cbMuteSpeaker)
        cbMuteMic = findViewById(R.id.cbMuteMic)
        btnSetDefaultDialer = findViewById(R.id.btnSetDefaultDialer)
        recyclerView = findViewById(R.id.recyclerView)

        // 체크박스 리스너 설정 - 상태 변경 시 companion object 변수 업데이트
        cbMuteSpeaker.setOnCheckedChangeListener { _, isChecked ->
            isSpeakerMuted = isChecked
            Log.d(TAG, "스피커 끄기 설정: $isChecked")
        }
        cbMuteMic.setOnCheckedChangeListener { _, isChecked ->
            isMicMuted = isChecked
            Log.d(TAG, "마이크 끄기 설정: $isChecked")
        }

        // 진행 상황 표시 뷰 초기화
        statusCard = findViewById(R.id.statusCard)
        tvStatus = findViewById(R.id.tvStatus)
        tvProgress = findViewById(R.id.tvProgress)
        tvCurrentNumber = findViewById(R.id.tvCurrentNumber)
        progressBar = findViewById(R.id.progressBar)

        // RecyclerView 설정
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SmsHistoryAdapter(ArrayList())
        recyclerView.adapter = adapter

        // 권한 확인 및 요청
        checkAndRequestPermissions()

        // SMS ContentObserver 등록
        registerSmsContentObserver()

        // DisconnectCause 리스너 등록
        CallDisconnectListener.register(this)

        // SMS 수신 BroadcastReceiver 등록
        val filter = IntentFilter(ACTION_SMS_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록됨 (RECEIVER_NOT_EXPORTED)")
        } else {
            registerReceiver(smsUpdateReceiver, filter)
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록됨")
        }
        Log.d(TAG, "Action 필터: $ACTION_SMS_RECEIVED")

        // 시작/중지 버튼 클릭 이벤트
        btnStart.setOnClickListener {
            if (isAutoCalling) {
                // 중지 버튼으로 동작
                stopAutoCallProcessManually()
            } else {
                // 시작 버튼으로 동작
                startAutoCallProcess()
            }
        }

        // 권한 상태 확인 버튼
        btnCheckPermissions.setOnClickListener {
            checkPermissionStatus()
        }

        // SMS 테스트 버튼
        btnTestSms.setOnClickListener {
            testSmsReceiver()
        }

        // 기본 전화 앱 설정 버튼
        btnSetDefaultDialer.setOnClickListener {
            requestDefaultDialer()
        }

        // 기본 전화 앱 상태 확인 및 버튼 업데이트
        updateDefaultDialerButton()

        // 앱 시작시 SMS 이력 로드
        loadSmsHistory()

        // Service가 이미 실행 중인지 확인하고 바인딩
        bindAutoCallService()

        // Service가 실행 중이면 UI 업데이트
        if (AutoCallService.isRunning) {
            isAutoCalling = true
            updateStartStopButton()
        }

        // 앱 실행 시 자동 시작 기능 중지 (사용자가 시작 버튼을 눌러야 시작됨)
        // autoStartIfReady() 제거됨

        // 테스트: 특정 번호의 CallLog 분석 (권한 확인 후 실행)
        handler.postDelayed({
            testCallLogAnalysis()
        }, 2000)
    }

    /**
     * CallLog 분석 테스트 (없는 번호: 01093647941)
     */
    private fun testCallLogAnalysis() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG 권한이 없어서 CallLog 분석을 수행할 수 없습니다")
            return
        }

        Log.d(TAG, "")
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║          CallLog 분석 테스트 시작                          ║")
        Log.d(TAG, "║          최근 발신 통화 10건 분석                          ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "")

        // 최근 발신 통화 기록 10건 조회
        val allLogs = CallLogAnalyzer.getAllRecentCallLogs(this, 10)

        if (allLogs.isEmpty()) {
            Log.w(TAG, "통화 기록이 없습니다.")
        } else {
            Log.d(TAG, "최근 통화 기록 ${allLogs.size}건:")
            Log.d(TAG, "")

            allLogs.forEach { entry ->
                logCallEntryDetails(entry)
                val result = CallLogAnalyzer.analyzeCallResult(entry)
                Log.d(TAG, "★ 분석 결과: $result")
                Log.d(TAG, "")
            }
        }

        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║          CallLog 분석 테스트 완료                          ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "")
    }

    /**
     * CallLog 엔트리 상세 정보 출력 (MainActivity 태그로)
     */
    private fun logCallEntryDetails(entry: CallLogAnalyzer.CallLogEntry) {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(entry.date))

        Log.d(TAG, "┌────────────────────────────────────────")
        Log.d(TAG, "│ ID: ${entry.id}")
        Log.d(TAG, "│ 번호: ${entry.number}")
        Log.d(TAG, "│ 유형: ${entry.typeName}")
        Log.d(TAG, "│ 시간: $dateStr")
        Log.d(TAG, "│ 통화시간: ${entry.duration}초")
        Log.d(TAG, "│ 새 통화: ${entry.isNew}")
        Log.d(TAG, "│ 저장된 이름: ${entry.cachedName ?: "없음"}")
        Log.d(TAG, "│ 번호 유형: ${entry.cachedNumberType}")
        Log.d(TAG, "│ 국가: ${entry.countryIso ?: "없음"}")
        Log.d(TAG, "│ 위치: ${entry.geocodedLocation ?: "없음"}")
        Log.d(TAG, "│ 기능(features): ${entry.features}")
        Log.d(TAG, "└────────────────────────────────────────")
    }

    /**
     * 권한이 모두 허용되어 있으면 자동으로 작업 시작
     */
    private fun autoStartIfReady() {
        if (hasAutoStarted) {
            return // 이미 자동 시작했으면 건너뛰기
        }

        // 필수 권한 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        ) {
            hasAutoStarted = true

            Log.d(TAG, "========================================")
            Log.d(TAG, "자동 시작 조건 충족 - 1초 후 서버 접속 시작")
            Log.d(TAG, "서버 주소: ${etServerAddress.text}")
            Log.d(TAG, "========================================")

            // 약간의 지연 후 자동 시작 (UI가 완전히 초기화되도록)
            handler.postDelayed({
                Toast.makeText(this, "자동으로 서버에 접속하여 작업을 시작합니다...", Toast.LENGTH_LONG).show()
                startAutoCallProcess()
            }, 1000)
        } else {
            Log.w(TAG, "자동 시작 실패: 권한 부족")
            Log.w(TAG, "CALL_PHONE: ${ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED}")
            Log.w(TAG, "READ_PHONE_STATE: ${ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED}")
        }
    }

    /**
     * 자동 전화 프로세스 시작 (Service로 위임)
     */
    private fun startAutoCallProcess() {
        Log.d(TAG, "startAutoCallProcess() 호출됨")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "전화 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }

        // 서버 주소 설정
        val serverAddress = etServerAddress.text.toString().trim()
        Log.d(TAG, "서버 주소: '$serverAddress'")

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "서버 주소를 입력하세요", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "서버 주소가 비어있음 - 자동 시작 실패")
            return
        }

        if (isAutoCalling || AutoCallService.isRunning) {
            Toast.makeText(this, "이미 자동 전화가 진행 중입니다", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "이미 자동 전화 진행 중")
            return
        }

        // 타이머 및 설정값 읽기
        val callTimeout = etCallTimeout.text.toString().toIntOrNull() ?: 30
        val callDuration = etCallDuration.text.toString().toIntOrNull() ?: 5
        val phoneNumberLimit = etPhoneNumberLimit.text.toString().toIntOrNull() ?: 10
        val endTime = etEndTime.text.toString().trim()

        Log.d(TAG, "========================================")
        Log.d(TAG, "Foreground Service로 자동 전화 시작!")
        Log.d(TAG, "서버: $serverAddress")
        Log.d(TAG, "전화 시도: ${callTimeout}초, 대기: ${callDuration}초")
        Log.d(TAG, "전화번호 개수: $phoneNumberLimit")
        Log.d(TAG, "종료 시간: $endTime")
        Log.d(TAG, "========================================")

        // 서버 주소 저장
        saveServerAddress(serverAddress)

        isAutoCalling = true
        updateStartStopButton()
        updateStatus("서비스 시작 중...")

        // Foreground Service 시작
        val serviceIntent = Intent(this, AutoCallService::class.java).apply {
            action = AutoCallService.ACTION_START
            putExtra(AutoCallService.EXTRA_SERVER_ADDRESS, serverAddress)
            putExtra(AutoCallService.EXTRA_CALL_TIMEOUT, callTimeout)
            putExtra(AutoCallService.EXTRA_CALL_DURATION, callDuration)
            putExtra(AutoCallService.EXTRA_PHONE_NUMBER_LIMIT, phoneNumberLimit)
            putExtra(AutoCallService.EXTRA_END_TIME, endTime)
            putExtra(AutoCallService.EXTRA_MUTE_SPEAKER, cbMuteSpeaker.isChecked)
            putExtra(AutoCallService.EXTRA_MUTE_MIC, cbMuteMic.isChecked)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Service 바인딩
        bindAutoCallService()

        Toast.makeText(this, "자동 전화 서비스가 시작되었습니다", Toast.LENGTH_SHORT).show()
    }

    /**
     * AutoCallService 바인딩
     */
    private fun bindAutoCallService() {
        if (!isServiceBound) {
            val intent = Intent(this, AutoCallService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * AutoCallService 언바인딩
     */
    private fun unbindAutoCallService() {
        if (isServiceBound) {
            autoCallService?.setCallback(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    /**
     * 종료 시간 체크 시작
     */
    private fun startEndTimeCheck() {
        val endTimeStr = etEndTime.text.toString().trim()
        Log.d(TAG, "종료 시간 설정: $endTimeStr")

        endTimeCheckRunnable = object : Runnable {
            override fun run() {
                if (!isAutoCalling) {
                    return // 이미 종료됨
                }

                if (isEndTimeReached(endTimeStr)) {
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "종료 시간 도달: $endTimeStr")
                    Log.d(TAG, "작동 종료 처리 시작...")
                    Log.d(TAG, "========================================")
                    stopAutoCallProcess()
                } else {
                    // 1분마다 체크
                    handler.postDelayed(this, 60000L)
                }
            }
        }
        handler.postDelayed(endTimeCheckRunnable!!, 60000L) // 1분 후 첫 체크
    }

    /**
     * 종료 시간 도달 여부 확인
     */
    private fun isEndTimeReached(endTimeStr: String): Boolean {
        return try {
            val parts = endTimeStr.split(":")
            if (parts.size != 2) {
                Log.w(TAG, "잘못된 종료 시간 형식: $endTimeStr")
                return false
            }

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
     * 자동 전화 프로세스 종료 (종료 시간 도달 시)
     */
    private fun stopAutoCallProcess() {
        Log.d(TAG, "자동 전화 프로세스 종료 시작 (종료 시간 도달)")

        // 진행 중인 전화 종료
        isAutoCalling = false

        // 오디오 설정 복원 (중지 시 1회)
        PhoneStateReceiver.restoreAudioSettings(this)

        // 종료 시간 체크 중단
        endTimeCheckRunnable?.let {
            handler.removeCallbacks(it)
            endTimeCheckRunnable = null
        }

        // 남아있는 전화번호들을 서버에 리셋 요청
        val remainingNumbers = phoneNumberQueue.toList()
        phoneNumberQueue.clear()

        if (remainingNumbers.isNotEmpty()) {
            Log.d(TAG, "남은 전화번호 ${remainingNumbers.size}개를 서버에 리셋 요청")
            for (phoneNumber in remainingNumbers) {
                ApiClient.resetNumber(phoneNumber)
            }
        }

        runOnUiThread {
            updateStartStopButton()
            updateStatus("종료됨", totalPhoneNumbersProcessed, currentBatchSize)
            Toast.makeText(
                this,
                "종료 시간 도달 - 작동을 종료합니다. 남은 번호: ${remainingNumbers.size}개",
                Toast.LENGTH_LONG
            ).show()
        }

        Log.d(TAG, "자동 전화 프로세스 종료 완료")
    }

    /**
     * 자동 전화 프로세스 수동 중지 (사용자가 중지 버튼을 눌렀을 때)
     * Service에 중지 요청
     */
    private fun stopAutoCallProcessManually() {
        Log.d(TAG, "자동 전화 프로세스 수동 중지 시작")

        // Service에 중지 요청
        if (isServiceBound && autoCallService != null) {
            autoCallService?.requestStop()
        } else {
            // Service에 직접 중지 Intent 전송
            val stopIntent = Intent(this, AutoCallService::class.java).apply {
                action = AutoCallService.ACTION_STOP
            }
            startService(stopIntent)
        }

        isAutoCalling = false
        updateStartStopButton()

        Log.d(TAG, "자동 전화 프로세스 수동 중지 요청 완료")
    }

    /**
     * 추가 전화번호 가져오기
     */
    private fun fetchMorePhoneNumbers() {
        if (!isAutoCalling) {
            return
        }

        // 전화번호 가져올 개수 설정 읽기
        val phoneNumberLimit = etPhoneNumberLimit.text.toString().toIntOrNull() ?: 10

        Toast.makeText(this, "전화번호를 가져오는 중...", Toast.LENGTH_SHORT).show()

        Log.d(TAG, "서버에서 최대 ${phoneNumberLimit}개의 전화번호 요청")

        // REST API로 설정된 개수만큼 전화번호 가져오기
        ApiClient.getPhoneNumbers(phoneNumberLimit, object : ApiClient.PhoneNumbersCallback {
            override fun onSuccess(phoneNumbers: List<String>) {
                runOnUiThread {
                    // 서버 접속 성공 - 현재 서버 주소 저장
                    val currentServerAddress = etServerAddress.text.toString().trim()
                    saveServerAddress(currentServerAddress)
                    Log.d(TAG, "서버 접속 성공 - 주소 저장: $currentServerAddress")

                    if (phoneNumbers.isNotEmpty()) {
                        // 전화번호를 큐에 추가
                        phoneNumberQueue.addAll(phoneNumbers)
                        currentBatchSize += phoneNumbers.size
                        Toast.makeText(
                            this@MainActivity,
                            "${phoneNumbers.size}개의 전화번호를 가져왔습니다",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 첫 번째 전화 걸기
                        processNextPhoneCall()
                    } else {
                        // 더 이상 가져올 전화번호가 없으면 작업 종료
                        Toast.makeText(
                            this@MainActivity,
                            "더 이상 가져올 전화번호가 없습니다. 작업을 종료합니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                        updateStatus("대기 중", totalPhoneNumbersProcessed, totalPhoneNumbersProcessed)
                        isAutoCalling = false
                        PhoneStateReceiver.restoreAudioSettings(this@MainActivity)
                        updateStartStopButton()
                    }
                }
            }

            override fun onFailure(error: String) {
                runOnUiThread {
                    // 오류 발생 시에도 작업 종료
                    updateStatus("대기 중", totalPhoneNumbersProcessed, currentBatchSize)
                    Toast.makeText(
                        this@MainActivity,
                        "오류: $error - 작업을 종료합니다.",
                        Toast.LENGTH_LONG
                    ).show()
                    isAutoCalling = false
                    PhoneStateReceiver.restoreAudioSettings(this@MainActivity)
                    updateStartStopButton()
                }
            }
        })
    }

    /**
     * 다음 전화번호로 전화 걸기
     */
    private fun processNextPhoneCall() {
        if (!isAutoCalling) {
            return
        }

        if (phoneNumberQueue.isEmpty()) {
            // 현재 큐의 전화번호가 모두 처리 완료되었으므로 추가 전화번호 가져오기
            fetchMorePhoneNumbers()
            return
        }

        val nextPhoneNumber = phoneNumberQueue.poll()
        if (!nextPhoneNumber.isNullOrEmpty()) {
            totalPhoneNumbersProcessed++

            // 진행 상황 업데이트
            updateStatus(
                "전화 걸기",
                totalPhoneNumbersProcessed,
                currentBatchSize,
                nextPhoneNumber
            )

            Toast.makeText(
                this,
                "전화번호: $nextPhoneNumber (${totalPhoneNumbersProcessed}/$currentBatchSize)",
                Toast.LENGTH_SHORT
            ).show()
            makePhoneCall(nextPhoneNumber)
        } else {
            // 빈 전화번호는 건너뛰고 다음으로
            processNextPhoneCall()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.CALL_PHONE)
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        permissions.add(Manifest.permission.RECEIVE_SMS)
        permissions.add(Manifest.permission.READ_SMS)
        permissions.add(Manifest.permission.READ_CONTACTS)
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        permissions.add(Manifest.permission.READ_CALL_LOG)

        // Android 13+ (API 33) 알림 권한 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsNeeded = mutableListOf<String>()

        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        // "다른 앱 위에 표시" 권한 확인 및 요청 (백그라운드에서 전화 걸기용)
        checkOverlayPermission()
    }

    /**
     * "다른 앱 위에 표시" 권한 확인 및 요청
     * 이 권한이 있으면 백그라운드에서도 Activity를 시작할 수 있음
     */
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음 - 설정 화면으로 이동 필요")
                AlertDialog.Builder(this)
                    .setTitle("권한 필요")
                    .setMessage("백그라운드에서 자동 전화를 걸기 위해 '다른 앱 위에 표시' 권한이 필요합니다.\n\n설정 화면에서 권한을 허용해주세요.")
                    .setPositiveButton("설정으로 이동") { _, _ ->
                        try {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "설정 화면 열기 실패: ${e.message}", e)
                            Toast.makeText(this, "설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            } else {
                Log.d(TAG, "SYSTEM_ALERT_WINDOW 권한 허용됨")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "모든 권한이 허용되었습니다", Toast.LENGTH_SHORT).show()
                // 권한이 허용되면 ContentObserver 등록 시도
                registerSmsContentObserver()
                // 권한 허용 후 자동 시작 기능은 제거됨 (사용자가 시작 버튼을 눌러야 함)
            } else {
                Toast.makeText(this, "권한이 필요합니다. SMS 수신이 작동하지 않을 수 있습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makePhoneCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {

            // 타이머 설정값 읽기
            val callTimeout = etCallTimeout.text.toString().toIntOrNull() ?: 30
            val callDuration = etCallDuration.text.toString().toIntOrNull() ?: 5

            Log.d(TAG, "========================================")
            Log.d(TAG, "전화 걸기 설정:")
            Log.d(TAG, "전화 시도 시간: ${callTimeout}초")
            Log.d(TAG, "연결 후 대기: ${callDuration}초")
            Log.d(TAG, "========================================")

            // 전화번호 저장 (전화를 걸었다는 기록)
            CallManager.setLastCalledNumber(phoneNumber)

            // PhoneStateReceiver에 전화번호 및 타이머 설정
            PhoneStateReceiver.setCurrentPhoneNumber(phoneNumber)
            PhoneStateReceiver.setCallTimeouts(callTimeout, callDuration)

            // 전화 걸기 시작 상태 기록 (dial: 전화 걸기 시작)
            ApiClient.recordCall(phoneNumber, "dial")

            try {
                val callIntent = Intent(Intent.ACTION_CALL)
                callIntent.data = Uri.parse("tel:$phoneNumber")
                startActivity(callIntent)

                Toast.makeText(this, "${phoneNumber}로 전화를 걸었습니다", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // 전화 걸기 실패 시 REST API로 기록
                ApiClient.recordCall(phoneNumber, "failed")
                Toast.makeText(this, "전화 걸기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                // 실패 시에도 다음 전화로 진행 (약간의 지연 후)
                handler.postDelayed({
                    processNextPhoneCall()
                }, 1000)
            }
        } else {
            Toast.makeText(this, "전화 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            // 권한이 없을 때도 다음 전화로 진행 (약간의 지연 후)
            handler.postDelayed({
                processNextPhoneCall()
            }, 1000)
        }
    }

    private fun loadSmsHistory() {
        Log.d(TAG, "loadSmsHistory() 호출됨")
        val records = dbHelper.getAllSmsRecords()
        Log.d(TAG, "데이터베이스에서 ${records.size}개의 SMS 레코드 로드됨")
        adapter.updateData(records)
        Log.d(TAG, "RecyclerView 어댑터 업데이트 완료")

        if (records.isEmpty()) {
            Toast.makeText(this, "저장된 SMS 기록이 없습니다", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "SMS 기록 화면 갱신 완료 - 총 ${records.size}개")
        }
    }

    /**
     * SMS Receiver 테스트
     */
    private fun testSmsReceiver() {
        val info = StringBuilder()
        info.append("=== SMS 수신 테스트 ===\n\n")

        // 제조사 확인
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        info.append("제조사: $manufacturer\n")
        info.append("모델: $model\n")
        info.append("Android 버전: API ${Build.VERSION.SDK_INT}\n\n")

        Log.d(TAG, "제조사: $manufacturer, 모델: $model")

        // 삼성 기기 특별 안내
        if (manufacturer.equals("samsung", ignoreCase = true)) {
            info.append("⚠️ 삼성 기기 감지!\n\n")
            info.append("삼성 기기에서 SMS 수신을 위해\n")
            info.append("다음 설정을 확인해주세요:\n\n")
            info.append("1. 설정 > 배터리 > 백그라운드 사용 제한\n")
            info.append("   → AutoCallSmsApp 찾아서 '제한 안 함'\n\n")
            info.append("2. 설정 > 디바이스 케어 > 배터리\n")
            info.append("   → 앱 전원 관리\n")
            info.append("   → AutoCallSmsApp 추가 (최적화 안 함)\n\n")
            info.append("3. 기기 재부팅 후 다시 테스트\n\n")
            Log.w(TAG, "삼성 기기에서 추가 설정 필요")
        }

        // SMS 권한 확인
        val receiveSms = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val readSms = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        info.append("SMS 권한 상태:\n")
        info.append("RECEIVE_SMS: ${if (receiveSms) "✓" else "✗"}\n")
        info.append("READ_SMS: ${if (readSms) "✓" else "✗"}\n\n")

        if (!receiveSms || !readSms) {
            info.append("⚠️ SMS 권한이 거부되었습니다!\n")
            info.append("권한을 다시 요청하려면 '권한 확인' 버튼을 눌러주세요.\n\n")
        }

        info.append("테스트 방법:\n")
        info.append("1. 다른 기기에서 이 기기로 SMS/MMS 전송\n")
        info.append("2. logcat 확인:\n")
        info.append("   adb logcat -s SmsReceiver:D SmsContentObserver:D\n")
        info.append("3. 'SMS 수신 성공!' 또는 '새로운 SMS/MMS 감지!' 메시지 확인\n\n")
        info.append("이 앱은 두 가지 방식으로 SMS/MMS를 수신합니다:\n")
        info.append("- BroadcastReceiver (Android 표준, SMS만)\n")
        info.append("- ContentObserver (SMS/MMS 모두 지원)\n")

        AlertDialog.Builder(this)
            .setTitle("SMS 수신 테스트")
            .setMessage(info.toString())
            .setPositiveButton("확인", null)
            .setNegativeButton("설정 열기") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /**
     * 권한 상태 확인 및 표시
     */
    private fun checkPermissionStatus() {
        val status = StringBuilder()
        status.append("=== 권한 상태 확인 ===\n\n")

        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.CALL_PHONE)
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        permissions.add(Manifest.permission.RECEIVE_SMS)
        permissions.add(Manifest.permission.READ_SMS)
        permissions.add(Manifest.permission.READ_CONTACTS)
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        permissions.add(Manifest.permission.READ_CALL_LOG)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        var granted = 0
        var denied = 0

        for (permission in permissions) {
            val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

            val permName = permission.substring(permission.lastIndexOf('.') + 1)
            status.append("$permName: ")
            status.append(if (isGranted) "✓ 허용됨" else "✗ 거부됨").append("\n")

            if (isGranted) granted++ else denied++

            Log.d(TAG, "$permName: ${if (isGranted) "GRANTED" else "DENIED"}")
        }

        status.append("\n총 ${granted}개 허용, ")
        status.append("${denied}개 거부\n\n")

        // SMS 수신 방법
        status.append("SMS 수신 방법:\n")
        status.append("BroadcastReceiver: AndroidManifest.xml에 등록됨\n")
        status.append("ContentObserver: ")
        status.append(if (isObserverRegistered) "✓ 등록됨" else "✗ 미등록").append("\n")
        Log.d(TAG, "ContentObserver 등록 상태: $isObserverRegistered")

        // Android 버전 정보
        status.append("\nAndroid 버전: API ${Build.VERSION.SDK_INT}")
        status.append(" (${Build.VERSION.RELEASE})")
        Log.d(TAG, "Android 버전: API ${Build.VERSION.SDK_INT}")

        // 대화상자로 표시
        AlertDialog.Builder(this)
            .setTitle("권한 및 상태 확인")
            .setMessage(status.toString())
            .setPositiveButton("확인", null)
            .setNegativeButton("권한 재요청") { _, _ ->
                checkAndRequestPermissions()
            }
            .show()
    }

    /**
     * SMS ContentObserver 등록
     */
    private fun registerSmsContentObserver() {
        if (!isObserverRegistered) {
            // READ_SMS 권한 확인
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    smsContentObserver = SmsContentObserver(this, Handler(Looper.getMainLooper()))

                    // SMS inbox URI 모니터링
                    val smsUri = Uri.parse("content://sms/inbox")
                    contentResolver.registerContentObserver(smsUri, true, smsContentObserver!!)

                    // MMS inbox URI 모니터링
                    val mmsUri = Uri.parse("content://mms/inbox")
                    contentResolver.registerContentObserver(mmsUri, true, smsContentObserver!!)

                    isObserverRegistered = true
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "SMS/MMS ContentObserver 등록 완료!")
                    Log.d(TAG, "SMS URI: content://sms/inbox")
                    Log.d(TAG, "MMS URI: content://mms/inbox")
                    Log.d(TAG, "========================================")
                    Toast.makeText(this, "SMS/MMS 모니터링 시작 (ContentObserver)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "ContentObserver 등록 실패: ${e.message}", e)
                    Toast.makeText(this, "SMS/MMS 모니터링 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.w(TAG, "READ_SMS 권한이 없어서 ContentObserver를 등록할 수 없습니다")
            }
        } else {
            Log.d(TAG, "SMS/MMS ContentObserver 이미 등록됨")
        }
    }

    /**
     * SMS/MMS ContentObserver 등록 해제
     */
    private fun unregisterSmsContentObserver() {
        if (isObserverRegistered && smsContentObserver != null) {
            try {
                contentResolver.unregisterContentObserver(smsContentObserver!!)
                isObserverRegistered = false
                Log.d(TAG, "SMS/MMS ContentObserver 등록 해제됨")
            } catch (e: Exception) {
                Log.e(TAG, "ContentObserver 등록 해제 실패: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy()")

        dbHelper.close()
        handler.removeCallbacksAndMessages(null)

        // Service 언바인딩 (Service는 계속 실행됨)
        unbindAutoCallService()

        // DisconnectCause 리스너 해제
        CallDisconnectListener.unregister()

        // SMS/MMS ContentObserver 등록 해제
        unregisterSmsContentObserver()

        // SMS 수신 BroadcastReceiver 등록 해제
        try {
            unregisterReceiver(smsUpdateReceiver)
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록 해제됨")
        } catch (e: Exception) {
            Log.e(TAG, "BroadcastReceiver 등록 해제 실패: ${e.message}")
        }

        // 주의: Service가 실행 중이면 isAutoCalling, phoneNumberQueue를 초기화하지 않음
        // Service가 독립적으로 동작하므로 Activity가 종료되어도 계속 실행됨
        if (!AutoCallService.isRunning) {
            isAutoCalling = false
            phoneNumberQueue.clear()
            // 오디오 설정 복원 (Service가 없을 때만)
            PhoneStateReceiver.restoreAudioSettings(this)
            PhoneStateReceiver.cleanup()
            ApiClient.shutdown()
        }
    }

    /**
     * 진행 상황 표시 업데이트
     */
    private fun updateStatus(status: String, currentIndex: Int = 0, total: Int = 0, phoneNumber: String? = null) {
        runOnUiThread {
            statusCard.visibility = android.view.View.VISIBLE
            tvStatus.text = status

            if (total > 0) {
                tvProgress.text = "진행: $currentIndex/$total"
                progressBar.max = total
                progressBar.progress = currentIndex
            } else {
                tvProgress.text = ""
                progressBar.progress = 0
            }

            if (!phoneNumber.isNullOrEmpty()) {
                tvCurrentNumber.visibility = android.view.View.VISIBLE
                tvCurrentNumber.text = phoneNumber
            } else {
                tvCurrentNumber.visibility = android.view.View.GONE
            }
        }
    }

    /**
     * 진행 상황 표시 숨기기
     */
    private fun hideStatus() {
        runOnUiThread {
            statusCard.visibility = android.view.View.GONE
        }
    }

    /**
     * 시작/중지 버튼 텍스트 및 상태 업데이트
     */
    private fun updateStartStopButton() {
        runOnUiThread {
            if (isAutoCalling) {
                btnStart.text = "중지"
                // 빨간색으로 변경 (중지 버튼)
                btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336"))
            } else {
                btnStart.text = "시작"
                // 초록색으로 변경 (시작 버튼)
                btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
            }
            btnStart.isEnabled = true
        }
    }

    /**
     * 서버 주소 저장
     */
    private fun saveServerAddress(address: String) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_ADDRESS, address).apply()
        Log.d(TAG, "서버 주소 저장됨: $address")
    }

    /**
     * 저장된 서버 주소 로드 (없으면 기본값 반환)
     */
    private fun loadServerAddress(): String {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS) ?: DEFAULT_SERVER_ADDRESS
    }

    /**
     * 현재 앱이 기본 전화 앱인지 확인
     */
    private fun isDefaultDialer(): Boolean {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return telecomManager?.defaultDialerPackage == packageName
    }

    /**
     * 기본 전화 앱으로 설정 요청
     */
    private fun requestDefaultDialer() {
        if (isDefaultDialer()) {
            Toast.makeText(this, "이미 기본 전화 앱으로 설정되어 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // 사용자에게 설명 다이얼로그 표시
        AlertDialog.Builder(this)
            .setTitle("기본 전화 앱 설정")
            .setMessage(
                "정확한 통화 상태 감지를 위해 이 앱을 기본 전화 앱으로 설정해야 합니다.\n\n" +
                "이 설정을 하면:\n" +
                "• 상대방이 전화를 받았을 때 정확하게 감지됩니다\n" +
                "• 통화 종료 원인을 정확하게 파악할 수 있습니다\n\n" +
                "※ 기존 전화 앱의 기능은 그대로 사용할 수 있습니다."
            )
            .setPositiveButton("설정하기") { _, _ ->
                launchDefaultDialerRequest()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 기본 전화 앱 설정 화면 실행
     */
    private fun launchDefaultDialerRequest() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 이상: RoleManager 사용
                val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                        defaultDialerLauncher.launch(intent)
                        Log.d(TAG, "RoleManager로 기본 전화 앱 요청")
                    } else {
                        Toast.makeText(this, "이미 기본 전화 앱입니다.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // RoleManager 사용 불가 시 TelecomManager 사용
                    launchDefaultDialerWithTelecom()
                }
            } else {
                // Android 9 이하: TelecomManager 사용
                launchDefaultDialerWithTelecom()
            }
        } catch (e: Exception) {
            Log.e(TAG, "기본 전화 앱 설정 요청 실패: ${e.message}", e)
            Toast.makeText(this, "기본 전화 앱 설정을 열 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * TelecomManager를 사용하여 기본 전화 앱 설정
     */
    private fun launchDefaultDialerWithTelecom() {
        try {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            defaultDialerLauncher.launch(intent)
            Log.d(TAG, "TelecomManager로 기본 전화 앱 요청")
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager 기본 전화 앱 요청 실패: ${e.message}", e)
            // 최후의 수단: 설정 화면으로 이동
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "'전화 앱'에서 이 앱을 선택해주세요.", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 기본 전화 앱 버튼 상태 업데이트
     */
    private fun updateDefaultDialerButton() {
        runOnUiThread {
            if (isDefaultDialer()) {
                btnSetDefaultDialer.text = "✓ 기본 전화 앱 (설정됨)"
                btnSetDefaultDialer.isEnabled = false
                btnSetDefaultDialer.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#888888"))
            } else {
                btnSetDefaultDialer.text = "기본 전화 앱으로 설정"
                btnSetDefaultDialer.isEnabled = true
                btnSetDefaultDialer.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 앱이 다시 활성화될 때 SMS 이력 새로고침
        loadSmsHistory()
        // 기본 전화 앱 상태 업데이트
        updateDefaultDialerButton()
    }
}
