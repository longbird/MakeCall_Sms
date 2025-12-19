# CI/CD 설정 가이드

## GitHub Actions

### 자동 빌드 (android-build.yml)
- **트리거**: `master`/`main` 브랜치에 push 또는 PR
- **결과물**: Debug APK, Release APK (서명 안 됨)
- **보관 기간**: 30일

### 릴리스 빌드 (android-release.yml)
- **트리거**: `v*` 태그 푸시 (예: `v1.0.0`) 또는 수동 실행
- **결과물**: 서명된 Release APK (Secrets 설정 시)
- **보관 기간**: 90일

## 서명된 APK 빌드 설정

### 1. Keystore 생성 (없는 경우)
```bash
keytool -genkey -v -keystore release-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

### 2. Keystore를 Base64로 인코딩
```bash
base64 -i release-keystore.jks -o keystore-base64.txt
```

### 3. GitHub Secrets 설정
Repository > Settings > Secrets and variables > Actions에서 추가:

| Secret 이름 | 설명 |
|------------|------|
| `KEYSTORE_BASE64` | Keystore 파일의 Base64 인코딩 값 |
| `KEYSTORE_PASSWORD` | Keystore 비밀번호 |
| `KEY_ALIAS` | Key alias (예: my-key-alias) |
| `KEY_PASSWORD` | Key 비밀번호 |

### 4. 릴리스 생성
```bash
git tag v1.0.0
git push origin v1.0.0
```

## 수동 빌드 실행
1. GitHub Repository > Actions 탭
2. 워크플로우 선택
3. "Run workflow" 버튼 클릭

---

## GitLab CI (선택사항)

`.gitlab-ci.yml` 파일을 프로젝트 루트에 추가:

```yaml
image: openjdk:17-jdk

variables:
  ANDROID_COMPILE_SDK: "34"
  ANDROID_BUILD_TOOLS: "34.0.0"
  ANDROID_SDK_TOOLS: "9477386"

before_script:
  - apt-get update -qy
  - apt-get install -y wget unzip
  - wget -q https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_SDK_TOOLS}_latest.zip -O android-sdk.zip
  - unzip -q android-sdk.zip -d android-sdk
  - export ANDROID_HOME=$PWD/android-sdk
  - export PATH=$PATH:$ANDROID_HOME/cmdline-tools/bin
  - yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
  - sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-${ANDROID_COMPILE_SDK}" "build-tools;${ANDROID_BUILD_TOOLS}"
  - chmod +x ./gradlew

stages:
  - build

build:
  stage: build
  script:
    - ./gradlew assembleDebug
    - ./gradlew assembleRelease
  artifacts:
    paths:
      - app/build/outputs/apk/
    expire_in: 30 days
  only:
    - master
    - main
    - tags
```

---

## Jenkins (선택사항)

### Jenkinsfile
```groovy
pipeline {
    agent any

    environment {
        ANDROID_HOME = '/opt/android-sdk'
        JAVA_HOME = '/usr/lib/jvm/java-17-openjdk'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Debug') {
            steps {
                sh 'chmod +x gradlew'
                sh './gradlew assembleDebug'
            }
        }

        stage('Build Release') {
            steps {
                sh './gradlew assembleRelease'
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'app/build/outputs/apk/**/*.apk', fingerprint: true
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
```

### Jenkins 설정 요구사항
1. JDK 17 설치
2. Android SDK 설치 (`/opt/android-sdk`)
3. 환경 변수 설정: `ANDROID_HOME`, `JAVA_HOME`
