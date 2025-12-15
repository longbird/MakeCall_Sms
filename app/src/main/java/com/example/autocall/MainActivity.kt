package com.example.autocall

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
        const val ACTION_SMS_RECEIVED = "com.example.autocall.SMS_RECEIVED"
    }

    private lateinit var etPhoneNumber: EditText
    private lateinit var etServerAddress: EditText
    private lateinit var etCallTimeout: EditText
    private lateinit var etCallDuration: EditText
    private lateinit var etPhoneNumberLimit: EditText
    private lateinit var etEndTime: EditText
    private lateinit var btnStart: Button
    private lateinit var btnMakeCall: Button
    private lateinit var btnViewHistory: Button
    private lateinit var btnCheckPermissions: Button
    private lateinit var btnTestSms: Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 데이터베이스 헬퍼 초기화
        dbHelper = DatabaseHelper(this)

        // UI 컴포넌트 초기화
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        etServerAddress = findViewById(R.id.etServerAddress)
        etCallTimeout = findViewById(R.id.etCallTimeout)
        etCallDuration = findViewById(R.id.etCallDuration)
        etPhoneNumberLimit = findViewById(R.id.etPhoneNumberLimit)
        etEndTime = findViewById(R.id.etEndTime)
        // 기본 서버 주소 설정
        etServerAddress.setText("192.168.0.210:8080")
        // 기본 타이머, 전화번호 개수, 종료 시간 설정 (이미 XML에서 설정되어 있음)
        btnStart = findViewById(R.id.btnStart)
        btnMakeCall = findViewById(R.id.btnMakeCall)
        btnViewHistory = findViewById(R.id.btnViewHistory)
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions)
        btnTestSms = findViewById(R.id.btnTestSms)
        recyclerView = findViewById(R.id.recyclerView)

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

        // 시작 버튼 클릭 이벤트
        btnStart.setOnClickListener {
            startAutoCallProcess()
        }

        // 전화 걸기 버튼 클릭 이벤트
        btnMakeCall.setOnClickListener {
            val phoneNumber = etPhoneNumber.text.toString().trim()
            if (phoneNumber.isNotEmpty()) {
                makePhoneCall(phoneNumber)
            } else {
                Toast.makeText(this, "전화번호를 입력하세요", Toast.LENGTH_SHORT).show()
            }
        }

        // SMS 이력 새로고침 버튼
        btnViewHistory.setOnClickListener {
            loadSmsHistory()
        }

        // 권한 상태 확인 버튼
        btnCheckPermissions.setOnClickListener {
            checkPermissionStatus()
        }

        // SMS 테스트 버튼
        btnTestSms.setOnClickListener {
            testSmsReceiver()
        }

        // 앱 시작시 SMS 이력 로드
        loadSmsHistory()

        // PhoneStateReceiver에 전화 종료 리스너 설정
        PhoneStateReceiver.setOnCallEndedListener(object : PhoneStateReceiver.Companion.OnCallEndedListener {
            override fun onCallEnded() {
                // 전화가 끝나면 다음 전화 걸기
                runOnUiThread {
                    processNextPhoneCall()
                }
            }
        })

        // 앱 실행 시 자동으로 작업 시작
        autoStartIfReady()
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
     * 자동 전화 프로세스 시작
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

        // API 클라이언트 주소 설정
        ApiClient.setBaseUrl(serverAddress)
        Log.d(TAG, "ApiClient에 서버 주소 설정 완료")

        if (isAutoCalling) {
            Toast.makeText(this, "이미 자동 전화가 진행 중입니다", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "이미 자동 전화 진행 중")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "자동 전화 프로세스 시작!")
        Log.d(TAG, "서버: $serverAddress")
        Log.d(TAG, "========================================")

        btnStart.isEnabled = false
        isAutoCalling = true
        phoneNumberQueue.clear()
        totalPhoneNumbersProcessed = 0
        currentBatchSize = 0

        // 진행 상황 표시
        updateStatus("전화번호 가져오는 중...")

        // 종료 시간 체크 시작
        startEndTimeCheck()

        // 전화번호 가져오기 시작
        fetchMorePhoneNumbers()
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
     * 자동 전화 프로세스 종료
     */
    private fun stopAutoCallProcess() {
        Log.d(TAG, "자동 전화 프로세스 종료 시작")

        // 진행 중인 전화 종료
        isAutoCalling = false

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
            btnStart.isEnabled = true
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
                        updateStatus("작업 완료", totalPhoneNumbersProcessed, totalPhoneNumbersProcessed)
                        isAutoCalling = false
                        btnStart.isEnabled = true
                    }
                }
            }

            override fun onFailure(error: String) {
                runOnUiThread {
                    // 오류 발생 시에도 작업 종료
                    updateStatus(error, totalPhoneNumbersProcessed, currentBatchSize)
                    Toast.makeText(
                        this@MainActivity,
                        "오류: $error - 작업을 종료합니다.",
                        Toast.LENGTH_LONG
                    ).show()
                    isAutoCalling = false
                    btnStart.isEnabled = true
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
            etPhoneNumber.setText(nextPhoneNumber)

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
                // 권한 허용 후 자동 시작
                autoStartIfReady()
            } else {
                Toast.makeText(this, "권한이 필요합니다. SMS 수신이 작동하지 않을 수 있습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makePhoneCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {

            // 타이머 설정값 읽기
            val callTimeout = etCallTimeout.text.toString().toIntOrNull() ?: 30
            val callDuration = etCallDuration.text.toString().toIntOrNull() ?: 20

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

            // 전화 걸기 시작 상태 기록
            ApiClient.recordCall(phoneNumber, "started")

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

    override fun onResume() {
        super.onResume()
        // 앱이 다시 활성화될 때 SMS 이력 새로고침
        loadSmsHistory()
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
        dbHelper.close()
        handler.removeCallbacksAndMessages(null)
        // PhoneStateReceiver 리스너 및 타이머 정리 (메모리 누수 방지)
        PhoneStateReceiver.cleanup()
        // ApiClient ExecutorService 종료
        ApiClient.shutdown()
        // SMS/MMS ContentObserver 등록 해제
        unregisterSmsContentObserver()
        // SMS 수신 BroadcastReceiver 등록 해제
        try {
            unregisterReceiver(smsUpdateReceiver)
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록 해제됨")
        } catch (e: Exception) {
            Log.e(TAG, "BroadcastReceiver 등록 해제 실패: ${e.message}")
        }
        isAutoCalling = false
        phoneNumberQueue.clear()
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
                tvCurrentNumber.text = "현재: $phoneNumber"
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
}
