package com.example.autocall;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.LinkedList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    public static final String ACTION_SMS_RECEIVED = "com.example.autocall.SMS_RECEIVED";

    private EditText etPhoneNumber;
    private EditText etServerAddress;
    private Button btnStart;
    private Button btnMakeCall;
    private Button btnViewHistory;
    private Button btnCheckPermissions;
    private Button btnTestSms;
    private RecyclerView recyclerView;
    private SmsHistoryAdapter adapter;
    private DatabaseHelper dbHelper;
    private Handler handler = new Handler();

    // SMS ContentObserver (BroadcastReceiver 대체)
    private SmsContentObserver smsContentObserver;
    private boolean isObserverRegistered = false;

    // SMS 수신 BroadcastReceiver
    private BroadcastReceiver smsUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "BroadcastReceiver.onReceive() 호출됨 - Action: " + intent.getAction());
            if (ACTION_SMS_RECEIVED.equals(intent.getAction())) {
                Log.d(TAG, "========================================");
                Log.d(TAG, "SMS 수신 브로드캐스트 받음 - UI 갱신 시작");
                Log.d(TAG, "========================================");
                loadSmsHistory();
            }
        }
    };

    // 전화번호 리스트 관리
    private Queue<String> phoneNumberQueue = new LinkedList<>();
    private static final int MAX_PHONE_NUMBERS = 10;
    private boolean isAutoCalling = false;
    private boolean hasAutoStarted = false; // 자동 시작 플래그

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 데이터베이스 헬퍼 초기화
        dbHelper = new DatabaseHelper(this);

        // UI 컴포넌트 초기화
        etPhoneNumber = findViewById(R.id.etPhoneNumber);
        etServerAddress = findViewById(R.id.etServerAddress);
        // 기본 서버 주소 설정
        etServerAddress.setText("192.168.0.210:8080");
        btnStart = findViewById(R.id.btnStart);
        btnMakeCall = findViewById(R.id.btnMakeCall);
        btnViewHistory = findViewById(R.id.btnViewHistory);
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        btnTestSms = findViewById(R.id.btnTestSms);
        recyclerView = findViewById(R.id.recyclerView);

        // RecyclerView 설정
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SmsHistoryAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        // 권한 확인 및 요청
        checkAndRequestPermissions();

        // SMS ContentObserver 등록
        registerSmsContentObserver();

        // SMS 수신 BroadcastReceiver 등록
        IntentFilter filter = new IntentFilter(ACTION_SMS_RECEIVED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록됨 (RECEIVER_NOT_EXPORTED)");
        } else {
            registerReceiver(smsUpdateReceiver, filter);
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록됨");
        }
        Log.d(TAG, "Action 필터: " + ACTION_SMS_RECEIVED);

        // 시작 버튼 클릭 이벤트
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAutoCallProcess();
            }
        });

        // 전화 걸기 버튼 클릭 이벤트
        btnMakeCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String phoneNumber = etPhoneNumber.getText().toString().trim();
                if (!phoneNumber.isEmpty()) {
                    makePhoneCall(phoneNumber);
                } else {
                    Toast.makeText(MainActivity.this, "전화번호를 입력하세요", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // SMS 이력 새로고침 버튼
        btnViewHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadSmsHistory();
            }
        });

        // 권한 상태 확인 버튼
        btnCheckPermissions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermissionStatus();
            }
        });

        // SMS 테스트 버튼
        btnTestSms.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testSmsReceiver();
            }
        });

        // 앱 시작시 SMS 이력 로드
        loadSmsHistory();

        // PhoneStateReceiver에 전화 종료 리스너 설정
        PhoneStateReceiver.setOnCallEndedListener(new PhoneStateReceiver.OnCallEndedListener() {
            @Override
            public void onCallEnded() {
                // 전화가 끝나면 다음 전화 걸기
                runOnUiThread(() -> {
                    processNextPhoneCall();
                });
            }
        });

        // 앱 실행 시 자동으로 작업 시작
        autoStartIfReady();
    }

    /**
     * 권한이 모두 허용되어 있으면 자동으로 작업 시작
     */
    private void autoStartIfReady() {
        if (hasAutoStarted) {
            return; // 이미 자동 시작했으면 건너뛰기
        }

        // 필수 권한 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {

            hasAutoStarted = true;

            // 약간의 지연 후 자동 시작 (UI가 완전히 초기화되도록)
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "자동으로 작업을 시작합니다...", Toast.LENGTH_SHORT).show();
                    startAutoCallProcess();
                }
            }, 1000);
        }
    }

    /**
     * 자동 전화 프로세스 시작
     */
    private void startAutoCallProcess() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "전화 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            checkAndRequestPermissions();
            return;
        }

        // 서버 주소 설정
        String serverAddress = etServerAddress.getText().toString().trim();
        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "서버 주소를 입력하세요", Toast.LENGTH_SHORT).show();
            return;
        }

        // API 클라이언트 주소 설정
        ApiClient.setBaseUrl(serverAddress);

        if (isAutoCalling) {
            Toast.makeText(this, "이미 자동 전화가 진행 중입니다", Toast.LENGTH_SHORT).show();
            return;
        }

        btnStart.setEnabled(false);
        isAutoCalling = true;
        phoneNumberQueue.clear();

        // 전화번호 가져오기 시작
        fetchMorePhoneNumbers();
    }

    /**
     * 추가 전화번호 가져오기
     */
    private void fetchMorePhoneNumbers() {
        if (!isAutoCalling) {
            return;
        }

        Toast.makeText(this, "전화번호를 가져오는 중...", Toast.LENGTH_SHORT).show();

        // REST API로 최대 10개의 전화번호 가져오기
        ApiClient.getPhoneNumbers(MAX_PHONE_NUMBERS, new ApiClient.PhoneNumbersCallback() {
            @Override
            public void onSuccess(List<String> phoneNumbers) {
                runOnUiThread(() -> {
                    if (phoneNumbers != null && !phoneNumbers.isEmpty()) {
                        // 전화번호를 큐에 추가
                        phoneNumberQueue.addAll(phoneNumbers);
                        Toast.makeText(MainActivity.this,
                                phoneNumbers.size() + "개의 전화번호를 가져왔습니다",
                                Toast.LENGTH_SHORT).show();

                        // 첫 번째 전화 걸기
                        processNextPhoneCall();
                    } else {
                        // 더 이상 가져올 전화번호가 없으면 작업 종료
                        Toast.makeText(MainActivity.this, "더 이상 가져올 전화번호가 없습니다. 작업을 종료합니다.", Toast.LENGTH_SHORT).show();
                        isAutoCalling = false;
                        btnStart.setEnabled(true);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    // 오류 발생 시에도 작업 종료
                    Toast.makeText(MainActivity.this, "오류: " + error + " - 작업을 종료합니다.", Toast.LENGTH_LONG).show();
                    isAutoCalling = false;
                    btnStart.setEnabled(true);
                });
            }
        });
    }

    /**
     * 다음 전화번호로 전화 걸기
     */
    private void processNextPhoneCall() {
        if (!isAutoCalling) {
            return;
        }

        if (phoneNumberQueue.isEmpty()) {
            // 현재 큐의 전화번호가 모두 처리 완료되었으므로 추가 전화번호 가져오기
            fetchMorePhoneNumbers();
            return;
        }

        String nextPhoneNumber = phoneNumberQueue.poll();
        if (nextPhoneNumber != null && !nextPhoneNumber.isEmpty()) {
            etPhoneNumber.setText(nextPhoneNumber);
            Toast.makeText(this, "전화번호: " + nextPhoneNumber +
                    " (남은 개수: " + phoneNumberQueue.size() + ")", Toast.LENGTH_SHORT).show();
            makePhoneCall(nextPhoneNumber);
        } else {
            // 빈 전화번호는 건너뛰고 다음으로
            processNextPhoneCall();
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CALL_PHONE);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.RECEIVE_SMS);
        permissions.add(Manifest.permission.READ_SMS);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);

        // Android 13+ (API 33) 알림 권한 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> permissionsNeeded = new ArrayList<>();

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "모든 권한이 허용되었습니다", Toast.LENGTH_SHORT).show();
                // 권한이 허용되면 ContentObserver 등록 시도
                registerSmsContentObserver();
                // 권한 허용 후 자동 시작
                autoStartIfReady();
            } else {
                Toast.makeText(this, "권한이 필요합니다. SMS 수신이 작동하지 않을 수 있습니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void makePhoneCall(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {

            // 전화번호 저장 (전화를 걸었다는 기록)
            CallManager.getInstance().setLastCalledNumber(phoneNumber);

            // PhoneStateReceiver에 전화번호 설정
            PhoneStateReceiver.setCurrentPhoneNumber(phoneNumber);

            // 전화 걸기 시작 상태 기록
            ApiClient.recordCall(phoneNumber, "started");

            try {
                Intent callIntent = new Intent(Intent.ACTION_CALL);
                callIntent.setData(Uri.parse("tel:" + phoneNumber));
                startActivity(callIntent);

                Toast.makeText(this, phoneNumber + "로 전화를 걸었습니다", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                // 전화 걸기 실패 시 REST API로 기록
                ApiClient.recordCall(phoneNumber, "failed");
                Toast.makeText(this, "전화 걸기 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                // 실패 시에도 다음 전화로 진행 (약간의 지연 후)
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        processNextPhoneCall();
                    }
                }, 1000);
            }
        } else {
            Toast.makeText(this, "전화 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            checkAndRequestPermissions();
            // 권한이 없을 때도 다음 전화로 진행 (약간의 지연 후)
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    processNextPhoneCall();
                }
            }, 1000);
        }
    }

    private void loadSmsHistory() {
        Log.d(TAG, "loadSmsHistory() 호출됨");
        List<SmsRecord> records = dbHelper.getAllSmsRecords();
        Log.d(TAG, "데이터베이스에서 " + records.size() + "개의 SMS 레코드 로드됨");
        adapter.updateData(records);
        Log.d(TAG, "RecyclerView 어댑터 업데이트 완료");

        if (records.isEmpty()) {
            Toast.makeText(this, "저장된 SMS 기록이 없습니다", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "SMS 기록 화면 갱신 완료 - 총 " + records.size() + "개");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 앱이 다시 활성화될 때 SMS 이력 새로고침
        loadSmsHistory();
    }

    /**
     * SMS Receiver 테스트
     */
    private void testSmsReceiver() {
        StringBuilder info = new StringBuilder();
        info.append("=== SMS 수신 테스트 ===\n\n");

        // 제조사 확인
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        info.append("제조사: ").append(manufacturer).append("\n");
        info.append("모델: ").append(model).append("\n");
        info.append("Android 버전: API ").append(Build.VERSION.SDK_INT).append("\n\n");

        Log.d(TAG, "제조사: " + manufacturer + ", 모델: " + model);

        // 삼성 기기 특별 안내
        if (manufacturer.equalsIgnoreCase("samsung")) {
            info.append("⚠️ 삼성 기기 감지!\n\n");
            info.append("삼성 기기에서 SMS 수신을 위해\n");
            info.append("다음 설정을 확인해주세요:\n\n");
            info.append("1. 설정 > 배터리 > 백그라운드 사용 제한\n");
            info.append("   → AutoCallSmsApp 찾아서 '제한 안 함'\n\n");
            info.append("2. 설정 > 디바이스 케어 > 배터리\n");
            info.append("   → 앱 전원 관리\n");
            info.append("   → AutoCallSmsApp 추가 (최적화 안 함)\n\n");
            info.append("3. 기기 재부팅 후 다시 테스트\n\n");
            Log.w(TAG, "삼성 기기에서 추가 설정 필요");
        }

        // SMS 권한 확인
        boolean receiveSms = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean readSms = ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;

        info.append("SMS 권한 상태:\n");
        info.append("RECEIVE_SMS: ").append(receiveSms ? "✓" : "✗").append("\n");
        info.append("READ_SMS: ").append(readSms ? "✓" : "✗").append("\n\n");

        if (!receiveSms || !readSms) {
            info.append("⚠️ SMS 권한이 거부되었습니다!\n");
            info.append("권한을 다시 요청하려면 '권한 확인' 버튼을 눌러주세요.\n\n");
        }

        info.append("테스트 방법:\n");
        info.append("1. 다른 기기에서 이 기기로 SMS 전송\n");
        info.append("2. logcat 확인:\n");
        info.append("   adb logcat -s SmsReceiver:D SmsContentObserver:D\n");
        info.append("3. 'SMS 수신 성공!' 또는 '새로운 SMS 감지!' 메시지 확인\n\n");
        info.append("이 앱은 두 가지 방식으로 SMS를 수신합니다:\n");
        info.append("- BroadcastReceiver (Android 표준)\n");
        info.append("- ContentObserver (차단 시 대체 방법)\n");

        new AlertDialog.Builder(this)
                .setTitle("SMS 수신 테스트")
                .setMessage(info.toString())
                .setPositiveButton("확인", null)
                .setNegativeButton("설정 열기", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /**
     * 권한 상태 확인 및 표시
     */
    private void checkPermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== 권한 상태 확인 ===\n\n");

        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CALL_PHONE);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.RECEIVE_SMS);
        permissions.add(Manifest.permission.READ_SMS);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        int granted = 0;
        int denied = 0;

        for (String permission : permissions) {
            boolean isGranted = ContextCompat.checkSelfPermission(this,
                    permission) == PackageManager.PERMISSION_GRANTED;

            String permName = permission.substring(permission.lastIndexOf('.') + 1);
            status.append(permName).append(": ");
            status.append(isGranted ? "✓ 허용됨" : "✗ 거부됨").append("\n");

            if (isGranted)
                granted++;
            else
                denied++;

            Log.d(TAG, permName + ": " + (isGranted ? "GRANTED" : "DENIED"));
        }

        status.append("\n총 ").append(granted).append("개 허용, ");
        status.append(denied).append("개 거부\n\n");

        // SMS 수신 방법
        status.append("SMS 수신 방법:\n");
        status.append("BroadcastReceiver: AndroidManifest.xml에 등록됨\n");
        status.append("ContentObserver: ");
        status.append(isObserverRegistered ? "✓ 등록됨" : "✗ 미등록").append("\n");
        Log.d(TAG, "ContentObserver 등록 상태: " + isObserverRegistered);

        // Android 버전 정보
        status.append("\nAndroid 버전: API ").append(Build.VERSION.SDK_INT);
        status.append(" (").append(Build.VERSION.RELEASE).append(")");
        Log.d(TAG, "Android 버전: API " + Build.VERSION.SDK_INT);

        // 대화상자로 표시
        new AlertDialog.Builder(this)
                .setTitle("권한 및 상태 확인")
                .setMessage(status.toString())
                .setPositiveButton("확인", null)
                .setNegativeButton("권한 재요청", (dialog, which) -> {
                    checkAndRequestPermissions();
                })
                .show();
    }

    /**
     * SMS ContentObserver 등록
     */
    private void registerSmsContentObserver() {
        if (!isObserverRegistered) {
            // READ_SMS 권한 확인
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    smsContentObserver = new SmsContentObserver(this, new Handler(Looper.getMainLooper()));

                    // SMS inbox URI 모니터링
                    Uri smsUri = Uri.parse("content://sms/inbox");
                    getContentResolver().registerContentObserver(smsUri, true, smsContentObserver);

                    isObserverRegistered = true;
                    Log.d(TAG, "========================================");
                    Log.d(TAG, "SMS ContentObserver 등록 완료!");
                    Log.d(TAG, "URI: content://sms/inbox");
                    Log.d(TAG, "========================================");
                    Toast.makeText(this, "SMS 모니터링 시작 (ContentObserver)", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "ContentObserver 등록 실패: " + e.getMessage(), e);
                    Toast.makeText(this, "SMS 모니터링 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Log.w(TAG, "READ_SMS 권한이 없어서 ContentObserver를 등록할 수 없습니다");
            }
        } else {
            Log.d(TAG, "SMS ContentObserver 이미 등록됨");
        }
    }

    /**
     * SMS ContentObserver 등록 해제
     */
    private void unregisterSmsContentObserver() {
        if (isObserverRegistered && smsContentObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(smsContentObserver);
                isObserverRegistered = false;
                Log.d(TAG, "SMS ContentObserver 등록 해제됨");
            } catch (Exception e) {
                Log.e(TAG, "ContentObserver 등록 해제 실패: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
        handler.removeCallbacksAndMessages(null);
        // PhoneStateReceiver 리스너 및 타이머 정리 (메모리 누수 방지)
        PhoneStateReceiver.cleanup();
        // ApiClient ExecutorService 종료
        ApiClient.shutdown();
        // SMS ContentObserver 등록 해제
        unregisterSmsContentObserver();
        // SMS 수신 BroadcastReceiver 등록 해제
        try {
            unregisterReceiver(smsUpdateReceiver);
            Log.d(TAG, "SMS 수신 BroadcastReceiver 등록 해제됨");
        } catch (Exception e) {
            Log.e(TAG, "BroadcastReceiver 등록 해제 실패: " + e.getMessage());
        }
        isAutoCalling = false;
        phoneNumberQueue.clear();
    }
}
