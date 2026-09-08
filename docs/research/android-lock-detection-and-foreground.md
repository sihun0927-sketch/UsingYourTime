# Android 잠금 해제·잠금 감지, 포그라운드 유지, 상태 복구, Play 정책 조사

- 대상 이슈: #2 (research)
- 조사일: 2026-09-08
- 출처 원칙: developer.android.com, support.google.com/googleplay/android-developer, AOSP 소스만 1차 출처로 사용. OEM 동작은 dontkillmyapp.com을 **2차 출처**로만 인용하고 표시함.
- 모든 사실은 `사실 → 출처` 형식. 1차 출처로 확인하지 못한 항목은 "미확인"으로 명시.

## 1. 요약 (TL;DR)

1. **감지**: `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`은 manifest 수신이 불가능하고(`Context.registerReceiver()` 전용), `ACTION_USER_PRESENT`도 Android 8.0+ 암시적 브로드캐스트 예외 목록에 없어 targetSdk 26+에서는 manifest로 받을 수 없다. 따라서 **살아 있는 프로세스**가 Application context로 세 브로드캐스트를 등록해야 한다.
2. **프로세스 유지**: 사용자가 "추적 시작"을 눌렀을 때 시작하는 **`foregroundServiceType="specialUse"` 포그라운드 서비스** 1개로 유지한다. `dataSync`는 Android 15에서 24시간당 6시간 제한·부팅 시 시작 금지, `shortService`는 3분 제한이라 부적합하다.
3. **상태 복구**: 세션 시작/종료 타임스탬프와 주기적 heartbeat를 디스크에 영속화하고, 프로세스 재시작 시 `KeyguardManager.isKeyguardLocked()` / `PowerManager.isInteractive()`로 현재 상태를 즉시 재조정한다. 프로세스가 죽어 있는 동안 발생한 잠금 시각은 `PACKAGE_USAGE_STATS` 없이는 알 수 없다(heartbeat로 하한만 추정).
4. **권한 최소화**: `PACKAGE_USAGE_STATS`·AccessibilityService·정확 알람 권한은 잠금 감지에 **불필요**하다. 필요한 것은 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`(13+), `RECEIVE_BOOT_COMPLETED`뿐이다.
5. **가장 큰 리스크**는 Play Console의 `specialUse` 포그라운드 서비스 심사이며, "사용자가 명시적으로 시작·종료하고 알림으로 인지 가능한 타이머"로 설계해 완화한다.

## 2. 권장 접근과 근거

### 2.1 감지 방식: FGS 프로세스 안에서 context-registered receiver

- 세션 시작 신호 = `ACTION_USER_PRESENT`(잠금 해제 완료), 세션 종료 신호 = `ACTION_SCREEN_OFF`(기기 비대화 상태 진입). `ACTION_SCREEN_ON`은 잠금 화면만 켠 경우도 포함하므로 세션 시작으로 쓰지 않고 상태 재확인 트리거로만 쓴다.
  - `ACTION_SCREEN_OFF`: "Sent when the device goes to sleep and becomes non-interactive… You cannot receive this through components declared in manifests, only by explicitly registering for it with Context.registerReceiver()." → https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF
  - `ACTION_USER_PRESENT`(API 3): "Sent when the user is present after device wakes up (e.g when the keyguard is gone)." → https://developer.android.com/reference/android/content/Intent#ACTION_USER_PRESENT
- 등록 컨텍스트는 Application context: "If you register with the Application context, you receive broadcasts as long as the app runs." → https://developer.android.com/develop/background-work/background-tasks/broadcasts
- 수신 직후 `KeyguardManager.isKeyguardLocked()` / `isDeviceLocked()` / `PowerManager.isInteractive()`로 상태를 교차 확인한다(브로드캐스트 순서·중복에 대한 방어). 출처는 §3.4.

### 2.2 프로세스 유지 방식: `specialUse` 포그라운드 서비스

- Android 14+에서 모든 FGS는 타입 선언이 필수이고, 없으면 `MissingForegroundServiceTypeException`. → https://developer.android.com/about/versions/14/changes/fgs-types-required
- `specialUse`: "Covers any valid foreground service use cases not covered by other types." 타임아웃 명시 없음. manifest `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>`로 용도를 적고 Play Console 심사에서 검토됨. → https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- 서비스는 사용자의 명시적 조작(앱 화면 또는 알림 버튼)으로만 시작한다. Android 12+ 백그라운드 FGS 시작 제한 때문. → https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- `onStartCommand()`는 `START_STICKY` 반환, 재생성 시 null intent 처리. → https://developer.android.com/reference/android/app/Service#START_STICKY

### 2.3 상태 복구 방식: 영속 타임스탬프 + heartbeat + 시작 시 상태 재조정

- 디스크에 `sessionStartWallMs`, `sessionStartElapsedMs`, `lastHeartbeatWallMs`, `bootId`(또는 `elapsedRealtime` 기준 부팅 판별)를 저장한다. 프로세스가 죽었다가 재시작되면 (a) 현재 잠금 상태를 폴링하고, (b) 열린 세션이 있으면 `lastHeartbeat`를 종료 하한으로 삼아 "추정 종료"로 닫는다.
- 프로세스가 죽어 있는 동안의 정확한 잠금 시각은 기본 권한으로는 얻을 수 없다. 얻을 수 있는 것: `ApplicationExitInfo.getTimestamp()`(프로세스 종료 시각·이유, API 30), 그리고 `PACKAGE_USAGE_STATS`가 있을 때만 `UsageEvents.Event.KEYGUARD_SHOWN/HIDDEN`, `SCREEN_(NON_)INTERACTIVE`(API 28). 상세는 §3.5.
- 부팅 후: `RECEIVE_BOOT_COMPLETED` + `ACTION_BOOT_COMPLETED` 수신기에서 "추적 켜짐" 플래그가 있으면 `specialUse` FGS를 재시작한다(Android 15의 BOOT_COMPLETED 금지 목록에 `specialUse`는 없음). → https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed

## 3. 조사 항목별 상세

### 3.1 브로드캐스트 신뢰성과 제약

| 사실 | 출처 |
|---|---|
| Android 8.0(API 26)+ 대상 앱은 대부분의 암시적 브로드캐스트를 manifest로 받을 수 없고, context-registered receiver는 계속 가능 | https://developer.android.com/develop/background-work/background-tasks/broadcasts |
| 예외 목록(`ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `LOCALE_CHANGED`, `ACTION_MY_PACKAGE_REPLACED`는 미포함 등)에 **`ACTION_USER_PRESENT`, `ACTION_SCREEN_ON/OFF`는 없음** | https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions |
| `ACTION_SCREEN_ON/OFF`는 API 1부터 존재, "sent in response to changes in the overall interactive state of the device", manifest 수신 불가, 시스템만 전송하는 protected intent | https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF |
| AOSP: `Notifier.java`가 `ACTION_SCREEN_ON/OFF`에 `FLAG_RECEIVER_REGISTERED_ONLY | FLAG_RECEIVER_FOREGROUND`를 붙여 전송 → 커널 수준에서 registered receiver에게만 감 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/Notifier.java |
| AOSP: `KeyguardViewMediator.java`가 `ACTION_USER_PRESENT`를 `FLAG_RECEIVER_REPLACE_PENDING`과 `BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE`로 전송. 즉 registered-only 플래그는 없지만(=예외 목록에 없으므로 targetSdk 26+ manifest 수신 불가), 캐시 상태 프로세스에는 **활성화될 때까지 지연** 전달됨 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java , https://developer.android.com/reference/android/app/BroadcastOptions#DEFERRAL_POLICY_UNTIL_ACTIVE |
| Android 14(모든 앱): 캐시 상태 앱의 context-registered 브로드캐스트는 큐잉되고 여러 개가 병합될 수 있음. `ACTION_SCREEN_ON` 같은 "덜 중요한" 시스템 브로드캐스트가 지연되는 예로 명시됨. FGS가 살아 있으면 캐시 상태가 아니므로 해당 없음 | https://developer.android.com/about/versions/14/behavior-changes-all , https://developer.android.com/develop/background-work/background-tasks/broadcasts |
| Android 14(targetSdk 34+): context-registered receiver는 `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` 지정 필요. 단 "시스템 브로드캐스트만 등록하는 경우 플래그를 지정하지 않아야 한다"고 behavior-changes 페이지가 명시하고, 브로드캐스트 가이드는 "모든 시스템 브로드캐스트를 받으려면 `RECEIVER_EXPORTED`"라고 함. 두 1차 문서가 상충하므로 구현 시 실제 기기에서 검증 필요 | https://developer.android.com/about/versions/14/behavior-changes-14 , https://developer.android.com/develop/background-work/background-tasks/broadcasts |
| `onReceive()`는 메인 스레드에서 빨리 반환해야 하며 `goAsync()`를 써도 10초 이내 완료 기대 | https://developer.android.com/develop/background-work/background-tasks/broadcasts |
| **프로세스가 죽어 있을 때**: manifest receiver는 시스템이 앱을 띄워 주지만 위 세 브로드캐스트는 manifest 수신 자체가 불가 → 죽은 프로세스는 잠금/해제 이벤트를 **받을 수 없음**. 브로드캐스트 재전송(replay)도 없음 | https://developer.android.com/develop/background-work/background-tasks/broadcasts |
| `ACTION_USER_UNLOCKED`(API 24)는 자격증명 암호화 저장소가 열릴 때(부팅 후 최초 해제) 1회성이며 registered receiver 전용 → 매 잠금 해제 감지용이 아님 | https://developer.android.com/reference/android/content/Intent#ACTION_USER_UNLOCKED |

### 3.2 포그라운드 서비스 타입 요구사항

| 사실 | 출처 |
|---|---|
| Android 9(API 28)+: `FOREGROUND_SERVICE` 권한(normal) 필요, 없으면 `SecurityException` | https://developer.android.com/develop/background-work/services/fgs/changes |
| `android:foregroundServiceType` 값: `camera, connectedDevice, dataSync, health, location, mediaPlayback, mediaProjection, microphone, phoneCall, remoteMessaging, shortService, specialUse, systemExempted`, `|`로 복수 지정 가능. `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 등 상수는 API 29, `SHORT_SERVICE`/`SPECIAL_USE`는 API 34 | https://developer.android.com/guide/topics/manifest/service-element , https://developer.android.com/reference/android/content/pm/ServiceInfo |
| Android 14(targetSdk 34+): 타입 미선언 시 `MissingForegroundServiceTypeException`, 타입별 권한 미선언 시 `SecurityException`, manifest에 없는 타입을 `startForeground()`에 넘기면 `IllegalArgumentException` | https://developer.android.com/about/versions/14/changes/fgs-types-required , https://developer.android.com/develop/background-work/services/fgs/launch |
| `specialUse`: 권한 `FOREGROUND_SERVICE_SPECIAL_USE`(API 34, `normal|appop|instant`), 런타임 전제 없음, 타임아웃 명시 없음, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property로 자유 서술 필수, Play 심사 대상 | https://developer.android.com/develop/background-work/services/fgs/service-types#special-use , https://developer.android.com/reference/android/Manifest.permission#FOREGROUND_SERVICE_SPECIAL_USE |
| `dataSync`: 업로드/다운로드/백업/파일 처리 용도. Android 15(targetSdk 35+)에서 24시간당 6시간 합산 제한(`Service.onTimeout(int,int)` 후 몇 초 내 `stopSelf()` 안 하면 크래시), `BOOT_COMPLETED`에서 시작 금지 → 타이머 용도 부적합 | https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync , https://developer.android.com/about/versions/15/behavior-changes-15 |
| `shortService`: 약 3분, sticky 불가, 다른 FGS 시작 불가, 타입별 권한 불필요 → 부적합 | https://developer.android.com/develop/background-work/services/fgs/service-types#short-service |
| 타입에 맞는 용도가 없으면 WorkManager 또는 user-initiated data transfer job으로 이전할 것을 "strongly recommend" | https://developer.android.com/about/versions/14/changes/fgs-types-required |
| Android 12(targetSdk 31+): 백그라운드에서 FGS 시작 불가(`ForegroundServiceStartNotAllowedException`). 예외: 사용자 UI 조작(알림·위젯·액티비티), 정확 알람, `ACTION_BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` 수신, `TIMEZONE_CHANGED`/`TIME_CHANGED`/`LOCALE_CHANGED` 수신, 배터리 최적화 해제된 앱, `SYSTEM_ALERT_WINDOW`(15+는 가시 오버레이 필요) 등 | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start |
| `startForegroundService()`(API 26) 후 "ANR interval에 준하는 시간" 내 `startForeground()` 호출 필수, 아니면 프로세스 크래시(`ForegroundServiceDidNotStartInTimeException`) | https://developer.android.com/reference/android/content/Context#startForegroundService(android.content.Intent) |
| Android 15(targetSdk 35+): `BOOT_COMPLETED` 수신기에서 `dataSync, camera, mediaPlayback, phoneCall, mediaProjection, microphone` FGS 시작 금지. `specialUse`는 목록에 없음 | https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed |
| Android 12+(모든 앱): FGS 알림 표시를 최대 10초 지연할 수 있음(예외 있음) | https://developer.android.com/about/versions/12/behavior-changes-all |
| Play Console: targetSdk 34+ 앱은 **Policy > App content**에서 FGS 타입별 선언(기능 설명, 지연/중단 시 사용자 영향, 기능을 재현하는 동영상 링크, 용도 선택). "In limited scenarios… you may declare the foreground service TYPE_SPECIAL_USE type. All foreground service types are subject to review." FGS 권한은 설치 시 자동 부여되며 사용자가 회수 불가 | https://support.google.com/googleplay/android-developer/answer/13392821 |
| Play 정책(Device and Network Abuse › FGS): 사용자에게 유익하고 핵심 기능과 관련, 사용자가 시작했거나 사용자가 인지 가능, 시스템이 중단/지연하면 부정적 경험, 작업에 필요한 시간만 실행. `systemExempted`, `shortService`, Play Asset Delivery용 `dataSync`는 예외 | https://support.google.com/googleplay/android-developer/answer/9888379 |

### 3.3 배터리 최적화·Doze·App Standby Buckets

| 사실 | 출처 |
|---|---|
| Doze 중: 네트워크 중단, **wake lock 무시**, `setExact()`/`setWindow()` 알람은 유지보수 창으로 연기, JobScheduler/WorkManager 실행 안 됨. `setAlarmClock()`은 정상 발화. `set(Exact)AndAllowWhileIdle()`은 앱당 9분에 1회 제한 | https://developer.android.com/training/monitoring-device-state/doze-standby |
| Doze 문서는 "FGS는 Doze 면제"라고 **명시하지 않음**. 명시된 것은 (a) FGS가 있으면 App Standby의 idle 판정 조건에서 제외, (b) "Don't start a foreground service just to prevent the system from determining that your app is idle" | https://developer.android.com/training/monitoring-device-state/doze-standby |
| 리소스 한도 표: "App process is running a foreground service" 행 → 잡/알람은 standby bucket 기준으로 제한, 네트워크는 무제한. 화면 꺼짐+Doze 시 while-idle 알람 시간당 7회 | https://developer.android.com/topic/performance/power/power-details |
| 장기 실행 FGS를 돌리는 앱은 **active bucket**에 들어감(active: 알람/잡 최소 제한). `restricted` bucket은 하루 알람 1회, 잡 하루 1회 10분 | https://developer.android.com/topic/performance/appstandby |
| 정확 알람: targetSdk 31+는 `SCHEDULE_EXACT_ALARM` 선언 필요, `canScheduleExactAlarms()`로 확인, 회수 시 알람 전부 삭제. Android 14부터 targetSdk 33+ 신규 설치 앱에는 **기본 거부**. `USE_EXACT_ALARM`(API 33)은 자동 부여되지만 Play 정책상 알람시계/캘린더 앱만 허용. `OnAlarmListener` 기반 `setExact`는 권한 불필요 | https://developer.android.com/develop/background-work/services/alarms/schedule , https://developer.android.com/about/versions/14/changes/schedule-exact-alarms , https://support.google.com/googleplay/android-developer/answer/9888170 |
| 부정확 알람: targetSdk 31+에서 `setWindow()` 창은 최소 10분으로 클리핑, `setInexactRepeating()`은 1시간 내 발화 | https://developer.android.com/develop/background-work/services/alarms/schedule |
| WorkManager 주기 작업 최소 15분, 실행 시각은 제약과 시스템 최적화에 좌우, Doze 영향 받음 | https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work |
| 배터리 최적화 예외(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) 요청은 Play 정책상 "핵심 기능이 저해되는 경우"만 허용. 허용 예: FCM 불가 메시징, 안전 앱, 작업 자동화, 주변기기 상시 연결. "주기적 동기화" 류는 불가 | https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases |
| 이 앱에 대한 함의: 타이머는 **틱을 세지 않고 타임스탬프 차이**로 계산하므로 Doze의 wake lock/알람 제한은 정확도에 영향 없음. 필요한 것은 FGS 프로세스가 `SCREEN_OFF`를 받는 순간 디스크에 기록하는 것뿐 | (위 출처들의 종합) |
| **OEM(2차 출처, 미검증)**: dontkillmyapp.com은 Xiaomi(MIUI Autostart·배터리 세이버가 FGS 종료), Samsung("Put unused apps to sleep" 3일 규칙, One UI 6.0부터 targetSdk 34 앱 FGS 보장 주장)을 상위 위반자로 나열. Google 1차 문서로는 확인 불가 | https://dontkillmyapp.com/samsung , https://dontkillmyapp.com/xiaomi (2차) |

### 3.4 UsageStats·AccessibilityService 필요 여부와 대안

| 사실 | 출처 |
|---|---|
| `PACKAGE_USAGE_STATS`(API 23): 보호 수준 `signature|privileged|development|appop|retailDemo`. 선언 후 사용자가 설정(`Settings.ACTION_USAGE_ACCESS_SETTINGS`)에서 직접 허용해야 함 | https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS , https://developer.android.com/reference/android/app/usage/UsageStatsManager |
| `UsageStatsManager.queryEvents()`: "Events are only kept by the system for a few days", Android 11+에서 사용자 잠금 해제 전에는 null | https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryEvents(long,%20long) |
| 이벤트 타입(API 28): `SCREEN_INTERACTIVE`(15), `SCREEN_NON_INTERACTIVE`(16), `KEYGUARD_SHOWN`(17, 화면 꺼짐 여부 무관), `KEYGUARD_HIDDEN`(18, "typically happens when the user unlocks their phone"); API 29: `DEVICE_SHUTDOWN`(26), `DEVICE_STARTUP`(27). `getTimeStamp()`는 epoch ms | https://developer.android.com/reference/android/app/usage/UsageEvents.Event |
| **결론: 실시간 잠금/해제 감지에는 UsageStats가 필요 없다.** 필요해지는 경우는 "프로세스 사망 중 발생한 이벤트의 사후 복구"뿐 | (3.1, 3.5 종합) |
| Play의 `PACKAGE_USAGE_STATS` 전용 정책 조항은 "Permissions and APIs that Access Sensitive Information" 페이지에서 **찾지 못함**(미확인). 일반 원칙만 적용: "You may only request permissions and APIs that access sensitive information that are necessary to implement current features… promoted in your Google Play listing." | https://support.google.com/googleplay/android-developer/answer/9888170 |
| AccessibilityService: Play는 장애인 지원이 주목적인 앱(`isAccessibilityTool="true"`)이 아니면 Permission Declaration Form과 승인이 필요하며, 자율적 행동을 하는 자동화는 금지. 잠금 감지 용도로는 정당화 불가 → **사용 금지** | https://support.google.com/googleplay/android-developer/answer/10964491 |
| 폴링 대안: `KeyguardManager.isKeyguardLocked()`(API 16, 잠금 화면 표시 여부), `isDeviceLocked()`(API 22, 인증 필요 여부; 스와이프는 잠금 아님), `isDeviceSecure()`(API 23). 권한 불필요 | https://developer.android.com/reference/android/app/KeyguardManager |
| `PowerManager.isInteractive()`(API 20; `isScreenOn()` deprecated) — 대화 상태, dreaming 중에도 true | https://developer.android.com/reference/android/os/PowerManager#isInteractive() |
| `Display.getState()`(API 20): `STATE_OFF/ON/DOZE/…` 실제 패널 상태 | https://developer.android.com/reference/android/view/Display#getState() |
| `KeyguardManager.addKeyguardLockedStateListener()`(API 33)는 `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE`(`signature|privileged|module|role`) 필요 → 일반 앱 사용 불가 | https://developer.android.com/reference/android/app/KeyguardManager , https://developer.android.com/reference/android/Manifest.permission#SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE |

### 3.5 재부팅·강제 종료·프로세스 사망 후 복구

| 사실 | 출처 |
|---|---|
| `RECEIVE_BOOT_COMPLETED`(normal)로 `ACTION_BOOT_COMPLETED` 수신 가능(예외 목록에 있어 manifest 등록 OK). Android 15부터는 부팅 후뿐 아니라 **Stopped 상태에서 벗어날 때(강제 종료 후 첫 실행)** 에도 전달됨 | https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED , https://developer.android.com/reference/android/Manifest.permission#RECEIVE_BOOT_COMPLETED |
| `ACTION_LOCKED_BOOT_COMPLETED`(API 24)는 `directBootAware="true"` 컴포넌트에 잠금 해제 전 전달, 이때는 device-protected storage만 접근 가능 | https://developer.android.com/privacy-and-security/direct-boot |
| BOOT_COMPLETED에서 FGS 시작은 Android 12 백그라운드 제한의 예외이며, Android 15 금지 목록에 `specialUse`는 없음 | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start , https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed |
| `START_STICKY`: 프로세스가 죽으면 started 상태 유지, 시스템이 재생성 시도, null intent로 `onStartCommand()` 호출될 수 있음. `START_REDELIVER_INTENT`는 마지막 intent 재전달 | https://developer.android.com/reference/android/app/Service#START_STICKY |
| Android 13+ FGS Task Manager "Stop": 앱 전체가 메모리에서 제거되고 **콜백 없음**, 예약된 잡/알람은 그대로 실행됨, targetSdk 무관. 재시작 시 `ApplicationExitInfo`의 `REASON_USER_REQUESTED`로 확인 가능 | https://developer.android.com/about/versions/13/changes/fgs-manager |
| `ApplicationExitInfo`(API 30): `REASON_USER_REQUESTED`(강제 종료·최근 앱에서 제거), `REASON_LOW_MEMORY`, `REASON_EXCESSIVE_RESOURCE_USAGE` 등과 종료 타임스탬프 제공 → "언제 죽었는지"는 알 수 있음 | https://developer.android.com/reference/android/app/ApplicationExitInfo |
| 죽어 있는 동안의 잠금 **시각**은 직접 알 수 없음(브로드캐스트 재전송 없음, 3.1). 가능한 것: (a) `PACKAGE_USAGE_STATS` 보유 시 `queryEvents()`로 `KEYGUARD_SHOWN`/`SCREEN_NON_INTERACTIVE` 시각 조회(며칠만 보관), (b) 자체 heartbeat 타임스탬프로 하한 추정, (c) `ApplicationExitInfo.getTimestamp()`로 사망 시각 상한 | 3.1, 3.4 출처 |
| `UsageEvents.Event.DEVICE_SHUTDOWN` 타임스탬프는 "실제 종료 전 마지막 DB 저장 시각"이며, `DEVICE_SHUTDOWN`~`DEVICE_STARTUP` 사이 닫히지 않은 이벤트는 무시해야 함 | https://developer.android.com/reference/android/app/usage/UsageEvents.Event#DEVICE_SHUTDOWN |
| 세션 시작 시각 영속화 권장 필드: wall clock(`System.currentTimeMillis()`) + `SystemClock.elapsedRealtime()`(부팅 감지·시계 변경 방어), `ACTION_TIME_CHANGED`/`TIMEZONE_CHANGED`는 manifest 수신 가능 | https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions |

### 3.6 스토어·플랫폼 요구사항 (minSdk, 알림 권한, 알림 해제, 데이터 안전)

| 사실 | 출처 |
|---|---|
| Play targetSdk: 2025-08-31부터 API 35, **2026-08-31부터 신규 앱·업데이트는 API 36(Android 16)** 필수 | https://support.google.com/googleplay/android-developer/answer/11926878 |
| 동작이 바뀌는 SDK 레벨: 23(Doze/App Standby, `PACKAGE_USAGE_STATS`), 24(direct boot), 26(암시적 브로드캐스트 제한, `startForegroundService`), 28(`FOREGROUND_SERVICE` 권한, UsageEvents KEYGUARD/SCREEN 이벤트), 29(`foregroundServiceType` 상수), 30(`ApplicationExitInfo`), 31(백그라운드 FGS 시작 제한, 정확 알람 권한), 33(`POST_NOTIFICATIONS`, Task Manager), 34(FGS 타입 필수, 알림 해제 가능, receiver export 플래그), 35(dataSync 6h, BOOT_COMPLETED 타입 제한) | 위 각 항목 출처 |
| minSdk 권장: **28** — 그 이하에서는 FGS 권한·UsageEvents 잠금 이벤트가 없어 코드 경로가 갈라짐. 26으로 낮추면 `FOREGROUND_SERVICE` 권한 분기만 추가되므로 결정 사항(§5) | (종합) |
| `POST_NOTIFICATIONS`(API 33, dangerous): FGS 시작에는 권한이 필요 없지만, 사용자가 거부하면 FGS 알림이 **알림 창에는 안 보이고 Task Manager에만 보임** | https://developer.android.com/develop/ui/views/notifications/notification-permission , https://developer.android.com/about/versions/13/behavior-changes-13 |
| Android 14(모든 앱): `setOngoing(true)` 알림도 사용자가 **스와이프로 해제 가능**. 잠금 화면에서·"Clear all"에서는 여전히 해제 불가. `CallStyle`, DPC, 미디어 알림 등은 예외 | https://developer.android.com/about/versions/14/behavior-changes-all |
| 알림을 해제해도 FGS 자체가 중단된다는 문장은 1차 문서에서 찾지 못함(미확인). 안전하게 "알림이 사라져도 서비스는 계속 실행" 전제로 설계하고 실기기 검증 필요 | — |
| Play Data safety: 데이터를 수집하지 않는 앱도 폼 제출과 **개인정보처리방침 링크** 필수. "Collect" = "transmitting data from your app off a user's device" → 기기 내 타임스탬프만 저장하면 수집 항목 없음으로 신고 가능 | https://support.google.com/googleplay/android-developer/answer/10787469 |

## 4. 스토어 정책 리스크 목록

| 리스크 | 심각도 | 해당 정책·출처 | 완화책 |
|---|---|---|---|
| `specialUse` FGS 선언이 Play 심사에서 거부됨("사용자 인지 불가/불필요한 상시 실행"으로 판단) | 높음 | Device and Network Abuse › FGS 정책 https://support.google.com/googleplay/android-developer/answer/9888379 ; FGS 선언 https://support.google.com/googleplay/android-developer/answer/13392821 | 사용자가 명시적으로 "추적 시작/중지"; 알림에 실시간 경과 시간과 중지 버튼 표시; `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`와 Play 선언문에 "unlock-to-lock usage timer, user-initiated, on-device only" 서술; 트리거 과정을 담은 데모 영상 준비; 대안 경로(UsageStats 기반 사후 집계) 설계 보유 |
| Play 선언 없이 targetSdk 34+ 업데이트 제출 → 심사 지연/거부 | 중간 | https://support.google.com/googleplay/android-developer/answer/13392821 | 릴리스 체크리스트에 App content › Foreground service 선언 포함 |
| `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` 선언 시 정책 위반(알람시계/캘린더 아님) | 높음(선언할 경우) / 없음(미선언) | https://support.google.com/googleplay/android-developer/answer/9888170 | 정확 알람 미사용. 알림 갱신은 FGS 내부 타이머(Handler/coroutine)로, 백그라운드 스케줄은 WorkManager로 |
| `PACKAGE_USAGE_STATS` 요청이 "핵심 기능에 불필요한 민감 API"로 판단 | 중간 | 일반 원칙만 확인 https://support.google.com/googleplay/android-developer/answer/9888170 (전용 조항 미확인) | 기본 기능은 권한 없이 동작; 사용자 opt-in "정밀 복구" 옵션으로만 요청하고 설정 화면에서 근거 설명 |
| AccessibilityService 사용 | 높음 | https://support.google.com/googleplay/android-developer/answer/10964491 | 사용하지 않음 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 요청이 정책 위반 | 중간 | https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases | 요청하지 않음(타임스탬프 기반이라 불필요). OEM 설정 안내는 앱 내 도움말 텍스트로만 |
| Data safety 미제출/개인정보처리방침 누락 | 낮음(작업량 적음, 누락 시 게시 불가) | https://support.google.com/googleplay/android-developer/answer/10787469 | 방침 페이지 작성, "수집 없음"으로 신고 |
| `POST_NOTIFICATIONS` 거부 시 사용자가 FGS를 인지 못해 "몰래 실행"으로 보임 | 낮음 | https://developer.android.com/develop/ui/views/notifications/notification-permission | 추적 시작 전 권한 요청, 거부 시 앱 내 배너로 실행 상태 표시 |

## 5. 결정에 필요한 남은 질문 (사람이 결정)

1. **아키텍처 선택**: (A) `specialUse` FGS 상시 실행(실시간, Play 심사 리스크) vs (B) `PACKAGE_USAGE_STATS` 기반 사후 집계(FGS 불필요, 사용자가 설정에서 Usage access 허용해야 하고 "며칠" 보관 창 안에 동기화 필요) vs (C) A를 기본으로 하고 B를 opt-in 정밀 복구로 두는 하이브리드(본 문서 권장). 어느 리스크를 감수할 것인가?
2. **세션 정의**: 세션 종료를 `ACTION_SCREEN_OFF`(화면 꺼짐)로 볼지, `KEYGUARD` 표시(잠금)로 볼지. 화면 시간 초과로 꺼진 뒤 곧바로 다시 켜서 잠금 해제한 경우를 같은 세션으로 이어 붙일 유예 시간(예: 30초)을 둘 것인가?
3. **추적 스위치의 지속성**: 사용자가 Task Manager "Stop"으로 종료하거나 강제 종료하면 다음 앱 실행 전까지 추적이 멈춘다. 이때 앱 실행 시 자동 재개할지, 사용자에게 재개를 물을지.
4. **minSdk**: 28(권장) vs 26. 26을 택하면 FGS 권한 분기·UsageEvents 잠금 이벤트 부재를 감수해야 한다.
5. **heartbeat 주기**: 프로세스 사망 시 손실 허용치(예: 1분)와 디스크 쓰기 빈도의 트레이드오프.
6. **Android 14 receiver 플래그**: 시스템 브로드캐스트 전용 등록 시 플래그 생략 vs `RECEIVER_EXPORTED` — 두 1차 문서가 상충하므로 실기기(API 34/35/36) 검증 후 확정.
7. **알림 해제 후 동작**: Android 14+에서 사용자가 FGS 알림을 스와이프한 뒤에도 서비스가 유지되는지 실기기 검증 필요(1차 문서 미확인). 유지되지 않으면 재표시 정책 결정.
8. **Play 선언용 데모 영상·문구**: 누가 언제 만들 것인가(심사 전 필수).

## 6. 출처 목록

Android 개발자 문서
- https://developer.android.com/develop/background-work/background-tasks/broadcasts
- https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
- https://developer.android.com/about/versions/oreo/background
- https://developer.android.com/reference/android/content/Intent (ACTION_SCREEN_OFF/ON, ACTION_USER_PRESENT, ACTION_USER_UNLOCKED, ACTION_BOOT_COMPLETED, ACTION_LOCKED_BOOT_COMPLETED)
- https://developer.android.com/reference/android/app/BroadcastOptions
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/develop/background-work/services/fgs/declare
- https://developer.android.com/develop/background-work/services/fgs/launch
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/about/versions/14/behavior-changes-14
- https://developer.android.com/about/versions/14/behavior-changes-all
- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/about/versions/13/changes/fgs-manager
- https://developer.android.com/about/versions/13/behavior-changes-13
- https://developer.android.com/about/versions/12/behavior-changes-12
- https://developer.android.com/about/versions/12/behavior-changes-all
- https://developer.android.com/guide/topics/manifest/service-element
- https://developer.android.com/reference/android/content/pm/ServiceInfo
- https://developer.android.com/reference/android/content/Context
- https://developer.android.com/reference/android/app/Service
- https://developer.android.com/reference/android/Manifest.permission
- https://developer.android.com/develop/background-work/services/alarms/schedule
- https://developer.android.com/reference/android/app/AlarmManager
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/topic/performance/appstandby
- https://developer.android.com/topic/performance/power/power-details
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/reference/android/app/usage/UsageStatsManager
- https://developer.android.com/reference/android/app/usage/UsageEvents.Event
- https://developer.android.com/reference/android/app/KeyguardManager
- https://developer.android.com/reference/android/os/PowerManager
- https://developer.android.com/reference/android/view/Display
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://developer.android.com/privacy-and-security/direct-boot
- https://developer.android.com/develop/ui/views/notifications/notification-permission

Google Play 정책
- https://support.google.com/googleplay/android-developer/answer/13392821 (Foreground service 선언)
- https://support.google.com/googleplay/android-developer/answer/9888379 (Device and Network Abuse, FGS 권한)
- https://support.google.com/googleplay/android-developer/answer/9888170 (Permissions and APIs that Access Sensitive Information, Exact Alarm)
- https://support.google.com/googleplay/android-developer/answer/10964491 (AccessibilityService API)
- https://support.google.com/googleplay/android-developer/answer/10787469 (Data safety)
- https://support.google.com/googleplay/android-developer/answer/11926878 (Target API level)

AOSP
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/Notifier.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java

2차 출처(미검증, OEM 동작만)
- https://dontkillmyapp.com/samsung
- https://dontkillmyapp.com/xiaomi
