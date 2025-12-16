# 변경 이력 (Changelog)

## 2025-12-15 주요 업데이트

### 1. 통화 상태 기록 개선

#### OFFHOOK 즉시 connected 기록
- **문제**: 전화를 받았는데 18초 연결 확인 전에 끊으면 rejected로 잘못 기록됨
- **해결**: OFFHOOK 상태 도달 시 즉시 `connected` 상태 기록
- **변경 파일**: `PhoneStateReceiver.kt`
- **관련 커밋**: `fd6d527`

**변경 전:**
```kotlin
// 18초 후 연결 확인
connectionCheckRunnable = Runnable {
    connectedTime = System.currentTimeMillis()
    ApiClient.recordCall(currentPhoneNumber!!, "connected")
}
handler.postDelayed(connectionCheckRunnable!!, 18000L)
```

**변경 후:**
```kotlin
// OFFHOOK 상태 = 통화 연결됨
connectedTime = System.currentTimeMillis()
isCallConnected = true
currentPhoneNumber?.let {
    ApiClient.recordCall(it, "connected")
}
```

#### 통화 상태 흐름
1. `started` - 전화 걸기 시작
2. `connected` - OFFHOOK 도달 (즉시 기록)
3. `ended` - 통화 종료 (OFFHOOK 도달한 경우)
4. `rejected` - 전화 연결 안 됨 (OFFHOOK 미도달)

### 2. MMS 수신 기능 추가

#### MMS ContentObserver 구현
- **파일**: `SmsContentObserver.kt`
- **관련 커밋**: `03ea21c`, `652fbd6`, `65c542a`

**주요 기능:**
- `content://mms/inbox` 모니터링
- MMS 발신번호 추출 (`content://mms/{id}/addr`)
- MMS 텍스트 내용 추출 (`content://mms/part`)
- SMS와 동일한 데이터베이스에 저장

**MMS Address Types (PDU 표준):**
```kotlin
137 (0x89) = FROM (발신자) ✓ 사용
151 (0x97) = TO (수신자)   ✗ 제외
130 (0x82) = CC            ✗ 제외
129 (0x81) = BCC           ✗ 제외
```

**MMS 처리 흐름:**
```
1. content://mms/inbox에서 수신 MMS 감지 (m_type = 132)
2. content://mms/{id}/addr에서 발신번호 조회 (type 137)
3. content://mms/part에서 text/plain 타입 추출
4. 데이터베이스 및 서버에 저장
```

**구현된 메서드:**
- `checkNewMms()` - MMS inbox 모니터링
- `getMmsAddress(mmsId)` - 발신번호 추출
- `getMmsText(mmsId)` - 텍스트 내용 추출
- `readMmsPartText(partId)` - part에서 텍스트 읽기

#### MainActivity MMS 지원
- `content://mms/inbox` URI 모니터링 추가
- SMS/MMS 통합 표시

### 3. UI 개선

#### 진행 상황 표시 추가
- **파일**: `activity_main.xml`, `MainActivity.kt`
- **관련 커밋**: `9aa7985`, `b95a2ef`

**추가된 UI 요소:**
```xml
<MaterialCardView id="statusCard">
    <TextView id="tvStatus" />      <!-- 상태 텍스트 -->
    <TextView id="tvProgress" />    <!-- 진행: 3/10 -->
    <TextView id="tvCurrentNumber" /> <!-- 현재 전화번호 -->
    <ProgressBar id="progressBar" /> <!-- 진행률 바 -->
</MaterialCardView>
```

**표시되는 상태:**
- "전화번호 가져오는 중..."
- "전화 걸기" + 진행률 + 현재 번호
- "작업 완료"
- "종료됨"
- 실제 오류 메시지 (예: "전화번호를 가져올 수 없습니다")

**진행 상황 추적:**
```kotlin
private var totalPhoneNumbersProcessed = 0  // 처리한 개수
private var currentBatchSize = 0            // 현재 배치 크기

updateStatus("전화 걸기", 3, 10, "01012345678")
// 표시: "전화 걸기" / "진행: 3/10  01012345678" / 프로그레스바 30%
```

#### UI 단순화 및 스크롤 추가
- **관련 커밋**: `ea80ae0`, `fa9caed`

**주요 변경:**
1. ScrollView로 전체 레이아웃 감싸기 (하단 내용 스크롤 가능)
2. 여백 축소 (padding: 16dp→12dp, marginBottom: 24dp→8dp)
3. 텍스트 크기 축소 (제목: 24sp→20sp, 버튼: 18sp→16sp)
4. 진행 상황 카드 컴팩트화
   - padding: 16dp→8dp
   - 진행률과 전화번호를 가로로 배치
   - ProgressBar 높이: wrap_content→4dp
5. RecyclerView 고정 높이 (300dp)

**레이아웃 최적화:**
```
이전 (세로 배치, 큰 여백):
┌──────────────────┐
│ 전화 걸기 (16sp)  │
│                  │
│ 진행: 3/10       │
│ 현재: 01034623453│
│ ▓▓▓░░░░░░░ (8dp) │
└──────────────────┘

수정 후 (가로 배치, 작은 여백):
┌──────────────────┐
│ 전화 걸기 (14sp)  │
│ 진행: 3/10  01034623453
│ ▓▓▓░░░░░░░ (4dp) │
└──────────────────┘
```

#### Material Components 테마 적용
- **문제**: MaterialCardView inflate 오류
- **해결**: Theme.AppCompat → Theme.MaterialComponents
- **파일**: `AndroidManifest.xml`
- **관련 커밋**: `a05c238`

```xml
<!-- 변경 전 -->
android:theme="@style/Theme.AppCompat.Light.DarkActionBar"

<!-- 변경 후 -->
android:theme="@style/Theme.MaterialComponents.Light.DarkActionBar"
```

### 4. 코드 품질 개선

#### Import 구문 정리
- `TextView`, `ProgressBar` import 추가
- **관련 커밋**: `b95a2ef`

#### 오류 메시지 개선
- 진행 상황에 실제 오류 메시지 표시
- "오류 발생" → "전화번호를 가져올 수 없습니다" 등 구체적 메시지
- **관련 커밋**: `2cc17f5`

## 기술 스택

### 프로그래밍 언어
- **Kotlin** 1.9.20
- JVM Target 11

### Android
- Min SDK: 24 (Android 7.0)
- Target SDK: 34 (Android 14)
- Compile SDK: 34

### 주요 라이브러리
```gradle
// UI
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.recyclerview:recyclerview:1.3.2

// 네트워킹
com.squareup.okhttp3:okhttp:4.12.0
com.squareup.okhttp3:logging-interceptor:4.12.0
com.google.code.gson:gson:2.10.1
```

### 필요 권한
```xml
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 아키텍처

### 핵심 컴포넌트

**PhoneStateReceiver.kt**
- 전화 상태 모니터링 (IDLE, RINGING, OFFHOOK)
- OFFHOOK 도달 시 즉시 connected 기록
- 자동 전화 끊기 (설정 가능한 타이머)
- 수신 전화 자동 거부
- 통화 종료 리스너 콜백

**SmsContentObserver.kt**
- SMS/MMS ContentObserver
- `content://sms/inbox` 모니터링
- `content://mms/inbox` 모니터링 (새로 추가)
- MMS 발신번호 및 텍스트 추출
- 데이터베이스 및 서버 동기화

**MainActivity.kt**
- 자동 전화 걸기 워크플로우
- 진행 상황 표시 UI 관리
- 전화번호 큐 관리 (최대 10개)
- 종료 시간 체크
- SMS/MMS 히스토리 표시

**ApiClient.kt**
- REST API 클라이언트 (OkHttp3)
- 전화번호 가져오기 (`GET /api/phone-numbers`)
- 통화 기록 전송 (`POST /api/call-record`)
- SMS 기록 전송 (`POST /api/sms-record`)
- 전화번호 리셋 (`POST /api/reset-number`)

**DatabaseHelper.kt**
- SQLite 데이터베이스 관리
- SMS/MMS 레코드 저장 (통합)
- CRUD 작업 제공

### 데이터 흐름

**자동 전화 걸기:**
```
1. 시작 버튼 클릭
   ↓
2. 서버에서 전화번호 가져오기 (최대 10개)
   ↓
3. phoneNumberQueue에 추가
   ↓
4. 첫 번째 전화 걸기
   ↓
5. PhoneStateReceiver 모니터링
   - OFFHOOK 도달 → connected 기록
   - 설정 시간 후 자동 끊기
   ↓
6. 통화 종료 → OnCallEndedListener 콜백
   ↓
7. 다음 전화 걸기 (큐가 빌 때까지 반복)
   ↓
8. 큐가 비면 서버에서 추가 번호 가져오기
   ↓
9. 서버가 빈 목록 반환하면 종료
```

**SMS/MMS 수신:**
```
1. SmsReceiver (SMS만) 또는 SmsContentObserver (SMS/MMS)
   ↓
2. 로컬 데이터베이스 저장
   ↓
3. 서버로 전송 (ApiClient.recordSms)
   ↓
4. MainActivity UI 갱신 (BroadcastReceiver)
```

## 테스트 방법

### 1. 전화 걸기 테스트
```
1. 서버 주소 입력
2. 타이머 설정 (전화 시도 시간, 연결 후 대기)
3. 시작 버튼 클릭
4. 진행 상황 카드에서 실시간 상태 확인
5. Logcat 확인:
   adb logcat -s PhoneStateReceiver:D
```

**예상 로그:**
```
OFFHOOK 상태 (통화 연결됨): 01012345678
✓ 정상 통화 종료: 01012345678 (통화 시간: 12029ms)
```

### 2. MMS 수신 테스트
```
1. 다른 기기에서 MMS 전송
2. Logcat 확인:
   adb logcat -s SmsContentObserver:D
3. UI에서 수신 확인
```

**예상 로그:**
```
새로운 MMS 감지!
MMS address - type: 137 (0x89), address: 01012345678
✓ 발신자 address 발견: type=137, address=01012345678
MMS 텍스트 발견: 안녕하세요
SMS/MMS 저장 성공: ID = 1
```

## 알려진 제한사항

1. **자동 전화 끊기**
   - Android 9+ 에서는 기본 전화 앱이 아니면 작동하지 않을 수 있음
   - `ANSWER_PHONE_CALLS` 권한 필요

2. **MMS 수신**
   - BroadcastReceiver로는 MMS 수신이 어려움
   - ContentObserver 방식으로만 처리

3. **에뮬레이터**
   - 전화 하드웨어가 없으므로 실제 기기 필요

## 향후 개선 사항

1. 통화 녹음 기능 (권한 및 정책 확인 필요)
2. 통계 대시보드 (총 전화 개수, 연결률 등)
3. 전화번호 필터링 (블랙리스트/화이트리스트)
4. 백그라운드 서비스로 전환
5. MMS 이미지/동영상 처리

## 문의 및 지원

문제가 발생하거나 질문이 있으시면 이슈를 등록해주세요.
