# PROTOTYPE: START_STICKY 재시작 시 startForeground 가능 여부 (Android 12+)

**버리는 코드.** 티켓 #8의 질문 하나에 답하기 위한 스텁이다. 프로덕션에 옮기지 않는다.

## 질문

시스템이 프로세스를 죽인 뒤 `START_STICKY` 서비스를 되살릴 때(`onStartCommand(intent == null)`),
앱이 백그라운드(화면 잠금)인 상태에서 `startForeground()`가 성공하는가,
아니면 `ForegroundServiceStartNotAllowedException`으로 막히는가.

## 실행

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

미리 빌드한 `StickyProbe-debug.apk`도 이 폴더에 있다.

## 기기 테스트 절차 (Android 12 이상 실기기)

1. StickyProbe 실행 → 알림 권한 허용
2. `1. Start FGS` 탭 → 상태바에 "startForeground OK (restart=false)" 알림 확인
3. `2. Kill process in 15s` 탭 → **즉시 전원 버튼으로 화면 잠금**
4. 1분 기다린 뒤 잠금 해제, 앱 다시 열기 (로그가 자동 갱신됨)

## 판독

로그(`files/probe.log`, 또는 `adb logcat -s StickyProbe`)에서 `killing self now` 이후 줄을 본다.

- `onStartCommand restart=true` + `startForeground OK` → **가능**. 세션 복구는 START_STICKY 재시작에 기대도 된다.
- `onStartCommand restart=true` + `startForeground FAILED notAllowed=true` → **불가**. 재시작은 되지만 FGS 승격이 막힌다. 복구 설계 변경 필요.
- `killing self now` 뒤에 아무 줄도 없음 → 시스템이 재시작 자체를 안 함(제조사 배터리 정책 등). 재시작 지연은 수 초~수 분이므로 5분까지 기다려 본다.

각 줄의 `screenOn`/`locked`/`importance`로 그 순간 앱이 실제로 백그라운드였는지 확인한다.
`importance=400`(CACHED)이나 `locked=true`면 백그라운드 조건이 맞다.
