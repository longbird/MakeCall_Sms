package com.example.autocall

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * REST API 클라이언트 클래스
 * 전화번호 가져오기 및 기록 전송을 담당
 */
object ApiClient {
    private const val TAG = "ApiClient"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 기본 URL (사용자가 입력하지 않았을 때의 대비책, 실제로는 사용자가 입력해야 함)
    private var BASE_URL = "https://your-api-server.com/api"

    fun setBaseUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            return
        }

        // URL에 스킴이 없으면 http:// 추가
        var processedUrl = url.trim()
        if (!processedUrl.startsWith("http://") && !processedUrl.startsWith("https://")) {
            processedUrl = "http://$processedUrl"
        }

        // URL 끝에 /가 있으면 제거
        BASE_URL = if (processedUrl.endsWith("/")) {
            processedUrl.substring(0, processedUrl.length - 1)
        } else {
            processedUrl
        }

        Log.d(TAG, "BASE_URL 설정됨: $BASE_URL")
    }

    private fun getPhoneNumberUrl() = "$BASE_URL/phone-number"
    private fun getPhoneNumbersUrl() = "$BASE_URL/api/phone-numbers"
    private fun getRecordCallUrl() = "$BASE_URL/api/call-record"
    private fun getRecordSmsUrl() = "$BASE_URL/api/sms-record"

    // HTTP 로깅 인터셉터 설정
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d(TAG, "HTTP: $message")
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ExecutorService for background tasks (replaces AsyncTask)
    private val executorService = Executors.newFixedThreadPool(4)

    // Handler for posting results back to main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 전화번호 가져오기 콜백
     */
    interface PhoneNumberCallback {
        fun onSuccess(phoneNumber: String)
        fun onFailure(error: String)
    }

    /**
     * 여러 전화번호 가져오기 콜백
     */
    interface PhoneNumbersCallback {
        fun onSuccess(phoneNumbers: List<String>)
        fun onFailure(error: String)
    }

    fun getPhoneNumber(callback: PhoneNumberCallback) {
        executorService.execute {
            var phoneNumber: String? = null
            try {
                val body = "{}".toRequestBody(JSON)
                val request = Request.Builder()
                    .url(getPhoneNumberUrl())
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val responseBody = response.body!!.string()
                        val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)

                        if (jsonObject.has("phoneNumber")) {
                            phoneNumber = jsonObject.get("phoneNumber").asString
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "전화번호 가져오기 실패", e)
            }

            val result = phoneNumber
            mainHandler.post {
                if (!result.isNullOrEmpty()) {
                    callback.onSuccess(result)
                } else {
                    callback.onFailure("전화번호를 가져올 수 없습니다")
                }
            }
        }
    }

    /**
     * 여러 전화번호 가져오기 (최대 limit개)
     *
     * @param limit    가져올 최대 전화번호 개수
     * @param callback 콜백
     */
    fun getPhoneNumbers(limit: Int, callback: PhoneNumbersCallback) {
        executorService.execute {
            var phoneNumbers: MutableList<String>? = null
            var errorMessage: String? = null

            try {
                val url = getPhoneNumbersUrl()
                Log.d(TAG, "========================================")
                Log.d(TAG, "전화번호 가져오기 시작")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "BASE_URL: $BASE_URL")
                Log.d(TAG, "Limit: $limit")
                Log.d(TAG, "========================================")

                // 네트워크 연결 진단
                try {
                    val urlObj = java.net.URL(url)
                    val host = urlObj.host
                    val port = urlObj.port
                    Log.d(TAG, "연결 테스트: host=$host, port=$port")

                    // 소켓 연결 테스트
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(host, port), 5000)
                    socket.close()
                    Log.d(TAG, "직접 소켓 연결 성공!")
                } catch (e: Exception) {
                    Log.e(TAG, "직접 소켓 연결 실패: ${e.javaClass.simpleName} - ${e.message}")
                    e.printStackTrace()
                }

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                Log.d(TAG, "OkHttp 요청 전송 중...")

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "응답 수신: code=${response.code}, message=${response.message}")
                    Log.d(TAG, "응답 성공 여부: ${response.isSuccessful}")

                    if (response.isSuccessful && response.body != null) {
                        val responseBody = response.body!!.string()
                        Log.d(TAG, "응답 본문: $responseBody")

                        val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)

                        phoneNumbers = mutableListOf()

                        // phones 배열이 있는 경우
                        if (jsonObject.has("phones")) {
                            val phoneArray = jsonObject.getAsJsonArray("phones")
                            Log.d(TAG, "phones 배열 크기: ${phoneArray.size()}")

                            for (i in 0 until minOf(phoneArray.size(), limit)) {
                                val phoneObj = phoneArray[i].asJsonObject
                                if (phoneObj.has("phone")) {
                                    val phoneNumber = phoneObj.get("phone").asString
                                    if (!phoneNumber.isNullOrEmpty()) {
                                        phoneNumbers!!.add(phoneNumber)
                                        Log.d(TAG, "전화번호 추가: $phoneNumber")
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "응답에 'phones' 배열이 없음")
                        }

                        if (phoneNumbers!!.isEmpty()) {
                            Log.w(TAG, "가져온 전화번호가 없음")
                            phoneNumbers = null
                        } else {
                            Log.d(TAG, "총 ${phoneNumbers!!.size}개 전화번호 가져옴")
                        }
                    } else {
                        errorMessage = "서버 응답 실패: ${response.code} ${response.message}"
                        Log.e(TAG, errorMessage!!)
                    }
                }
            } catch (e: IOException) {
                errorMessage = "네트워크 오류: ${e.message}"
                Log.e(TAG, "전화번호 목록 가져오기 실패", e)
                Log.e(TAG, "오류 타입: ${e.javaClass.simpleName}")
                Log.e(TAG, "오류 메시지: ${e.message}")
                e.cause?.let {
                    Log.e(TAG, "원인: ${it.javaClass.simpleName} - ${it.message}")
                }
            } catch (e: Exception) {
                errorMessage = "예상치 못한 오류: ${e.message}"
                Log.e(TAG, "예상치 못한 오류 발생", e)
            }

            val result = phoneNumbers
            val error = errorMessage
            mainHandler.post {
                if (!result.isNullOrEmpty()) {
                    Log.d(TAG, "콜백 성공: ${result.size}개 전화번호")
                    callback.onSuccess(result)
                } else {
                    val finalError = error ?: "전화번호를 가져올 수 없습니다"
                    Log.e(TAG, "콜백 실패: $finalError")
                    callback.onFailure(finalError)
                }
            }
        }
    }

    /**
     * 전화 기록 전송
     *
     * @param phoneNumber 전화번호
     * @param status      상태 (started: 전화 걸기 시작, connected: 통화 연결됨, ended: 통화 종료,
     *                    no_answer: 연결안됨, failed: 전화 걸기 실패)
     */
    fun recordCall(phoneNumber: String, status: String) {
        executorService.execute {
            var success = false
            try {
                val jsonObject = JsonObject().apply {
                    addProperty("phoneNumber", phoneNumber)
                    addProperty("status", status)
                    addProperty("timestamp", System.currentTimeMillis())
                }

                val body = jsonObject.toString().toRequestBody(JSON)
                val request = Request.Builder()
                    .url(getRecordCallUrl())
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    success = response.isSuccessful
                }
            } catch (e: IOException) {
                Log.e(TAG, "전화 기록 전송 실패", e)
            }

            val result = success
            val phone = phoneNumber
            val stat = status
            mainHandler.post {
                if (result) {
                    Log.d(TAG, "전화 기록 전송 성공: $phone - $stat")
                } else {
                    Log.e(TAG, "전화 기록 전송 실패: $phone - $stat")
                }
            }
        }
    }

    /**
     * SMS 기록 전송
     *
     * @param phoneNumber 전화번호
     * @param message     SMS 메시지
     * @param callback    완료 시 호출될 콜백 (nullable)
     */
    @JvmOverloads
    fun recordSms(phoneNumber: String, message: String, callback: Runnable? = null) {
        executorService.execute {
            var success = false
            try {
                val jsonObject = JsonObject().apply {
                    addProperty("phoneNumber", phoneNumber)
                    addProperty("message", message)
                    addProperty("timestamp", System.currentTimeMillis())
                }

                val body = jsonObject.toString().toRequestBody(JSON)
                val request = Request.Builder()
                    .url(getRecordSmsUrl())
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    success = response.isSuccessful
                }
            } catch (e: IOException) {
                Log.e(TAG, "SMS 기록 전송 실패", e)
            }

            val result = success
            val phone = phoneNumber
            mainHandler.post {
                if (result) {
                    Log.d(TAG, "SMS 기록 전송 성공: $phone")
                } else {
                    Log.e(TAG, "SMS 기록 전송 실패: $phone")
                }

                callback?.run()
            }
        }
    }

    /**
     * ExecutorService 종료 (앱 종료 시 호출)
     */
    fun shutdown() {
        executorService.shutdown()
    }
}
