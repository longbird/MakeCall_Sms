package com.example.autocall;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * SMS Provider를 직접 모니터링하는 ContentObserver
 * BroadcastReceiver가 차단된 경우를 대비한 대체 방법
 */
public class SmsContentObserver extends ContentObserver {
    private static final String TAG = "SmsContentObserver";
    private Context context;
    private long lastMessageId = -1;
    private long lastCheckTime = 0;

    public SmsContentObserver(Context context, Handler handler) {
        super(handler);
        this.context = context;
        this.lastCheckTime = System.currentTimeMillis();
        Log.d(TAG, "SmsContentObserver 생성됨");
    }

    @Override
    public void onChange(boolean selfChange) {
        super.onChange(selfChange);
        Log.d(TAG, "onChange 호출됨 - selfChange: " + selfChange);
        checkNewSms();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);
        Log.d(TAG, "onChange 호출됨 - URI: " + uri);
        checkNewSms();
    }

    /**
     * 새로운 SMS가 있는지 확인
     */
    private void checkNewSms() {
        try {
            // SMS inbox URI
            Uri smsUri = Uri.parse("content://sms/inbox");

            // 최근 SMS만 조회 (지난 10초 이내)
            long currentTime = System.currentTimeMillis();
            String selection = "date > ?";
            String[] selectionArgs = new String[]{String.valueOf(lastCheckTime - 5000)}; // 5초 여유

            // SMS 조회
            Cursor cursor = context.getContentResolver().query(
                    smsUri,
                    new String[]{"_id", "address", "body", "date"},
                    selection,
                    selectionArgs,
                    "date DESC"
            );

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int idIndex = cursor.getColumnIndex("_id");
                    int addressIndex = cursor.getColumnIndex("address");
                    int bodyIndex = cursor.getColumnIndex("body");
                    int dateIndex = cursor.getColumnIndex("date");

                    if (idIndex >= 0 && addressIndex >= 0 && bodyIndex >= 0 && dateIndex >= 0) {
                        long id = cursor.getLong(idIndex);
                        String address = cursor.getString(addressIndex);
                        String body = cursor.getString(bodyIndex);
                        long date = cursor.getLong(dateIndex);

                        // 중복 방지
                        if (id != lastMessageId && date > lastCheckTime) {
                            lastMessageId = id;
                            lastCheckTime = currentTime;

                            Log.d(TAG, "========================================");
                            Log.d(TAG, "새로운 SMS 감지!");
                            Log.d(TAG, "ID: " + id);
                            Log.d(TAG, "발신번호: " + address);
                            Log.d(TAG, "메시지: " + body);
                            Log.d(TAG, "수신시간: " + date);
                            Log.d(TAG, "========================================");

                            // 데이터베이스에 저장
                            saveSmsToDatabase(address, body, date);

                            // 메인 스레드에서 Toast 표시
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(context,
                                        "SMS 수신됨 (ContentObserver): " + address,
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                } while (cursor.moveToNext());

                cursor.close();
            } else {
                Log.d(TAG, "조회된 SMS 없음");
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS 확인 중 오류: " + e.getMessage(), e);
        }
    }

    /**
     * SMS를 데이터베이스에 저장
     */
    private void saveSmsToDatabase(String phoneNumber, String message, long timestamp) {
        try {
            DatabaseHelper dbHelper = new DatabaseHelper(context);
            long id = dbHelper.insertSmsRecord(phoneNumber, message, timestamp);
            dbHelper.close();

            if (id != -1) {
                Log.d(TAG, "SMS 저장 성공: ID = " + id);

                // MainActivity에 SMS 수신 알림 (명시적 Intent 사용)
                Intent updateIntent = new Intent(MainActivity.ACTION_SMS_RECEIVED);
                updateIntent.setPackage(context.getPackageName());
                context.sendBroadcast(updateIntent);
                Log.d(TAG, "UI 갱신 브로드캐스트 전송됨 (ContentObserver, 패키지: " + context.getPackageName() + ")");

                // REST API로 SMS 기록 전송
                ApiClient.recordSms(phoneNumber, message);
            } else {
                Log.e(TAG, "SMS 저장 실패!");
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS 저장 중 오류: " + e.getMessage(), e);
        }
    }
}
