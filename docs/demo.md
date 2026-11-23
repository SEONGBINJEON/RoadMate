# Pleos 에뮬레이터 시연

1. 공식 설치 안내에 따라 Android Studio에서 Pleos Connect 2.1.0 시스템 이미지(API 34)를 설치하고 Automotive 가상 기기를 생성합니다. 부팅 설정은 Cold boot, RAM은 4GB로 맞춥니다. 앱 빌드에는 Android SDK Platform 35를 사용합니다. [Pleos 에뮬레이터 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
2. Android Studio의 Device Manager에서 가상 기기를 시작하고 Virtual Console을 연결합니다. 이 프로젝트의 검증 화면은 2560×1440, 160dpi에서 캡처했습니다. [Virtual Console 연결 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
3. 저장소에서 테스트와 APK 빌드를 실행합니다. 가상 기기를 선택해 Android Studio에서 실행하거나 Android SDK의 `adb`로 빌드한 APK를 설치합니다. [빌드 명령](../README.md#빌드와-테스트)

```powershell
adb devices
adb -e install app/build/outputs/apk/release/app-release.apk
```

위 명령은 에뮬레이터 한 대를 연결한 환경 기준입니다. 같은 패키지의 기존 앱과 서명이 다르면 Android 설정에서 기존 앱을 제거한 후 설치할 수 있습니다. 제거하면 해당 가상 기기의 앱 데이터도 삭제됩니다.

## 주차 후 휴식과 중단

1. Virtual Console에서 기어를 P로 전환합니다. 앱 상단의 ‘실제 기어’가 P인지 확인하고, 처음 실행할 때 외부 AI 대신 기본 안내를 선택합니다.
2. 시연 패널에서 ‘쉬고 싶어’ 모의 응답을 선택합니다. 내비가 열리면 로드메이트로 돌아옵니다.
3. 확인창이 표시되면 차량 설정 변경 내용을 확인하고 휴식을 승인합니다. 확인창이 자동으로 열리지 않으면 ‘휴식 시작 확인’을 누릅니다. [승인 화면](screenshots/confirm.png)
4. ‘휴식 중단’을 눌러 주차 화면으로 돌아오는지 확인합니다. 중단하면 예약된 제어가 멈추며, 적용된 설정은 운전자가 확인합니다. 정상 종료를 확인하려면 30초 타이머가 끝날 때까지 기다립니다. [휴식 화면](screenshots/nap.png)

기어 P는 에뮬레이터의 Gear SDK에서 수신한 값입니다. 검증 당시 Pleos 환경에서 모의 응답으로 휴식 전환을 확인했으며 음성 인식 정확도와 실차 동작은 별도 검증 대상입니다. [팀의 0.2.1 검증 기록](https://github.com/PJJ0337/RoadMate/blob/f4d8cd19ae199f456e7784404cd4569b92857c5d/docs/VALIDATION-0.2.1.md)
