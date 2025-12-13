package com.example.autocall

/**
 * 최근에 전화를 걸었던 번호를 관리하는 싱글톤 클래스
 */
object CallManager {
    private var lastCalledNumber: String? = null
    private val callHistory = mutableMapOf<String, Long>() // 전화번호와 통화 시간 매핑
    private const val SMS_WAIT_TIME = 10 * 60 * 1000L // 10분 (밀리초)

    /**
     * 전화번호 정규화 (숫자만 추출, 국가코드 처리)
     * @param phoneNumber 원본 전화번호
     * @return 정규화된 전화번호 (숫자만, 국가코드 제거)
     */
    private fun normalizePhoneNumber(phoneNumber: String?): String? {
        if (phoneNumber == null) {
            return null
        }

        // 숫자만 추출
        var cleaned = phoneNumber.replace(Regex("[^0-9]"), "")

        // 국가 코드 제거 (한국 +82)
        if (cleaned.startsWith("82") && cleaned.length > 10) {
            cleaned = "0" + cleaned.substring(2)
        }

        return cleaned
    }

    /**
     * 두 전화번호가 동일한지 확인 (정규화 후 비교)
     * @param number1 첫 번째 전화번호
     * @param number2 두 번째 전화번호
     * @return 동일하면 true
     */
    private fun isPhoneNumberMatch(number1: String?, number2: String?): Boolean {
        if (number1 == null || number2 == null) {
            return false
        }

        val normalized1 = normalizePhoneNumber(number1)
        val normalized2 = normalizePhoneNumber(number2)

        if (normalized1 == null || normalized2 == null) {
            return false
        }

        // 완전 일치
        if (normalized1 == normalized2) {
            return true
        }

        // 뒤 10자리 매칭 (국가 코드 처리 누락 케이스 대비)
        if (normalized1.length >= 10 && normalized2.length >= 10) {
            val suffix1 = normalized1.substring(normalized1.length - 10)
            val suffix2 = normalized2.substring(normalized2.length - 10)
            return suffix1 == suffix2
        }

        return false
    }

    fun setLastCalledNumber(phoneNumber: String) {
        lastCalledNumber = phoneNumber
        val normalized = normalizePhoneNumber(phoneNumber)
        if (normalized != null) {
            callHistory[normalized] = System.currentTimeMillis()
        }
    }

    fun getLastCalledNumber(): String? {
        return lastCalledNumber
    }

    /**
     * 특정 번호가 최근 통화 목록에 있는지 확인
     * @param phoneNumber 확인할 전화번호
     * @return 최근 통화 목록에 있으면 true
     */
    fun isRecentCall(phoneNumber: String?): Boolean {
        if (phoneNumber == null || callHistory.isEmpty()) {
            return false
        }

        // 정규화하여 매칭
        val normalizedInput = normalizePhoneNumber(phoneNumber)

        for ((key, timestamp) in callHistory) {
            if (isPhoneNumberMatch(phoneNumber, key)) {
                // 일정 시간 이내의 통화인지 확인
                val currentTime = System.currentTimeMillis()
                if ((currentTime - timestamp) <= SMS_WAIT_TIME) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 오래된 통화 기록 정리
     */
    fun cleanupOldCalls() {
        val currentTime = System.currentTimeMillis()
        callHistory.entries.removeIf { (_, timestamp) ->
            (currentTime - timestamp) > SMS_WAIT_TIME
        }
    }

    fun clear() {
        lastCalledNumber = null
        callHistory.clear()
    }
}
