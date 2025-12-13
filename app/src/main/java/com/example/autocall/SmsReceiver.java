package com.example.autocall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import android.os.PowerManager;
import android.widget.Toast;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive 호출됨 - Action: " + (intent.getAction() != null ? intent.getAction() : "null"));

        final PendingResult pendingResult = goAsync();

        // WakeLock 획득
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "AutoCallSms::SmsReceiverWakeLock");
        wakeLock.acquire(10 * 60 * 1000L /* 10 minutes */);

        new Thread(() -> {
            try {
                handleSmsReceive(context, intent);
            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                pendingResult.finish();
            }
        }).start();
    }

    private void handleSmsReceive(Context context, Intent intent) {
        if (intent.getAction() != null && intent.getAction().equals(SMS_RECEIVED)) {
            Log.d(TAG, "SMS_RECEIVED 액션 확인됨");
            Bundle bundle = intent.getExtras();

            if (bundle != null) {
                Log.d(TAG, "Bundle이 null이 아님");
                try {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    String format = bundle.getString("format");

                    if (pdus != null) {
                        Log.d(TAG, "PDU 개수: " + pdus.length);
                        for (Object pdu : pdus) {
                            SmsMessage smsMessage;

                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                            } else {
                                smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                            }

                            String phoneNumber = smsMessage.getDisplayOriginatingAddress();
                            String message = smsMessage.getMessageBody();
                            long timestamp = smsMessage.getTimestampMillis();

                            Log.d(TAG, "========================================");
                            Log.d(TAG, "SMS 수신 성공!");
                            Log.d(TAG, "발신번호: " + phoneNumber);
                            Log.d(TAG, "메시지: " + message);
                            Log.d(TAG, "타임스탬프: " + timestamp);
                            Log.d(TAG, "========================================");

                            // 최근 통화 여부와 상관없이 저장
                            // 로컬 데이터베이스에 저장
                            DatabaseHelper dbHelper = new DatabaseHelper(context);
                            long id = dbHelper.insertSmsRecord(phoneNumber, message, timestamp);
                            dbHelper.close();

                            if (id != -1) {
                                Log.d(TAG, "SMS 저장 성공: ID = " + id);

                                // MainActivity에 SMS 수신 알림 (명시적 Intent 사용)
                                Intent updateIntent = new Intent(MainActivity.ACTION_SMS_RECEIVED);
                                updateIntent.setPackage(context.getPackageName());
                                context.sendBroadcast(updateIntent);
                                Log.d(TAG, "UI 갱신 브로드캐스트 전송됨 (패키지: " + context.getPackageName() + ")");

                                // REST API로 SMS 기록 전송 (비동기 처리 완료 대기)
                                // 여기서 pendingResult.finish()를 호출하기 위해 동기적으로 처리하거나
                                // ApiClient가 비동기 처리를 완료할 때까지 기다려야 함.
                                // 하지만 이미 별도 스레드(new Thread)에서 실행 중이므로
                                // ApiClient의 비동기 처리를 기다리는 래치(Latch)나 콜백 구조가 필요함.
                                // 간단하게 ApiClient 수정하여 콜백 받도록 처리.

                                // 동기화를 위한 객체
                                Object lock = new Object();

                                ApiClient.recordSms(phoneNumber, message, () -> {
                                    synchronized (lock) {
                                        lock.notify();
                                    }
                                });

                                // API 호출 완료 대기 (최대 10초)
                                synchronized (lock) {
                                    try {
                                        lock.wait(10000);
                                    } catch (InterruptedException e) {
                                        Log.e(TAG, "API 응답 대기 중 인터럽트 발생");
                                    }
                                }

                                // 메인 스레드에서 Toast 표시
                                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    Toast.makeText(context,
                                            "응답 SMS 저장됨: " + phoneNumber,
                                            Toast.LENGTH_SHORT).show();
                                });
                            } else {
                                Log.e(TAG, "SMS 저장 실패!");
                            }
                        }
                    } else {
                        Log.w(TAG, "pdus가 null임");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "SMS 처리 오류: " + e.getMessage(), e);
                }
            } else {
                Log.w(TAG, "Bundle이 null임");
            }
        } else {
            Log.w(TAG, "SMS_RECEIVED 액션이 아님");
        }
    }

    /**
     * 전화번호 매칭 (국가 코드 등 다양한 형식 처리)
     */
    private boolean isPhoneNumberMatch(String number1, String number2) {
        if (number1 == null || number2 == null) {
            return false;
        }

        // 숫자만 추출
        String cleaned1 = number1.replaceAll("[^0-9]", "");
        String cleaned2 = number2.replaceAll("[^0-9]", "");

        // 완전 일치
        if (cleaned1.equals(cleaned2)) {
            return true;
        }

        // 국가 코드 적용 여부에 따른 매칭
        // 예: 01012345678 vs +821012345678
        if (cleaned1.length() >= 10 && cleaned2.length() >= 10) {
            String suffix1 = cleaned1.substring(cleaned1.length() - 10);
            String suffix2 = cleaned2.substring(cleaned2.length() - 10);
            return suffix1.equals(suffix2);
        }

        return false;
    }
}
