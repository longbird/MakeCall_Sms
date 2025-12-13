# 앱 아키텍처 및 로직 검토 보고서

`MakeCall_Sms` 애플리케이션의 코드를 분석한 결과, 다음과 같은 문제점과 잠재적인 이슈가 확인되었습니다.

## 1. 중요한 로직 문제

### A. 통화 상태 감지 (`OFFHOOK` ≠ 통화 연결됨)
**위치:** `PhoneStateReceiver.java`
**문제점:**
앱은 `TelephonyManager.EXTRA_STATE_OFFHOOK` 상태를 통화가 "연결된" 시점으로 판단하고 있습니다.
- **실제 동작:** `OFFHOOK`은 다이얼링이 시작되는 순간(수화기를 든 상태) 바로 발생하며, 상대방이 전화를 받았을 때 발생하는 것이 **아닙니다**.
- **영향:**
    1. **3초 자동 종료 타이머**가 신호음이 울리는 동안 이미 시작됩니다. 연결에 4초가 걸린다면, 상대방이 받기도 전에 전화가 끊어질 수 있습니다.
    2. **10초 연결 대기 타임아웃** 또한 `OFFHOOK`이 다이얼링 직후 발생하므로 사실상 무용지물입니다.

**권장 사항:**
안드로이드에서 실제 "통화 연결(Answered)" 상태를 감지하는 것은 개인정보 보호 정책으로 인해 어렵습니다.
- **옵션 1 (고급):** `AccessibilityService`를 사용하여 화면의 UI 텍스트(예: "00:01")를 읽어옵니다.
- **옵션 2 (API 31+):** `READ_PRECISE_PHONE_STATE` 권한을 요청합니다 (특수 권한 필요).
- **옵션 3 (타협안):** 3초 지연 시간을 신호 가는 시간까지 포함하여 충분히 늘립니다 (예: 20-30초). 단, 이는 "연결 후 3초"라는 요구사항과는 다릅니다.

### B. SMS 매칭 로직
**위치:** `SmsReceiver.java`
**문제점:**
수신된 SMS를 `CallManager.getInstance().getLastCalledNumber()`와 비교하고 있습니다.
```java
if (lastCalledNumber != null && isPhoneNumberMatch(phoneNumber, lastCalledNumber))
```
- **시나리오:** 사용자 A에게 전화를 걸고, 바로 사용자 B에게 전화를 겁니다. 이때 사용자 A가 SMS로 답장을 보냅니다.
- **결과:** `getLastCalledNumber()`는 사용자 B를 반환합니다. 사용자 A의 SMS는 *가장 마지막* 번호와 일치하지 않으므로 **무시됩니다**.
- **영향:** 10개의 전화번호 배치 작업 중, 가장 마지막 번호가 아닌 다른 번호에서 온 유효한 SMS 응답을 놓치게 됩니다.

**권장 사항:**
마지막 번호만 확인하는 대신 `CallManager.isRecentCall(phoneNumber)`를 사용하세요. `CallManager`에는 이미 이를 지원하는 `callHistory` 맵이 있습니다.

## 2. API 호환성 문제

### A. 프로그래밍 방식으로 전화 끊기
**위치:** `PhoneStateReceiver.java` -> `disconnectCall()`
**문제점:**
Java Reflection을 사용하여 `ITelephony.endCall()`을 호출하고 있습니다:
```java
Method endCallMethod = telephonyClass.getDeclaredMethod("endCall");
```
- **실제 동작:** 이 방식은 안드로이드 9 (API 28) 이상에서 **차단**되었습니다. `SecurityException`이 발생하거나 조용히 실패합니다.
- **영향:** 최신 기기에서는 앱이 **전화를 끊을 수 없습니다**. 사용자가 수동으로 끊거나 상대방이 끊을 때까지 통화가 계속됩니다.

**권장 사항:**
- **옵션 1:** `TelecomManager`를 사용합니다 (앱이 **기본 전화 앱(Default Dialer)**으로 설정되어야 함).
- **옵션 2:** `AccessibilityService`를 사용하여 전역 "뒤로 가기" 또는 "통화 종료" 버튼 클릭을 수행합니다 (사용자가 접근성 권한을 허용해야 함).
- **옵션 3:** 자동 종료 기능을 포기하고 사용자 수동 종료에 의존합니다.

## 3. 기타 문제

### A. 10초 타임아웃 구현
**위치:** `PhoneStateReceiver.java`
**문제점:**
10초 타임아웃 발생 시 "no_answer"로 기록만 하고, 전화를 취소하거나 끊으려는 시도를 하지 않습니다.
- **영향:** 앱이 "응답 없음"으로 처리한 후에도 전화벨은 계속 울리게 됩니다.

### B. API 블로킹
**위치:** `ApiClient.java`
**문제점:**
`AsyncTask` (deprecated) 및 동기식 `execute()` 호출을 사용하고 있습니다.
- **영향:** `AsyncTask`가 백그라운드 스레드에서 실행되긴 하지만, 구식 구현 방식입니다. 현재 작동은 하겠지만, 더 나은 안정성과 최신 아키텍처를 위해 `Retrofit` + `Coroutines` 또는 `RxJava`로 마이그레이션하는 것이 좋습니다.

## 수정 필요 사항 요약
1.  **SMS 로직 수정:** `SmsReceiver`가 `CallManager.isRecentCall()`을 사용하도록 변경.
2.  **전화 종료 문제 해결:** 안드로이드 9+ 지원을 위해 전화 종료 전략 결정 (기본 전화 앱 또는 접근성 서비스).
3.  **상태 로직 개선:** `OFFHOOK`의 한계를 인지하고 "3초" 로직을 조정 (예: 타이머 시작 시점을 늦추거나 다른 감지 방식 사용).
