# 자동 전화 걸기 및 SMS 수신 앱

안드로이드에서 자동으로 전화를 걸고, 상대방이 응답 SMS를 보냈을 때 그 내용을 전화번호와 함께 데이터베이스에 저장하는 앱입니다.

**언어**: Kotlin (JVM Target 11)
**Kotlin 버전**: 1.9.20
**최소 SDK**: 24 (Android 7.0)
**타겟 SDK**: 34 (Android 14)

## 주요 기능

1. **앱 실행 시 자동 시작**: 권한이 허용되면 자동으로 서버 접속 및 전화 걸기 시작
2. **자동 전화 걸기**: REST API에서 전화번호를 가져와 순차적으로 자동 전화
3. **SMS 자동 수신 및 저장**: 수신된 모든 SMS를 로컬 데이터베이스와 원격 서버에 저장
4. **히스토리 보기**: 저장된 모든 SMS 기록을 시간순으로 확인
5. **SQLite 데이터베이스**: 로컬 데이터베이스에 영구적으로 저장
6. **REST API 연동**: 원격 서버와 통화 상태 및 SMS 기록 동기화

## 파일 구조

```
AutoCallSmsApp/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/autocall/
│   │       │   ├── MainActivity.kt             # 메인 액티비티 (Kotlin)
│   │       │   ├── ApiClient.kt                # REST API 클라이언트 (object)
│   │       │   ├── SmsReceiver.kt              # SMS 수신 BroadcastReceiver
│   │       │   ├── PhoneStateReceiver.kt       # 전화 상태 모니터링
│   │       │   ├── CallManager.kt              # 전화 관리 싱글톤 (object)
│   │       │   ├── DatabaseHelper.kt           # SQLite 데이터베이스 헬퍼
│   │       │   ├── SmsRecord.kt                # SMS 레코드 (data class)
│   │       │   ├── SmsHistoryAdapter.kt        # RecyclerView 어댑터
│   │       │   └── SmsContentObserver.kt       # SMS ContentObserver
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   ├── activity_main.xml       # 메인 화면 레이아웃
│   │       │   │   └── item_sms_record.xml     # SMS 아이템 레이아웃
│   │       │   └── values/
│   │       │       └── strings.xml             # 문자열 리소스
│   │       └── AndroidManifest.xml             # 매니페스트 파일
│   └── build.gradle                            # 앱 모듈 빌드 설정 (Kotlin 플러그인 포함)
├── build.gradle                                # 프로젝트 빌드 설정 (Kotlin 플러그인 포함)
└── settings.gradle                             # 프로젝트 설정
```

## 필요 권한

앱이 정상 동작하기 위해 다음 권한들이 필요합니다:

- `CALL_PHONE`: 전화 걸기
- `READ_PHONE_STATE`: 전화 상태 확인
- `RECEIVE_SMS`: SMS 수신
- `READ_SMS`: SMS 읽기
- `READ_CONTACTS`: 연락처 읽기

앱 최초 실행 시 이 권한들을 요청합니다.

## 데이터베이스 구조

### sms_records 테이블

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| id | INTEGER | 기본 키 (자동 증가) |
| phone_number | TEXT | 전화번호 |
| message | TEXT | SMS 메시지 내용 |
| timestamp | INTEGER | SMS 수신 시간 (밀리초) |
| created_at | DATETIME | 레코드 생성 시간 |

## 사용 방법

1. **앱 설치 및 권한 허용**
   - 앱을 실행하면 필요한 권한을 요청합니다.
   - 모든 권한을 허용해야 정상 작동합니다.

2. **전화 걸기**
   - 상단의 입력란에 전화번호를 입력합니다 (예: 01012345678).
   - "전화 걸기" 버튼을 누릅니다.
   - 자동으로 해당 번호로 전화가 걸립니다.

3. **SMS 응답 수신**
   - 전화를 건 상대방이 SMS로 답장을 보내면 자동으로 감지됩니다.
   - 전화번호, 메시지 내용, 수신 시간이 데이터베이스에 저장됩니다.
   - 메인 화면의 리스트에 자동으로 표시됩니다.

4. **히스토리 확인**
   - 화면을 아래로 스크롤하여 모든 SMS 기록을 확인할 수 있습니다.
   - "히스토리 새로고침" 버튼을 눌러 최신 데이터로 업데이트할 수 있습니다.
   - 각 항목에는 전화번호, 메시지 내용, 수신 시간이 표시됩니다.

## 작동 원리

1. **전화 걸기 시**:
   - `MainActivity`에서 `CallManager`에 전화번호를 등록합니다.
   - `Intent.ACTION_CALL`을 사용하여 전화를 겁니다.

2. **SMS 수신 시**:
   - `SmsReceiver`가 SMS를 수신합니다.
   - 수신된 전화번호가 최근에 전화를 건 번호인지 `CallManager`로 확인합니다.
   - 일치하면 `DatabaseHelper`를 통해 SQLite 데이터베이스에 저장합니다.

3. **데이터 표시**:
   - `MainActivity`가 `DatabaseHelper`를 통해 저장된 데이터를 조회합니다.
   - `SmsHistoryAdapter`가 `RecyclerView`에 데이터를 표시합니다.

## 주의사항

1. **권한**: Android 6.0 (API 23) 이상에서는 런타임 권한 요청이 필요합니다.
2. **전화번호 매칭**: 국가 코드(+82) 포함 여부에 관계없이 번호를 매칭합니다.
3. **SMS 대기 시간**: 전화 걸기 후 10분 이내에 온 SMS만 저장됩니다 (설정 변경 가능).
4. **백그라운드 실행**: 앱이 백그라운드에 있어도 SMS를 수신하고 저장합니다.

## 빌드 방법

1. Android Studio에서 프로젝트를 엽니다.
2. Gradle 동기화를 완료합니다.
3. 실제 안드로이드 기기를 연결하거나 에뮬레이터를 실행합니다.
4. Run 버튼을 클릭하여 앱을 실행합니다.

## 테스트 시나리오

1. 앱을 실행하고 모든 권한을 허용합니다.
2. 자신의 다른 전화번호 또는 친구의 전화번호를 입력합니다.
3. "전화 걸기" 버튼을 누릅니다.
4. 상대방에게 통화 대신 SMS로 답장을 보내달라고 요청합니다.
5. SMS가 도착하면 앱의 리스트에 자동으로 표시되는지 확인합니다.

## Kotlin으로 마이그레이션

프로젝트는 Kotlin으로 작성되어 다음과 같은 장점을 제공합니다:

### 주요 개선사항
- **Null 안전성**: Nullable 타입과 Non-null 타입 명시적 구분으로 NullPointerException 방지
- **코드 간결성**: Data class, Object 선언, 람다 등으로 boilerplate 코드 감소 (약 6% 코드 라인 감소)
- **가독성 향상**: Kotlin 관용구 및 표준 라이브러리 활용
- **향후 확장성**: Kotlin Coroutines 등 최신 기술 쉽게 적용 가능

### Kotlin 특징 활용 예시
- `SmsRecord`: data class로 자동 getter/setter 생성
- `CallManager`, `ApiClient`: object 선언으로 싱글톤 패턴 간소화
- `DatabaseHelper`: use 확장 함수로 리소스 관리 개선
- 람다 및 고차 함수로 콜백 처리 간소화

## 커스터마이징

### SMS 대기 시간 변경
`CallManager.kt`의 `SMS_WAIT_TIME` 상수를 수정하세요:
```kotlin
private const val SMS_WAIT_TIME = 10 * 60 * 1000L // 10분
```

### 서버 주소 변경
`MainActivity.kt`에서 기본 서버 주소를 수정하세요:
```kotlin
etServerAddress.setText("192.168.0.210:8080")
```

### UI 색상 및 디자인 변경
- `activity_main.xml`: 메인 화면 레이아웃
- `item_sms_record.xml`: SMS 아이템 디자인

## 라이선스

이 프로젝트는 교육 및 참고 목적으로 제공됩니다.

## 문의

문제가 발생하거나 질문이 있으시면 이슈를 등록해주세요.
