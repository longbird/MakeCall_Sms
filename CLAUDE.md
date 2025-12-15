# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

AutoCallSmsApp은 자동으로 전화를 걸고 SMS 응답을 기록하는 안드로이드 애플리케이션입니다. REST API 서버에서 전화번호를 가져와 순차적으로 전화를 걸고, 수신된 SMS 메시지를 로컬 SQLite 데이터베이스와 원격 서버 양쪽에 저장합니다.

**패키지명**: `com.example.autocallsms`
**언어**: Kotlin (JVM Target 11)
**Kotlin 버전**: 1.9.20
**최소 SDK**: 24 (Android 7.0)
**타겟 SDK**: 34 (Android 14)

## 빌드 명령어

```bash
# 프로젝트 클린 및 빌드
./gradlew clean build

# 디버그 APK 빌드
./gradlew assembleDebug

# 연결된 기기에 디버그 APK 설치
./gradlew installDebug

# 모든 테스트 실행
./gradlew test

# 계측 테스트 실행 (기기/에뮬레이터 필요)
./gradlew connectedAndroidTest
```

## 개발 환경 설정

1. Android SDK가 설치되어 있고 `local.properties`에 올바른 SDK 경로가 설정되어 있는지 확인
2. Gradle 동기화: `./gradlew build` 또는 Android Studio의 "Sync Project with Gradle Files" 사용
3. 전화 기능이 필요하므로 실제 안드로이드 기기 사용 권장
4. 테스트 시 모든 런타임 권한 허용 필요

## 아키텍처

### 핵심 컴포넌트

**MainActivity** (`MainActivity.kt`)
- 메인 진입점 및 UI 컨트롤러 (Kotlin Activity)
- 전화번호 큐(최대 10개)를 사용한 자동 전화 걸기 워크플로우 관리
- `ApiClient`를 통해 REST API에서 전화번호 가져오기
- `PhoneStateReceiver` 콜백을 통한 통화 생명주기 조정
- `SmsHistoryAdapter`를 사용하여 RecyclerView에 SMS 기록 표시
- **앱 실행 시 자동 시작**: 권한이 허용되면 자동으로 서버 접속 및 전화 걸기 시작

**CallManager** (`CallManager.kt`)
- 최근 전화 걸었던 번호를 추적하는 싱글톤 (`object`)
- 전화번호 정규화: 국가번호(+82) 및 다양한 형식 처리
- 10분 만료 시간(`SMS_WAIT_TIME`)을 가진 통화 기록 유지
- `SmsReceiver`가 SMS가 최근 통화한 번호에서 온 것인지 확인하는데 사용

**PhoneStateReceiver** (`PhoneStateReceiver.kt`)
- 전화 통화 상태(IDLE, RINGING, OFFHOOK) 모니터링 BroadcastReceiver
- 자동 통화 관리 구현:
  - **수신 전화 자동 거부** (RINGING 상태 감지 시)
  - 동적 타이머 설정 (기본값: 전화 시도 30초, 연결 후 대기 10초)
  - 18초 후 실제 통화 연결 확인 (통신사 안내 멘트 약 15초)
  - 연결 확인 후 설정 시간만큼 대기 후 자동 종료
  - `OnCallEndedListener` 호출하여 큐의 다음 전화 트리거
- 세분화된 통화 상태 기록:
  - `started`: 전화 걸기 시작
  - `dialing`: OFFHOOK 상태 (다이얼링 시작)
  - `connected`: 실제 통화 연결됨 (18초 이상 지속, 명시적 확인)
  - `ended`: 통화 종료 (OFFHOOK 도달 후 종료)
  - `rejected`: 전화 받지 않음 (OFFHOOK 도달 실패, 30초 타임아웃)
  - `failed`: 전화 걸기 실패
- `ApiClient`를 통해 서버에 통화 상태 기록
- **중요**: 자동 거부/끊기는 `ANSWER_PHONE_CALLS` 권한이 필요하며, Android 9+ 에서는 기본 전화 앱이 아니면 작동하지 않을 수 있음

**SmsReceiver** (`SmsReceiver.kt`)
- 수신 SMS 메시지용 BroadcastReceiver
- `DatabaseHelper`를 통해 모든 수신 SMS를 로컬 데이터베이스에 저장
- `ApiClient`를 통해 원격 서버에 SMS 기록 전송
- 최근 통화 필터링 제거됨 - 모든 SMS 저장

**ApiClient** (`ApiClient.kt`)
- 네트워크 작업용 OkHttp3 기반 REST API 클라이언트 (`object` 싱글톤)
- Base URL은 사용자 입력을 통해 `setBaseUrl()`로 설정 가능
- 주요 엔드포인트:
  - `GET /api/phone-numbers` - 전화번호 가져오기 (`phones` 배열이 포함된 JSON 반환)
  - `POST /api/call-record` - 통화 상태 기록 (started, dialing, connected, ended, rejected, failed)
  - `POST /api/sms-record` - 수신 SMS 기록
  - `POST /api/reset-number` - 전화번호 리셋 (종료 시간 도달 시)
- 백그라운드 작업용 ExecutorService, 메인 스레드 콜백용 Handler 사용

**DatabaseHelper** (`DatabaseHelper.kt`)
- SQLite 데이터베이스 관리자 (`AutoCallSms.db`)
- 단일 테이블: `sms_records` (컬럼: id, phone_number, message, timestamp, created_at)
- SMS 레코드의 CRUD 작업 제공
- 타임스탬프 기준 정렬 (최신순)
- Kotlin의 `use` 확장 함수로 리소스 관리

**SmsRecord** (`SmsRecord.kt`)
- SMS 레코드 데이터 클래스 (`data class`)
- 자동 생성된 getter/setter, `toString()`, `equals()`, `hashCode()`, `copy()`

### 데이터 흐름

1. **자동 전화 걸기 프로세스**:
   - 사용자가 서버 주소 입력 후 "시작" 버튼 클릭
   - `MainActivity`가 `ApiClient.getPhoneNumbers()` 호출하여 최대 10개 번호 가져오기
   - 번호들이 `phoneNumberQueue`에 추가되고 첫 번째 전화 시작
   - 각 전화마다: `MainActivity.makePhoneCall()` → `CallManager`와 `PhoneStateReceiver`에 등록
   - `PhoneStateReceiver`가 통화 상태 모니터링, 20초 후 자동 끊기 트리거
   - 통화 종료(IDLE 상태)시 `PhoneStateReceiver.OnCallEndedListener` 콜백이 다음 전화 트리거
   - 큐가 비면 서버에서 추가 번호 가져오기
   - 서버가 빈 목록 반환할 때까지 프로세스 계속

2. **SMS 수신**:
   - `SmsReceiver`가 SMS 브로드캐스트 수신
   - `DatabaseHelper`를 통해 로컬 데이터베이스에 즉시 저장
   - `ApiClient.recordSms()`를 통해 원격 서버에 전송
   - `MainActivity.onResume()`에서 데이터베이스로부터 UI 새로고침

3. **전화번호 매칭**:
   - 숫자가 아닌 문자 제거 및 +82 국가번호 처리를 위한 정규화
   - 형식 변형 처리를 위해 마지막 10자리 사용
   - `CallManager.normalizePhoneNumber()`와 `SmsReceiver.isPhoneNumberMatch()`에 구현

## 주요 의존성

```gradle
// Kotlin
org.jetbrains.kotlin:kotlin-stdlib:1.9.20
androidx.core:core-ktx:1.12.0

// UI
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.recyclerview:recyclerview:1.3.2

// 네트워킹
com.squareup.okhttp3:okhttp:4.12.0
com.google.code.gson:gson:2.10.1
```

## 필요 권한

- `CALL_PHONE` - 발신 전화
- `READ_PHONE_STATE` - 통화 상태 모니터링
- `RECEIVE_SMS` / `READ_SMS` - SMS 수신 및 읽기
- `ANSWER_PHONE_CALLS` - 자동 전화 끊기 (Android 9+, 기본 전화 앱이 아니면 작동 안 할 수 있음)
- `INTERNET` / `ACCESS_NETWORK_STATE` - REST API 통신

모든 권한은 `MainActivity.checkAndRequestPermissions()`에서 런타임에 요청됨.

## REST API 규약

앱은 다음 엔드포인트를 구현하는 서버를 필요로 합니다:

```
GET /api/phone-numbers
응답: { "phones": [{ "phone": "01012345678" }, ...] }

POST /api/call-record
본문: {
  "phoneNumber": "01012345678",
  "status": "started|dialing|connected|ended|invalid_number|busy|no_answer|failed",
  "timestamp": 1234567890
}

통화 상태 설명:
- started: 전화 걸기 시작
- dialing: 다이얼링 시작 (OFFHOOK 도달)
- connected: 실제 통화 연결 (12초 이상 지속)
- ended: 정상 통화 종료
- invalid_number: 없는 번호/통신사 안내 (12초 이내 종료)
- busy: 통화 중/기타 (12초~30초 사이)
- no_answer: 연결 실패 (OFFHOOK 미도달)
- failed: 전화 걸기 실패 (Exception)

참고: 12초 기준은 통신사 안내 멘트(약 5~10초)와 실제 통화를 구분하기 위함

POST /api/sms-record
본문: { "phoneNumber": "01012345678", "message": "SMS 내용", "timestamp": 1234567890 }
```

서버 주소는 UI에서 설정 (예: `http://192.168.1.100:8080`). 네트워크 보안 설정(`network_security_config`)은 평문 HTTP 트래픽 허용.

## Kotlin 주요 특징

프로젝트는 Kotlin으로 작성되어 다음과 같은 이점을 제공합니다:

### Null 안전성
- Nullable 타입(`String?`)과 Non-null 타입(`String`) 명시적 구분
- Safe call(`?.`), Elvis operator(`?:`), non-null assertion(`!!`) 사용
- NullPointerException 위험 감소

### 간결한 코드
- Data class로 boilerplate 코드 제거 (SmsRecord)
- Object 선언으로 싱글톤 패턴 간소화 (CallManager, ApiClient)
- 람다 및 고차 함수로 콜백 처리 간소화
- Extension function 및 property 활용

### 타입 추론 및 스마트 캐스트
- 변수 타입 자동 추론 (`val`, `var`)
- 조건문 후 자동 캐스팅
- When 표현식으로 복잡한 조건문 간소화

### 코루틴 호환성
- 현재는 ExecutorService 사용
- 향후 Kotlin Coroutines로 마이그레이션 가능

## 중요 사항

- 이 앱은 자동으로 전화를 걸고 통화를 모니터링합니다 - 적절한 권한을 가지고만 사용할 것
- **앱 실행 시 자동 시작**: 권한이 허용되면 1초 후 자동으로 서버 접속 및 전화 걸기 시작
- `CallManager`의 10분 SMS 대기 시간은 `SMS_WAIT_TIME` 상수로 조정 가능
- 자동 끊기 기능은 Android 9+ 에서 앱이 기본 전화 앱으로 설정되지 않으면 작동하지 않을 수 있음
- 에뮬레이터는 전화 하드웨어가 없으므로 실제 기기로 테스트 필요
- 데이터베이스는 로컬에 저장됨; 필요시 내보내기/백업 기능 추가 고려
- 현재 앱은 전화 걸었던 번호뿐 아니라 모든 수신 SMS를 저장함
- 기본 서버 주소: `192.168.0.210:8080` (MainActivity에서 변경 가능)
