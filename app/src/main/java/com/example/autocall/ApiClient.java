package com.example.autocall;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.gson.JsonArray;

/**
 * REST API 클라이언트 클래스
 * 전화번호 가져오기 및 기록 전송을 담당
 */
public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // TODO: 실제 API 엔드포인트로 변경 필요
    // 기본 URL (사용자가 입력하지 않았을 때의 대비책, 실제로는 사용자가 입력해야 함)
    private static String BASE_URL = "https://your-api-server.com/api";

    public static void setBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }

        // URL에 스킴이 없으면 http:// 추가
        String processedUrl = url.trim();
        if (!processedUrl.startsWith("http://") && !processedUrl.startsWith("https://")) {
            processedUrl = "http://" + processedUrl;
        }

        // URL 끝에 /가 있으면 제거
        if (processedUrl.endsWith("/")) {
            BASE_URL = processedUrl.substring(0, processedUrl.length() - 1);
        } else {
            BASE_URL = processedUrl;
        }

        Log.d(TAG, "BASE_URL 설정됨: " + BASE_URL);
    }

    private static String getPhoneNumberUrl() {
        return BASE_URL + "/phone-number";
    }

    private static String getPhoneNumbersUrl() {
        return BASE_URL + "/api/phone-numbers";
    }

    private static String getRecordCallUrl() {
        return BASE_URL + "/api/call-record";
    }

    private static String getRecordSmsUrl() {
        return BASE_URL + "/api/sms-record";
    }

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final Gson gson = new Gson();

    // ExecutorService for background tasks (replaces AsyncTask)
    private static final ExecutorService executorService = Executors.newFixedThreadPool(4);

    // Handler for posting results back to main thread
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 전화번호 가져오기
     */
    public interface PhoneNumberCallback {
        void onSuccess(String phoneNumber);

        void onFailure(String error);
    }

    /**
     * 여러 전화번호 가져오기
     */
    public interface PhoneNumbersCallback {
        void onSuccess(List<String> phoneNumbers);

        void onFailure(String error);
    }

    public static void getPhoneNumber(PhoneNumberCallback callback) {
        executorService.execute(() -> {
            String phoneNumber = null;
            try {
                RequestBody body = RequestBody.create("{}", JSON);
                Request request = new Request.Builder()
                        .url(getPhoneNumberUrl())
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();

                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);

                    if (jsonObject.has("phoneNumber")) {
                        phoneNumber = jsonObject.get("phoneNumber").getAsString();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "전화번호 가져오기 실패", e);
            }

            final String result = phoneNumber;
            mainHandler.post(() -> {
                if (result != null && !result.isEmpty()) {
                    callback.onSuccess(result);
                } else {
                    callback.onFailure("전화번호를 가져올 수 없습니다");
                }
            });
        });
    }

    /**
     * 여러 전화번호 가져오기 (최대 limit개)
     * 
     * @param limit    가져올 최대 전화번호 개수
     * @param callback 콜백
     */
    public static void getPhoneNumbers(int limit, PhoneNumbersCallback callback) {
        executorService.execute(() -> {
            List<String> phoneNumbers = null;
            try {
                Log.d(TAG, "URL 설정됨: " + getPhoneNumbersUrl());
                Request request = new Request.Builder()
                        .url(getPhoneNumbersUrl())
                        .get()
                        .build();
                /*
                 * String url = getPhoneNumbersUrl() + "?limit=" + limit;
                 * Request request = new Request.Builder()
                 * .url(url)
                 * .get()
                 * .build();
                 */
                Response response = client.newCall(request).execute();

                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);

                    phoneNumbers = new ArrayList<>();

                    // phones 배열이 있는 경우
                    if (jsonObject.has("phones")) {
                        JsonArray phoneArray = jsonObject.getAsJsonArray("phones");
                        for (int i = 0; i < phoneArray.size() && i < limit; i++) {
                            JsonObject phoneObj = phoneArray.get(i).getAsJsonObject();
                            if (phoneObj.has("phone")) {
                                String phoneNumber = phoneObj.get("phone").getAsString();
                                if (phoneNumber != null && !phoneNumber.isEmpty()) {
                                    phoneNumbers.add(phoneNumber);
                                }
                            }
                        }
                    }

                    if (phoneNumbers.isEmpty()) {
                        phoneNumbers = null;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "전화번호 목록 가져오기 실패", e);
            }

            final List<String> result = phoneNumbers;
            mainHandler.post(() -> {
                if (result != null && !result.isEmpty()) {
                    callback.onSuccess(result);
                } else {
                    callback.onFailure("전화번호를 가져올 수 없습니다");
                }
            });
        });
    }

    /**
     * 전화 기록 전송
     * 
     * @param phoneNumber 전화번호
     * @param status      상태 (started: 전화 걸기 시작, connected: 통화 연결됨, ended: 통화 종료,
     *                    no_answer: 연결안됨, failed: 전화 걸기 실패)
     */
    public static void recordCall(String phoneNumber, String status) {
        executorService.execute(() -> {
            boolean success = false;
            try {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("phoneNumber", phoneNumber);
                jsonObject.addProperty("status", status);
                jsonObject.addProperty("timestamp", System.currentTimeMillis());

                RequestBody body = RequestBody.create(jsonObject.toString(), JSON);
                Request request = new Request.Builder()
                        .url(getRecordCallUrl())
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                success = response.isSuccessful();
            } catch (IOException e) {
                Log.e(TAG, "전화 기록 전송 실패", e);
            }

            final boolean result = success;
            final String phone = phoneNumber;
            final String stat = status;
            mainHandler.post(() -> {
                if (result) {
                    Log.d(TAG, "전화 기록 전송 성공: " + phone + " - " + stat);
                } else {
                    Log.e(TAG, "전화 기록 전송 실패: " + phone + " - " + stat);
                }
            });
        });
    }

    /**
     * SMS 기록 전송
     * 
     * @param phoneNumber 전화번호
     * @param message     SMS 메시지
     * @param callback    완료 시 호출될 콜백 (nullable)
     */
    public static void recordSms(String phoneNumber, String message, Runnable callback) {
        executorService.execute(() -> {
            boolean success = false;
            try {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("phoneNumber", phoneNumber);
                jsonObject.addProperty("message", message);
                jsonObject.addProperty("timestamp", System.currentTimeMillis());

                RequestBody body = RequestBody.create(jsonObject.toString(), JSON);
                Request request = new Request.Builder()
                        .url(getRecordSmsUrl())
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                success = response.isSuccessful();
            } catch (IOException e) {
                Log.e(TAG, "SMS 기록 전송 실패", e);
            }

            final boolean result = success;
            final String phone = phoneNumber;
            mainHandler.post(() -> {
                if (result) {
                    Log.d(TAG, "SMS 기록 전송 성공: " + phone);
                } else {
                    Log.e(TAG, "SMS 기록 전송 실패: " + phone);
                }

                if (callback != null) {
                    callback.run();
                }
            });
        });
    }

    /**
     * SMS 기록 전송 (레거시 지원)
     */
    public static void recordSms(String phoneNumber, String message) {
        recordSms(phoneNumber, message, null);
    }

    /**
     * ExecutorService 종료 (앱 종료 시 호출)
     */
    public static void shutdown() {
        executorService.shutdown();
    }
}
