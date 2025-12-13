package com.example.autocall;

import java.util.HashMap;
import java.util.Map;

/**
 * 최근에 전화를 걸었던 번호를 관리하는 싱글톤 클래스
 */
public class CallManager {
    private static CallManager instance;
    private String lastCalledNumber;
    private Map<String, Long> callHistory; // 전화번호와 통화 시간 매핑
    private static final long SMS_WAIT_TIME = 10 * 60 * 1000; // 10분 (밀리초)

    private CallManager() {
        callHistory = new HashMap<>();
    }

    public static synchronized CallManager getInstance() {
        if (instance == null) {
            instance = new CallManager();
        }
        return instance;
    }

    /**
     * 전화번호 정규화 (숫자만 추출, 국가코드 처리)
     * @param phoneNumber 원본 전화번호
     * @return 정규화된 전화번호 (숫자만, 국가코드 제거)
     */
    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }

        // 숫자만 추출
        String cleaned = phoneNumber.replaceAll("[^0-9]", "");

        // 국가 코드 제거 (한국 +82)
        if (cleaned.startsWith("82") && cleaned.length() > 10) {
            cleaned = "0" + cleaned.substring(2);
        }

        return cleaned;
    }

    /**
     * 두 전화번호가 동일한지 확인 (정규화 후 비교)
     * @param number1 첫 번째 전화번호
     * @param number2 두 번째 전화번호
     * @return 동일하면 true
     */
    private boolean isPhoneNumberMatch(String number1, String number2) {
        if (number1 == null || number2 == null) {
            return false;
        }

        String normalized1 = normalizePhoneNumber(number1);
        String normalized2 = normalizePhoneNumber(number2);

        if (normalized1 == null || normalized2 == null) {
            return false;
        }

        // 완전 일치
        if (normalized1.equals(normalized2)) {
            return true;
        }

        // 뒤 10자리 매칭 (국가 코드 처리 누락 케이스 대비)
        if (normalized1.length() >= 10 && normalized2.length() >= 10) {
            String suffix1 = normalized1.substring(normalized1.length() - 10);
            String suffix2 = normalized2.substring(normalized2.length() - 10);
            return suffix1.equals(suffix2);
        }

        return false;
    }

    public void setLastCalledNumber(String phoneNumber) {
        this.lastCalledNumber = phoneNumber;
        String normalized = normalizePhoneNumber(phoneNumber);
        callHistory.put(normalized, System.currentTimeMillis());
    }

    public String getLastCalledNumber() {
        return lastCalledNumber;
    }

    /**
     * 특정 번호가 최근 통화 목록에 있는지 확인
     * @param phoneNumber 확인할 전화번호
     * @return 최근 통화 목록에 있으면 true
     */
    public boolean isRecentCall(String phoneNumber) {
        if (phoneNumber == null || callHistory.isEmpty()) {
            return false;
        }

        // 정규화하여 매칭
        String normalizedInput = normalizePhoneNumber(phoneNumber);

        for (Map.Entry<String, Long> entry : callHistory.entrySet()) {
            if (isPhoneNumberMatch(phoneNumber, entry.getKey())) {
                // 일정 시간 이내의 통화인지 확인
                long currentTime = System.currentTimeMillis();
                if ((currentTime - entry.getValue()) <= SMS_WAIT_TIME) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 오래된 통화 기록 정리
     */
    public void cleanupOldCalls() {
        long currentTime = System.currentTimeMillis();
        callHistory.entrySet().removeIf(entry ->
            (currentTime - entry.getValue()) > SMS_WAIT_TIME);
    }

    public void clear() {
        lastCalledNumber = null;
        callHistory.clear();
    }
}
