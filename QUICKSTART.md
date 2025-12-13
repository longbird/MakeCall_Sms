# 빠른 시작 가이드

## 1단계: 프로젝트 열기

1. **Android Studio 실행**
2. **File → Open** 메뉴 선택
3. `AutoCallSmsApp` 폴더 선택

## 2단계: SDK 경로 설정

### 자동 설정 (권장)
Android Studio가 자동으로 SDK 경로를 감지합니다.

### 수동 설정
프로젝트 루트에 `local.properties` 파일을 생성하고 다음 내용을 추가:

**Windows:**
```
sdk.dir=C\:\\Users\\당신의사용자명\\AppData\\Local\\Android\\Sdk
```

**Mac:**
```
sdk.dir=/Users/당신의사용자명/Library/Android/sdk
```

**Linux:**
```
sdk.dir=/home/당신의사용자명/Android/Sdk
```

## 3단계: Gradle 동기화

1. Android Studio 하단에 나타나는 "Sync Now" 클릭
2. 또는 메뉴: **File → Sync Project with Gradle Files**
3. 동기화가 완료될 때까지 기다립니다 (첫 실행 시 몇 분 소요)

## 4단계: 앱 실행

### 실제 기기 사용 (권장)
1. USB 디버깅이 활성화된 안드로이드 기기를 컴퓨터에 연결
2. 상단의 기기 선택 드롭다운에서 연결된 기기 선택
3. **Run** 버튼 (▶️) 클릭

### 에뮬레이터 사용
1. **Tools → Device Manager** 메뉈
2. 가상 기기 생성 (Create Device)
3. API Level 24 이상의 이미지 선택
4. **Run** 버튼 (▶️) 클릭

## 5단계: 권한 허용

앱이 실행되면 다음 권한을 허용해야 합니다:
- ✅ 전화 걸기
- ✅ SMS 수신
- ✅ SMS 읽기
- ✅ 전화 상태 읽기

모든 권한을 "허용"으로 설정하세요.

## 6단계: 앱 사용

1. 전화번호 입력 (예: 01012345678)
2. "전화 걸기" 버튼 클릭
3. 상대방이 SMS로 답장하면 자동으로 목록에 표시됩니다

---

## 문제 해결

### Gradle 동기화 실패
```bash
# 터미널에서 실행
./gradlew clean build
```

### "SDK location not found" 오류
→ `local.properties` 파일을 확인하세요

### 앱이 크래시되는 경우
→ 모든 권한이 허용되었는지 확인하세요

### 빌드가 느린 경우
→ `gradle.properties`에서 메모리 설정 조정:
```
org.gradle.jvmargs=-Xmx4096m
```

---

## 추가 정보

자세한 내용은 `README.md` 파일을 참조하세요.
