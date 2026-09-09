# 스토어 제출 자료

Play Console 제출 때 그대로 복사해 쓰는 답변 모음이다. 정본은 [구현 스펙 9절](spec/usingtime-spec.md)이고, 이 문서는 제출 화면 순서대로 펼쳐 놓은 것이다. 값이 바뀌면 스펙을 먼저 고친다.

## 1. 빌드 식별

| 항목 | 값 |
|---|---|
| applicationId / namespace | `io.github.sihun0927.usingyourtime` |
| minSdk / targetSdk / compileSdk | 28 / 36 / 36 |
| 앱 이름·라벨 (스토어·홈 화면 동일) | UsingTime |
| 스토어 기본 언어 | ko-KR |
| 카테고리 | 생산성 |
| `android:allowBackup` | false |
| 개인정보처리방침 URL | <https://sihun0927-sketch.github.io/UsingYourTime/privacy-policy> |

## 2. 포그라운드 서비스(FGS) 선언

**App content › Foreground service permissions**에서 답하는 항목이다. 타입은 `specialUse`이고, subtype property 값과 선언문은 **영문 원문 그대로** 넣는다.

- **타입**: `specialUse` (`android.permission.FOREGROUND_SERVICE_SPECIAL_USE`)
- **subtype property 값** (manifest의 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, Console 제출문과 같은 문장):

```
User-initiated unlock-to-lock continuous usage timer. Runs only while the user keeps tracking on; shows live elapsed time in a persistent notification; on-device only; user can stop anytime from the app.
```

- **선언문 (Console 입력란)**: 위와 같은 문장을 쓴다.
- **시스템이 서비스를 중단하면**: 연속 사용 세션 감지가 멈추고 상시 표시와 임계값 알림이 더 오지 않는다. 사용자가 앱을 다시 열 때까지 측정이 비어 있다.
  영문: `If the system stops the service, session detection stops and neither the persistent notification nor threshold alerts are delivered until the user opens the app again.`
- **다른 FGS 타입을 쓰지 않는 이유**: 위치·미디어·통화·데이터 동기화 등 기존 타입 중 "잠금 해제~잠금 사이의 연속 사용 시간을 사용자가 켠 동안만 재는" 용도에 해당하는 것이 없다.

manifest 반영 형태(#19에서 아래 모양으로 들어갔다):

```xml
<service
    android:name=".tracking.TrackingService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-initiated unlock-to-lock continuous usage timer. Runs only while the user keeps tracking on; shows live elapsed time in a persistent notification; on-device only; user can stop anytime from the app." />
</service>
```

## 3. 데모 영상

사용자가 실기기에서 직접 촬영하고 **비공개(일부 공개) YouTube 링크**로 제출한다. 화면 녹화 하나에 아래 순서를 끊지 않고 담는다.

1. 앱을 열고 설정 화면에서 **측정 시작**을 탭한다.
2. 알림 권한 요청 대화상자에서 **허용**을 누른다.
3. 상태바를 내려 **상시 표시**에 연속 사용 시간이 늘어나는 것을 보여준다.
4. 임계값에 도달해 **임계값 알림**이 뜨는 것을 보여주고, 알림의 **이번 세션 알림 끄기** 액션을 누른다.
5. 앱으로 돌아와 설정 화면에서 **측정 중지**를 눌러 서비스가 끝나는 것을 보여준다.

촬영 팁: 임계값을 최솟값(10분)으로 낮춰 두면 4번까지 한 번에 찍기 쉽다. 얼굴·계정·알림 내용 등 개인정보가 화면에 들어가지 않게 한다.

## 4. Data safety

| 질문 | 답 |
|---|---|
| 데이터를 수집하거나 공유합니까? | 아니요 (No data collected, no data shared) |
| 데이터가 전송 중 암호화됩니까? | 해당 없음 (전송하는 데이터가 없음) |
| 사용자가 데이터 삭제를 요청할 수 있습니까? | 해당 없음. 데이터는 기기에만 있고 앱 삭제·데이터 삭제로 사용자가 직접 지운다 |
| 개인정보처리방침 URL | <https://sihun0927-sketch.github.io/UsingYourTime/privacy-policy> |

근거: `INTERNET` 권한이 없고 광고·분석 SDK가 없다. 설정은 DataStore, 세션 기록은 Room으로 앱 전용 저장 영역에만 남으며 `allowBackup=false`다.

## 5. 기타 선언

| 항목 | 답 |
|---|---|
| 광고 | 없음 |
| 인앱 구매 | 없음 |
| IARC 콘텐츠 등급 설문 | 유틸리티/생산성으로 응답 → 전체 이용가 예상 |
| 대상 연령층 | 아동 대상 아님 (18세 이상 및 일반 사용자) |
| 정부·금융·건강·뉴스 앱 | 모두 아님 |
| 앱 액세스 권한 | 제한 없음 (로그인·특별한 설정 없이 모든 기능 사용 가능) |
| 데이터 안전 · 방침 URL | 4절과 동일 |
| 민감 권한 (위치·SMS·통화기록·`PACKAGE_USAGE_STATS`·정확한 알람) | 선언하지 않음 |

선언하는 권한은 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` 4개가 전부다(스펙 8절).

## 6. 개인정보처리방침 페이지

- 원본: [`docs/privacy-policy.md`](privacy-policy.md) (한·영 병기)
- 공개 주소: <https://sihun0927-sketch.github.io/UsingYourTime/privacy-policy>
- 발행은 GitHub Pages다. 저장소 **Settings › Pages**에서 Source를 `Deploy from a branch`, Branch를 `main` + `/docs`로 두면 `docs/_config.yml`과 방침 문서의 front matter가 위 주소를 만든다. 저장소 설정 변경이라 사람이 한 번 켜야 한다.
- 방침 문서가 `main`에 올라간 뒤에 주소가 살아난다(`main` 머지는 릴리스 브랜치를 통해서만 한다).

## 7. 릴리스 체크리스트

스펙 9절의 순서 고정 절차다. 앞 단계가 끝나야 다음 단계로 간다.

1. 빌드 완성, 실기기 검증(복구 경로 포함)
2. 개인 개발자 계정 등록 (US$25, 본인 확인에 며칠)
3. 테스터 최소 12명 확보 (본인 제외, 이탈 대비 14~15명)
4. 비공개 테스트 트랙에 올려 12명 참여 상태로 연속 14일 유지
5. Play Console 선언 제출 (FGS·Data safety·방침 URL·데모 영상 = 이 문서 2~5절)
6. 프로덕션 접근 신청 → 검토 → 출시. 심사 거절 시 선언문·영상·문구만 고쳐 1회 재제출. 재거절이면 이 스펙 밖(새 지도)
