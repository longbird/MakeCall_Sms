# SMS 수신 문제 해결 가이드 (삼성 기기)

## 현재 상황
- 기기: Samsung SM-A166L
- Android 버전: API 36 (Android 15)
- 기본 SMS 앱: com.samsung.android.messaging
- 문제: SMS BroadcastReceiver가 호출되지 않음

## 삼성 기기 특별 설정

### 1단계: 앱 배터리 최적화 해제
```
설정 > 배터리 > 백그라운드 사용 제한 > AutoCallSmsApp > 제한 안 함
```

### 2단계: 앱 절전 모드 해제
```
설정 > 배터리 > 앱 절전 > AutoCallSmsApp > 사용 안 함
```

### 3단계: 자동 최적화 해제
```
설정 > 디바이스 케어 > 배터리 > 앱 전원 관리 > AutoCallSmsApp 추가 (최적화 안 함)
```

### 4단계: 백그라운드 데이터 허용
```
설정 > 앱 > AutoCallSmsApp > 모바일 데이터 > 백그라운드 데이터 사용 허용
```

### 5단계: 알림 허용
```
설정 > 알림 > AutoCallSmsApp > 모든 알림 허용
```

## ADB 명령어로 확인

```bash
# 앱 권한 상태 확인
adb shell dumpsys package com.example.autocallsms | grep -A 20 "granted=true"

# 배터리 최적화 상태 확인
adb shell dumpsys deviceidle whitelist | grep autocallsms

# SMS 관련 권한 확인
adb shell pm list permissions -g | grep -A 10 SMS
```

## 테스트 방법

1. 위의 모든 설정 변경
2. 기기 재부팅
3. 앱 재설치
4. SMS 전송 테스트
