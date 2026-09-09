---
layout: default
title: 개인정보처리방침 · Privacy Policy
permalink: /privacy-policy
---

# 개인정보처리방침

- **앱**: UsingTime (`io.github.sihun0927.usingyourtime`)
- **최종 수정일**: 2026-09-09

## 요약

UsingTime은 개인정보를 **수집하지 않고, 어디로도 전송하지 않습니다.** 앱이 만드는 데이터는 전부 사용자의 기기 안에만 남습니다.

## 1. 수집하지 않는 것

- 계정·이름·이메일·전화번호 같은 식별 정보를 요구하지 않습니다. 로그인이 없습니다.
- 위치, 연락처, 사진, 파일, 마이크, 카메라에 접근하지 않습니다.
- 어떤 앱을 얼마나 썼는지는 보지 않습니다(`PACKAGE_USAGE_STATS` 권한을 선언하지 않습니다). 앱이 아는 것은 화면이 켜지고 잠금이 풀린 시각뿐입니다.
- 광고·분석 SDK가 없고 광고 ID(`AD_ID`)를 쓰지 않습니다.
- 네트워크 권한(`INTERNET`)이 없습니다. 앱에는 데이터를 밖으로 보낼 수단 자체가 없습니다.
- 따라서 제3자에게 제공·판매·공유하는 데이터가 없습니다.

## 2. 기기 안에만 저장하는 것

| 저장 항목 | 내용 | 저장소 |
|---|---|---|
| 설정 | 측정 켜짐 여부, 임계값, 유예 시간, 재알림 주기, 임계값 알림 사용 여부, 재시작 안내를 띄운 시각 | 앱 전용 저장 영역(DataStore) |
| 세션 기록 | 연속 사용 세션의 시작·종료 시각, 마지막으로 측정이 살아 있던 시각, 잠금 시각, 종료 원인, 알림 시각과 횟수, 이번 세션 알림 끄기 여부 | 앱 전용 데이터베이스(Room) |

- 시각은 모두 기기 시계 기준의 숫자(epoch 밀리초)이며, 어떤 앱을 썼는지·무엇을 봤는지는 담지 않습니다.
- 이 데이터는 앱 밖으로 나가지 않고, 화면으로 보여주는 기능도 v1에는 없습니다. 오직 연속 사용 시간을 계산하고 앱이 강제 종료되거나 기기가 재부팅된 뒤 이어서 측정하기 위해 씁니다.
- `android:allowBackup="false"`로 선언되어 클라우드 자동 백업에도 포함되지 않습니다.

## 3. 권한과 용도

앱이 선언하는 권한은 아래 4개가 전부입니다.

| 권한 | 용도 |
|---|---|
| `FOREGROUND_SERVICE` | 측정이 켜진 동안 포그라운드 서비스로 연속 사용 시간을 재고 상태바의 상시 표시를 유지합니다 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14 이상에서 위 포그라운드 서비스의 유형을 선언하기 위해 필요합니다 |
| `POST_NOTIFICATIONS` | 상시 표시와 임계값 알림을 띄웁니다(Android 13 이상). 거부하면 측정을 시작하지 않습니다 |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 전에 측정이 켜져 있었다면 재부팅 뒤 측정을 다시 이어갑니다 |

## 4. 보관과 삭제

데이터는 사용자가 지울 때까지 기기에 남습니다. 시스템 설정의 **앱 › UsingTime › 저장공간 › 데이터 삭제**로 모두 지울 수 있고, 앱을 삭제하면 함께 사라집니다. 앱을 지운 뒤 개발자에게 남는 사본은 없습니다.

## 5. 아동

이 앱은 아동을 대상으로 하지 않으며, 나이를 묻거나 아동의 개인정보를 수집하지 않습니다.

## 6. 방침 변경

방침이 바뀌면 이 페이지를 고치고 위의 최종 수정일을 갱신합니다. 변경 이력은 이 저장소의 커밋 기록에 남습니다.

## 7. 연락처

- 이메일: sihun0927@gmail.com
- 이슈: <https://github.com/sihun0927-sketch/UsingYourTime/issues>

---

# Privacy Policy

- **App**: UsingTime (`io.github.sihun0927.usingyourtime`)
- **Last updated**: 2026-09-09

## Summary

UsingTime **collects no personal data and transmits nothing anywhere.** Everything the app produces stays on your device.

## 1. What we do not collect

- No account, name, email address, or phone number. There is no sign-in.
- No access to location, contacts, photos, files, microphone, or camera.
- No record of which apps you use or for how long (the app does not declare `PACKAGE_USAGE_STATS`). All it knows is when the screen turned on and when the device was unlocked.
- No advertising or analytics SDKs, and no advertising ID (`AD_ID`).
- No `INTERNET` permission. The app has no way to send data off the device.
- Consequently there is nothing to sell, share, or disclose to third parties.

## 2. What is stored, on your device only

| Stored item | Contents | Storage |
|---|---|---|
| Settings | Whether tracking is on, threshold, grace period, re-alert interval, whether threshold alerts are enabled, when the restart notice was shown | App-private storage (DataStore) |
| Session records | Start and end time of each continuous-use session, the time tracking was last alive, lock time, end reason, alert times and count, whether alerts are muted for the session | App-private database (Room) |

- All times are numbers on your device's clock (epoch milliseconds). They contain nothing about which apps you opened or what you looked at.
- The data never leaves the app, and v1 has no screen that displays it. It exists only to compute the current session duration and to resume correctly after a force-stop or a reboot.
- The app declares `android:allowBackup="false"`, so this data is excluded from cloud backups.

## 3. Permissions and why they are needed

These four are the only permissions the app declares.

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Runs the timer in a foreground service while tracking is on and keeps the persistent notification in the status bar |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declares the type of that foreground service, required on Android 14 and above |
| `POST_NOTIFICATIONS` | Shows the persistent display and the threshold alert (Android 13 and above). If denied, tracking does not start |
| `RECEIVE_BOOT_COMPLETED` | Resumes tracking after a reboot if it was on beforehand |

## 4. Retention and deletion

Data stays on your device until you remove it. Clear it from **Settings › Apps › UsingTime › Storage › Clear data**, or uninstall the app to delete it entirely. No copy remains with the developer, because none was ever sent.

## 5. Children

The app is not directed at children. It does not ask for an age and does not collect personal information from children.

## 6. Changes to this policy

If the policy changes, this page is updated and the "Last updated" date above is revised. The full history is visible in this repository's commit log.

## 7. Contact

- Email: sihun0927@gmail.com
- Issues: <https://github.com/sihun0927-sketch/UsingYourTime/issues>
