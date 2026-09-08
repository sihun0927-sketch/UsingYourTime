# Android 잠금 해제/잠금 감지와 백그라운드 상시 유지, Play 정책 제약

이슈: [#2](https://github.com/sihun0927-sketch/UsingYourTime/issues/2) (map: #1). 조사일 2026-09-08.
질문: 연속 사용 세션의 시작(잠금 해제)과 끝(잠금/화면 꺼짐)을 안정적으로 감지하고, 세션 진행 중 프로세스를 살려 두는 방법은? 상시 표시와 임계값 알림을 Play 정책 안에서 구현하려면 무엇이 필요한가?

## 권장 접근

**`specialUse` 타입의 상시 foreground service 하나가 런타임 등록 receiver로 `ACTION_USER_PRESENT` / `ACTION_SCREEN_OFF`를 받고, 세션 타이머와 상시 표시를 직접 관리한다. UsageStats·AccessibilityService·정확 알람(exact alarm)은 쓰지 않는다.**

### 왜 이 구조인가

1. **세 브로드캐스트는 manifest receiver로 받을 수 없다.** `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`는 API 1부터 javadoc에 "You cannot receive this through components declared in manifests, only by explicitly registering for it with `Context.registerReceiver()`"라고 명시돼 있고, 발신 측(`PowerManagerService`의 `Notifier`)이 `FLAG_RECEIVER_REGISTERED_ONLY`를 붙여 보낸다. `ACTION_USER_PRESENT`는 플래그상 제한은 없지만 implicit broadcast이고 Android 8.0 manifest 예외 목록에 없어, targetSdk 26+ 앱은 사실상 런타임 등록만 가능하다. 런타임 receiver는 프로세스가 살아 있는 동안만 유효하므로("If you register with the Application context, you receive broadcasts as long as the app runs") **살아 있는 컴포넌트가 반드시 필요**하다.
2. **세션 시작 시점에 서비스를 새로 띄울 수 없다.** Android 12+ 백그라운드 FGS 시작 제한의 예외 목록에 `ACTION_USER_PRESENT`/`SCREEN_ON`은 없다(BOOT_COMPLETED, 정확 알람, 배터리 최적화 해제 등만 예외). 즉 잠금 상태에서 서비스가 죽어 있으면 다음 잠금 해제를 놓친다. 따라서 서비스는 "세션 중"이 아니라 **잠금 중에도 계속 살아 있어야** 한다. 또한 cached 상태의 프로세스에는 `SCREEN_ON` 류 브로드캐스트가 지연 전달되므로(`DEFERRAL_POLICY_UNTIL_ACTIVE`), 프로세스를 cached에서 빼 주는 FGS가 실질적으로 유일한 호스트다.
3. **타입은 `specialUse`만 맞는다.** `shortService`는 약 3분 제한, `dataSync`는 데이터 전송 용도이며 Android 15에서 24시간당 6시간 제한, `health`는 피트니스 권한 필요, `systemExempted`는 시스템/특정 역할 전용(아니면 `ForegroundServiceTypeNotAllowedException`). `specialUse`는 "any valid foreground service use cases that aren't covered by the other foreground service types"이고 시간 제한이 문서화되어 있지 않다. manifest에 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` 권한과 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="...">`를 선언해야 하며, 누락 시 `MissingForegroundServiceTypeException`/`SecurityException`.
4. **화면이 켜져 있는 동안 Doze는 무관하다.** Doze는 "unplugged and stationary ... with the screen off"일 때만 진입한다. 세션이 진행 중(화면 켜짐, 잠금 해제)이면 FGS 안의 `Handler`/coroutine 타이머(`SystemClock.elapsedRealtime()` 기준)만으로 임계값 알림 시점을 잡을 수 있다. Google 가이드도 "Use Handler for timing operations guaranteed during app lifetime"을 권한다. 정확 알람은 불필요하고, Android 14+에서는 `SCHEDULE_EXACT_ALARM`이 기본 거부라 설정 화면 이동을 요구한다.
5. **유예 시간 만료는 알람 없이도 정확하다.** 잠금(`SCREEN_OFF`) 시각을 `elapsedRealtime`으로 저장하고, 다음 `USER_PRESENT`에서 경과 시간이 유예 시간 이내인지 비교하면 세션 연속 여부가 결정된다. 화면 꺼진 뒤 deep sleep에서는 `Handler` 타이머가 멈추므로("Time spent in deep sleep will add an additional delay"), 잠금 상태에서 상시 표시를 내리는 용도로만 `setAndAllowWhileIdle`(권한 불필요, 부정확)을 보조로 건다. 늦게 울려도 세션 판정에는 영향이 없다.

### 구현 골격

- **상태 복구**: 서비스 (재)시작 시 `PowerManager.isInteractive() && !KeyguardManager.isKeyguardLocked()`이면 세션 진행 중으로 본다. `isDeviceLocked()`는 swipe 잠금화면을 "unlocked"로 보므로 쓰지 않는다. 세션 시작 시각은 프로세스 죽음에 대비해 매번 영속화한다.
- **`USER_PRESENT` 의미**: 잠금화면이 "없음"이면 화면 켜짐 직후에, 그 외에는 keyguard dismiss 후에 발송된다(`KeyguardViewMediator`). `FLAG_RECEIVER_REPLACE_PENDING`이라 연속 발생이 합쳐질 수 있으니 횟수를 세지 말고 상태만 갱신한다. `SCREEN_OFF`는 "interactive state" 변화이므로 AOD가 켜져 있어도 잠금 시 발송된다.
- **재시작 경로**: manifest receiver로 `ACTION_BOOT_COMPLETED`(`RECEIVE_BOOT_COMPLETED` 권한)와 `ACTION_MY_PACKAGE_REPLACED`에서 `startForegroundService`. 둘 다 Android 12 백그라운드 시작 제한의 명시적 예외이고, Android 15의 BOOT_COMPLETED 타입 제한 목록(dataSync/camera/media*/phoneCall/microphone)에 `specialUse`는 없다. 앱 실행 시에도 서비스를 확인·기동.
- **알림 두 모드**: 세션 중에는 상시 표시(`setUsesChronometer(true)`로 1초 갱신 없이 경과 시간 표시 가능), 잠금 중/세션 밖에는 `IMPORTANCE_MIN` 대기 알림. FGS는 알림 없이 돌 수 없다.
- **POST_NOTIFICATIONS(Android 13+)**: FGS 기동 자체에는 필요 없지만, 거부되면 상시 표시는 알림 창에서 사라지고(Task Manager에만 보임) 임계값 알림은 조용히 버려진다. 앱의 핵심 가치가 알림이므로 온보딩에서 맥락과 함께 요청한다.
- **Android 14+ 알림 스와이프**: `setOngoing(true)` 알림도 잠금 해제 상태에서는 사용자가 지울 수 있다(잠금 중·"모두 지우기"는 예외). 서비스는 계속 돌므로 다음 세션 시작 때 다시 `notify`한다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 세션 중에만 FGS를 띄우고 잠금 시 종료 | 잠금 중에는 `USER_PRESENT`를 받을 프로세스가 없고, 받더라도 Android 12+에서 백그라운드 FGS 시작이 금지된다(예외 목록에 없음). |
| manifest receiver + WorkManager | `SCREEN_*`는 manifest 수신 불가, `USER_PRESENT`도 26+ 예외 목록에 없음. WorkManager는 최소 15분 주기·비정확이라 이벤트 감지·초 단위 표시 불가. |
| `AccessibilityService`로 화면 상태 추적 | 잠금 감지에 불필요. Play 정책상 "monitoring apps"는 accessibility tool이 아니며 prominent disclosure + 선언 필요, 심사 리스크 최상. |
| `PACKAGE_USAGE_STATS`(`UsageEvents.KEYGUARD_HIDDEN/SHOWN`, API 28+) | 실시간 감지가 아니라 사후 조회용. 설정 화면 수동 허용 + User Data 정책의 "usage data" 공개 요구. v1에는 불필요, 프로세스 사망 후 세션 백필용으로만 후속 검토. |
| `SCHEDULE_EXACT_ALARM`/`setExactAndAllowWhileIdle`로 임계값 알림 | 화면 켜짐 중엔 Doze가 없어 불필요. Android 14+ 신규 설치에서 기본 거부, 사용자가 설정에서 켜야 함. Play 정책은 "core functionality depends on precise timing"일 때만 권장. |
| `USE_EXACT_ALARM` | Play 정책 허용 범위가 "alarm or timer app", "calendar app"뿐. 이 앱을 timer app으로 볼지는 심사 판단이라 위험. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Play 허용 목록(FCM 없는 메시징, 안전 앱, 자동화, 주변기기 companion)에 해당하지 않음. |
| `dataSync`/`shortService` 타입 | 각각 Android 15에서 6h/24h 제한, 약 3분 제한(초과 시 ANR). |

## 스토어 정책 리스크 목록

1. **`specialUse` Play Console 심사(가장 큼).** targetSdk 34+는 Policy > App content에서 FGS 타입 선언 필수. 항목: 기능 설명, 시스템이 지연/중단했을 때의 사용자 영향, 기능 시연 영상 링크, use case 선택. 문서는 "In limited scenarios ... you may declare ... TYPE_SPECIAL_USE. All foreground service types are subject to review."라고만 하고 거절 기준은 비공개다. 완화: 사용자가 세션 알림을 실제로 보는 흐름을 영상으로, "runs only for as long as necessary"에 맞춰 사용자가 서비스를 끌 수 있는 토글을 제공.
2. **Device and Network Abuse의 FGS 조건.** "beneficial to the user and relevant to the core functionality", "user perceptible", "can be terminated or stopped by the user", "runs only for as long as necessary". 잠금 중 대기 알림이 "필요한 만큼만"에 걸릴 수 있다. 완화: 대기 알림 문구에 이유(다음 잠금 해제 감지) 명시, 앱 내 중지 버튼.
3. **OEM 강제 종료(Samsung/Xiaomi 등).** Samsung은 3일 미사용 앱을 sleep 처리해 백그라운드 기동 불가, MIUI는 Autostart 허용 필요(2차 출처 dontkillmyapp.com). 서비스가 죽으면 다음 잠금 해제까지 감지 불가. 완화: 앱 열 때마다 서비스 확인, OEM별 안내 화면, 세션 시작 시각 영속화.
4. **사용자 개입으로 조용히 멈춤.** Android 13+ Task Manager "Stop"은 콜백 없이 앱을 종료하고, Android 14+는 상시 표시를 스와이프로 제거 가능. POST_NOTIFICATIONS 거부 시 임계값 알림이 사라진다. 완화: 앱 진입 시 상태 진단 표시.
5. **알림 정책.** 별도 "notification spam" 정책은 없으나 시스템 알림을 흉내 내는 알림은 App Promotion 정책 위반. 상시 표시는 앱 이름·용도가 분명한 일반 알림으로 유지.

## 미해결 / 후속 질문

- 시스템이 죽인 `START_STICKY` FGS를 재시작할 때 Android 12+에서 `startForeground`가 백그라운드 시작 제한에 걸리는지(예외 목록에 명시 없음). 실기기 검증 필요.
- `specialUse` 심사 통과율에 대한 공개 데이터 없음. 유사 앱(스크린 타임 타이머류)의 선언 사례 조사, 혹은 심사 거절 시 대안(예: 사용자 명시적 "측정 시작" 액션 후에만 FGS) 설계.
- 잠금 중 유예 시간 만료 알람(`setAndAllowWhileIdle`)이 Doze에서 수 분~1시간 지연될 때 상시 표시가 잘못된 값을 보여주는 창이 생긴다. 허용 가능한지 도메인 결정 필요.
- 임계값이 유예 시간(잠금 중)에 도달한 경우 임계값 알림을 즉시 보낼지, 다음 잠금 해제 때 보낼지 도메인 결정 필요.
- 프로세스 사망 후 세션 백필을 위해 `PACKAGE_USAGE_STATS`를 도입할 가치가 있는지(OEM별로 `KEYGUARD_*` 이벤트가 실제 반환되는지 미검증).
- 잠금화면 "없음" 기기에서는 `USER_PRESENT`가 매 화면 켜짐마다 오므로 세션 정의가 "화면 켜짐~꺼짐"으로 축소된다. 허용할지 결정.

## 출처

- Intent `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON`/`ACTION_USER_PRESENT` javadoc (AOSP): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java , https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF
- 발신 플래그: `Notifier.java` https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/Notifier.java , `KeyguardViewMediator.java` https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java
- Broadcasts overview / implicit broadcast 예외: https://developer.android.com/develop/background-work/background-tasks/broadcasts , https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
- `PowerManager.isInteractive()`, `KeyguardManager`: https://developer.android.com/reference/android/os/PowerManager#isInteractive() , https://developer.android.com/reference/android/app/KeyguardManager
- FGS 백그라운드 시작 제한(Android 12+): https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 15 BOOT_COMPLETED FGS 타입 제한, dataSync 6h: https://developer.android.com/about/versions/15/behavior-changes-15
- FGS 타입 필수(Android 14) / 타입별 설명(`specialUse`): https://developer.android.com/about/versions/14/changes/fgs-types-required , https://developer.android.com/develop/background-work/services/fgs/service-types
- `Service.startForeground` 예외: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/app/Service.java
- Play Console FGS 선언: https://support.google.com/googleplay/android-developer/answer/13392821
- Play Device and Network Abuse(FGS 조건): https://support.google.com/googleplay/android-developer/answer/16559646
- 사용자 FGS 중지(Task Manager): https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- Android 14 FGS 알림 dismiss 가능: https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications
- Notification runtime permission: https://developer.android.com/develop/ui/views/notifications/notification-permission
- Doze/App Standby, 배터리 최적화 허용 목록: https://developer.android.com/training/monitoring-device-state/doze-standby , https://developer.android.com/topic/performance/power/power-details
- 알람 스케줄 가이드, Android 12/14 exact alarm: https://developer.android.com/develop/background-work/services/alarms/schedule , https://developer.android.com/about/versions/12/behavior-changes-12 , https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- `Handler`/`SystemClock` deep sleep: https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/os/Handler.java , https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/os/SystemClock.java
- Play Exact alarm 정책, 제한 권한 목록: https://support.google.com/googleplay/android-developer/answer/13161072 , https://support.google.com/googleplay/android-developer/answer/9888170
- Play Accessibility API 정책: https://support.google.com/googleplay/android-developer/answer/10964491
- Play User Data 정책: https://support.google.com/googleplay/android-developer/answer/10144311
- `UsageEvents.Event` 키가드 이벤트(API 28): https://developer.android.com/sdk/api_diff/28/changes/android.app.usage.UsageEvents.Event , https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/app/usage/UsageStatsManager.java
- WorkManager 최소 주기: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- OEM 제한(2차 출처): https://dontkillmyapp.com/ , https://dontkillmyapp.com/samsung , https://dontkillmyapp.com/xiaomi
