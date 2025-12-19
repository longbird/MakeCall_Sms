package com.example.autocall

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.CallLog
import android.util.Log

/**
 * CallLog 분석 유틸리티
 * 통화 종료 후 CallLog에서 통화 정보를 분석합니다.
 */
object CallLogAnalyzer {
    private const val TAG = "CallLogAnalyzer"

    /**
     * CallLog에서 가져올 수 있는 모든 컬럼 정보
     */
    data class CallLogEntry(
        val id: Long,
        val number: String,
        val type: Int,
        val typeName: String,
        val date: Long,
        val duration: Long,
        val isNew: Int,
        val cachedName: String?,
        val cachedNumberType: Int?,
        val features: Int?,
        val countryIso: String?,
        val geocodedLocation: String?,
        val extras: Map<String, String> = emptyMap()
    )

    /**
     * 통화 유형을 문자열로 변환
     */
    private fun getTypeName(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "INCOMING (수신)"
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING (발신)"
            CallLog.Calls.MISSED_TYPE -> "MISSED (부재중)"
            CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL (음성메일)"
            CallLog.Calls.REJECTED_TYPE -> "REJECTED (거절됨)"
            CallLog.Calls.BLOCKED_TYPE -> "BLOCKED (차단됨)"
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY (외부응답)"
            else -> "UNKNOWN ($type)"
        }
    }

    /**
     * 특정 전화번호의 최근 통화 기록 조회
     */
    fun getRecentCallLog(context: Context, phoneNumber: String, limit: Int = 1): List<CallLogEntry> {
        val results = mutableListOf<CallLogEntry>()

        try {
            // 전화번호 정규화
            val normalizedNumber = normalizePhoneNumber(phoneNumber)

            Log.d(TAG, "========================================")
            Log.d(TAG, "CallLog 조회 시작")
            Log.d(TAG, "검색 번호: $phoneNumber")
            Log.d(TAG, "정규화된 번호: $normalizedNumber")
            Log.d(TAG, "========================================")

            // 조회할 컬럼들
            val projection = mutableListOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NEW,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.CACHED_NUMBER_TYPE,
                CallLog.Calls.COUNTRY_ISO,
                CallLog.Calls.GEOCODED_LOCATION
            )

            // Android 7+ (API 24) 추가 필드
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                projection.add(CallLog.Calls.FEATURES)
            }

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection.toTypedArray(),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                Log.d(TAG, "총 발신 통화 기록 수: ${it.count}")

                // 컬럼 인덱스
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val newIdx = it.getColumnIndex(CallLog.Calls.NEW)
                val cachedNameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val cachedNumberTypeIdx = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val countryIsoIdx = it.getColumnIndex(CallLog.Calls.COUNTRY_ISO)
                val geocodedLocationIdx = it.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)
                val featuresIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    it.getColumnIndex(CallLog.Calls.FEATURES)
                } else -1

                var count = 0
                while (it.moveToNext() && count < limit * 10) { // 더 많이 검색해서 필터링
                    val logNumber = it.getString(numberIdx) ?: ""
                    val normalizedLogNumber = normalizePhoneNumber(logNumber)

                    // 번호 매칭 확인
                    if (isPhoneNumberMatch(normalizedNumber, normalizedLogNumber)) {
                        val entry = CallLogEntry(
                            id = it.getLong(idIdx),
                            number = logNumber,
                            type = it.getInt(typeIdx),
                            typeName = getTypeName(it.getInt(typeIdx)),
                            date = it.getLong(dateIdx),
                            duration = it.getLong(durationIdx),
                            isNew = it.getInt(newIdx),
                            cachedName = if (cachedNameIdx >= 0) it.getString(cachedNameIdx) else null,
                            cachedNumberType = if (cachedNumberTypeIdx >= 0) it.getInt(cachedNumberTypeIdx) else null,
                            features = if (featuresIdx >= 0) it.getInt(featuresIdx) else null,
                            countryIso = if (countryIsoIdx >= 0) it.getString(countryIsoIdx) else null,
                            geocodedLocation = if (geocodedLocationIdx >= 0) it.getString(geocodedLocationIdx) else null
                        )
                        results.add(entry)

                        if (results.size >= limit) break
                    }
                    count++
                }
            }

            Log.d(TAG, "검색된 통화 기록 수: ${results.size}")

        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CALL_LOG 권한이 없습니다: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "CallLog 조회 실패: ${e.message}", e)
        }

        return results
    }

    /**
     * 최근 통화 기록 전체 조회 (디버깅용)
     */
    fun getAllRecentCallLogs(context: Context, limit: Int = 10): List<CallLogEntry> {
        val results = mutableListOf<CallLogEntry>()

        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "전체 CallLog 조회 시작 (최근 $limit 건)")
            Log.d(TAG, "========================================")

            val projection = mutableListOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NEW,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.CACHED_NUMBER_TYPE,
                CallLog.Calls.COUNTRY_ISO,
                CallLog.Calls.GEOCODED_LOCATION
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                projection.add(CallLog.Calls.FEATURES)
            }

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection.toTypedArray(),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                Log.d(TAG, "총 통화 기록 수: ${it.count}")

                // 모든 컬럼명 출력 (디버깅용)
                Log.d(TAG, "사용 가능한 컬럼: ${it.columnNames.joinToString(", ")}")

                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val newIdx = it.getColumnIndex(CallLog.Calls.NEW)
                val cachedNameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val cachedNumberTypeIdx = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val countryIsoIdx = it.getColumnIndex(CallLog.Calls.COUNTRY_ISO)
                val geocodedLocationIdx = it.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)
                val featuresIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    it.getColumnIndex(CallLog.Calls.FEATURES)
                } else -1

                var count = 0
                while (it.moveToNext() && count < limit) {
                    val entry = CallLogEntry(
                        id = it.getLong(idIdx),
                        number = it.getString(numberIdx) ?: "",
                        type = it.getInt(typeIdx),
                        typeName = getTypeName(it.getInt(typeIdx)),
                        date = it.getLong(dateIdx),
                        duration = it.getLong(durationIdx),
                        isNew = it.getInt(newIdx),
                        cachedName = if (cachedNameIdx >= 0) it.getString(cachedNameIdx) else null,
                        cachedNumberType = if (cachedNumberTypeIdx >= 0) it.getInt(cachedNumberTypeIdx) else null,
                        features = if (featuresIdx >= 0) it.getInt(featuresIdx) else null,
                        countryIso = if (countryIsoIdx >= 0) it.getString(countryIsoIdx) else null,
                        geocodedLocation = if (geocodedLocationIdx >= 0) it.getString(geocodedLocationIdx) else null
                    )
                    results.add(entry)
                    count++
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CALL_LOG 권한이 없습니다: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "CallLog 조회 실패: ${e.message}", e)
        }

        return results
    }

    /**
     * 통화 기록을 로그로 출력 (디버깅용)
     */
    fun logCallEntry(entry: CallLogEntry) {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(entry.date))

        Log.d(TAG, "----------------------------------------")
        Log.d(TAG, "ID: ${entry.id}")
        Log.d(TAG, "번호: ${entry.number}")
        Log.d(TAG, "유형: ${entry.typeName}")
        Log.d(TAG, "시간: $dateStr")
        Log.d(TAG, "통화시간: ${entry.duration}초")
        Log.d(TAG, "새 통화: ${entry.isNew}")
        Log.d(TAG, "저장된 이름: ${entry.cachedName ?: "없음"}")
        Log.d(TAG, "번호 유형: ${entry.cachedNumberType}")
        Log.d(TAG, "국가: ${entry.countryIso ?: "없음"}")
        Log.d(TAG, "위치: ${entry.geocodedLocation ?: "없음"}")
        Log.d(TAG, "기능: ${entry.features}")
        Log.d(TAG, "----------------------------------------")
    }

    /**
     * 통화 결과 분석
     * duration과 type을 기반으로 통화 결과를 추정
     */
    fun analyzeCallResult(entry: CallLogEntry): String {
        return when {
            entry.type != CallLog.Calls.OUTGOING_TYPE -> "발신 통화가 아님"
            entry.duration == 0L -> "연결 안 됨 (없는 번호/통화중/무응답 가능성)"
            entry.duration in 1..5 -> "매우 짧은 통화 (즉시 끊김)"
            entry.duration in 6..15 -> "짧은 통화 (안내 멘트 가능성)"
            else -> "정상 통화 (${entry.duration}초)"
        }
    }

    /**
     * 전화번호 정규화
     */
    private fun normalizePhoneNumber(phoneNumber: String): String {
        var normalized = phoneNumber.replace(Regex("[^0-9]"), "")
        if (normalized.startsWith("82")) {
            normalized = "0" + normalized.substring(2)
        }
        return normalized
    }

    /**
     * 전화번호 매칭 확인
     */
    private fun isPhoneNumberMatch(number1: String, number2: String): Boolean {
        if (number1.isEmpty() || number2.isEmpty()) return false

        val suffix1 = if (number1.length >= 8) number1.takeLast(8) else number1
        val suffix2 = if (number2.length >= 8) number2.takeLast(8) else number2

        return suffix1 == suffix2
    }
}
