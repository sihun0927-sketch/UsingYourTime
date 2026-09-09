# UsingYourTime

휴대폰을 잠금 해제한 뒤 끊김 없이 계속 사용한 시간을 알려주는 Android 앱 "UsingTime". 구현 스펙 `docs/spec/usingtime-spec.md`가 정본이고, 도메인 용어는 `CONTEXT.md`가 정본이다. 아직 앱 코드는 없다.

## Agent skills

### Issue tracker

이슈는 GitHub Issues(`sihun0927-sketch/UsingYourTime`)에 있고, `gh` CLI로 다룬다. See `docs/agents/issue-tracker.md`.

### Triage labels

기본 5개 라벨(`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`)을 그대로 쓴다. See `docs/agents/triage-labels.md`.

### Domain docs

single-context 레이아웃: 루트 `CONTEXT.md` + `docs/adr/`. See `docs/agents/domain.md`.

## Git & PR 규칙

### 브랜치 (git-flow)

| 브랜치 | 분기 원점 | 머지 대상 | 머지 방식 | 누가 |
|---|---|---|---|---|
| `main` | | release·hotfix만 받는 배포 이력 | | 사용자 |
| `develop` | `main` | 다음 릴리스 통합 | | 파이프라인 세션 |
| `feature/<n>-<slug>` | `develop` | `develop` | squash | 에이전트 |
| `release/<versionName>` | `develop` | `main` + `develop` | merge commit, `main`에 `v<versionName>` 태그 | 사용자 |
| `hotfix/<versionName>` | `main` | `main` + `develop` | merge commit | 구현은 에이전트, 머지는 사용자 |
| `prototype/<n>-<slug>`, `research/<n>-<slug>` | `develop` | 머지하지 않음 | | wayfinder 티켓 |

- 모든 변경은 브랜치를 따서 PR로 넣는다. 문서 한 줄도 같다. `develop`·`main`에 직접 커밋하지 않는다.
- `main`으로의 머지, `release/*` 생성, 태그는 사용자만 한다. 에이전트의 `gh pr merge`는 `--base develop` PR에만 쓴다.
- `release/*`에서 하는 일은 `versionCode` +1, `versionName` SemVer 올리기, QA·버그 수정, 스펙 9절 릴리스 체크리스트뿐이다. 기능은 `develop`에서.
- `prototype/*`·`research/*`는 티켓의 질문에 답한 증거로만 남긴다. 결론은 이슈에 있고, 프로덕션 코드는 새로 쓴다.

### 커밋·PR

- 커밋 제목: `feat|fix|docs|test|refactor|chore|ci: 한국어 제목`. 본문에 관련 이슈를 `#n`으로 참조.
- PR 제목은 커밋 제목과 같은 형식이다(squash 뒤 `develop`의 커밋 메시지가 된다). 본문은 `.github/PULL_REQUEST_TEMPLATE.md`를 채우고 `Closes #n`을 쓴다.
- 자율 범위는 세션 종류로 정한다.
  - `/implement <n>`으로 시작한 **파이프라인 세션**: 아래 절차대로 커밋·push·PR·`develop` 머지까지 스스로 끝낸다.
  - 그 외 **대화형 세션**: 작업 브랜치에서 커밋은 자유, push와 PR 열기는 사용자가 요청할 때.

### 파이프라인: `ready-for-agent` 이슈

사람이 이슈마다 `/implement <n>`을 입력하고, 끝나면 `/clear`한다. 다음 세션에 남는 것은 이슈 코멘트·PR·커밋뿐이므로 진행 상황·결정·막힌 점은 거기에 적는다.

1. **선택**: `ready-for-agent` 라벨 + 열린 blocker 0 + 미할당. 프론티어 쿼리는 `docs/agents/issue-tracker.md`의 Wayfinding operations.
2. **claim**: `gh issue edit <n> --add-assignee @me`. 세션의 첫 쓰기.
3. **분기**: `git switch develop && git pull`, `git switch -c feature/<n>-<slug>`.
4. **구현**: `/implement <n>`. `session` 패키지는 `/tdd`로, 나머지 패키지는 테스트 없이 구현한다. 끝에 `/code-review`로 셀프리뷰.
5. **스모크**: 변경이 `tracking`·`notification`·`ui`에 닿으면 에뮬레이터에 `installDebug`하고 측정 시작 → 상시 표시가 뜨는지, logcat에 오류가 없는지 본다. 스크린샷·로그를 PR Test plan에 붙인다.
6. **최신화**: `git merge develop`. 충돌은 `/resolving-merge-conflicts`. 그 뒤 `/code-review develop`을 한 번 더.
7. **PR·머지**: push → `gh pr create --base develop` → `gh pr checks --watch` → 초록불이면 `gh pr merge --squash --delete-branch`. 빨간불이면 머지하지 않고 원인을 PR에 적는다.
8. **정리**: `git switch develop && git pull`, `git branch -d feature/<n>-<slug>`. 완료를 보고하면 사용자가 `/clear`.

출구:

- 원인이 바로 안 보이는 테스트 실패·회귀·플레이크 → `/diagnosing-bugs`.
- 수용 기준을 만족할 수 없거나 스펙에 빈칸 → 막힌 지점을 이슈 코멘트로 남기고, 라벨을 `ready-for-agent` → `needs-info`로, `gh issue edit <n> --remove-assignee @me`, 브랜치 push, 정지.
- 키스토어·Play Console·CI 시크릿처럼 사람만 할 수 있는 단계 → `/wizard`로 스크립트를 만들고 경로를 이슈에 코멘트, 정지.
- 새 도메인 용어나 되돌리기 어려운 결정 → `/domain-modeling`으로 `CONTEXT.md` / `docs/adr/`에 기록.

## 명령어

Git Bash에서는 `./gradlew`, PowerShell에서는 `.\gradlew.bat`. 아래는 Git Bash 기준. 앱 id는 `io.github.sihun0927.usingyourtime`, 로그 태그는 `UsingTime` 하나로 고정.

| 언제 | 명령 |
|---|---|
| 커밋 전 | `./gradlew test` |
| PR 전 | `./gradlew test assembleDebug` |
| 에뮬레이터 기동 | `emulator -list-avds`로 이름 확인(현재 `StickyProbe_API35`) → `emulator -avd <이름> &` |
| 설치·실행 | `./gradlew installDebug && adb shell am start -n io.github.sihun0927.usingyourtime/.MainActivity` |
| 로그 | `adb logcat -s UsingTime:*` |
| 잠금 / 화면 켜짐 / 잠금 해제 | `adb shell input keyevent KEYCODE_POWER` / `adb shell input keyevent KEYCODE_WAKEUP` / `adb shell wm dismiss-keyguard` |
| 프로세스 강제 종료 재현 | `adb shell am kill io.github.sihun0927.usingyourtime` |
| 이슈 읽기 | `gh issue view <n> --comments` |
| PR | `gh pr create --base develop`, `gh pr checks <n> --watch`, `gh pr merge <n> --squash --delete-branch` |

## 코드 스타일

Kotlin 공식 스타일(`kotlin.code.style=official`), 린터 없음. 식별자는 영어, 주석·문서·테스트 이름은 한국어. 도메인 개념의 영어 이름은 `CONTEXT.md`의 식별자를 그대로 쓴다.

패키지 `io.github.sihun0927.usingyourtime` 아래:

| 패키지 | 담당 | 스펙 |
|---|---|---|
| `session` | 세션 상태 머신. **Android import 없음** | 3절 |
| `tracking` | 포그라운드 서비스·리시버·타이머·heartbeat | 7·8절 |
| `notification` | 상시 표시·임계값 알림 | 4절 |
| `storage` | DataStore 설정·Room `sessions` | 8절 |
| `ui` | Compose 설정 화면 | 5절 |

기본값과 다른 규칙:

1. import는 이름을 명시한다(와일드카드 없음).
2. null은 `requireNotNull`·`checkNotNull`이나 early return으로 다룬다(`!!` 없음).
3. 상태 머신은 `data class` 상태 + `sealed interface` 이벤트 + 순수 함수 `reduce(state, event)`가 새 상태와 effect 목록을 돌려준다.
4. 알림 게시·타이머·DB 쓰기 같은 부수효과는 리듀서가 돌려준 effect를 `tracking`의 서비스가 실행한다.
5. Composable은 파일당 public 화면 하나, `@Preview`는 같은 파일 하단.
6. 사용자에게 보이는 문자열은 전부 `strings.xml`.
7. 시각·기간 변수는 단위 접미사를 붙인다(`startedAtMillis`, `graceMinutes`).

## 테스트

- JUnit 5 + `kotlinx-coroutines-test`. 테스트 이름은 백틱 한국어로 스펙 3절 전이표의 행을 그대로 옮긴다: `` fun `유예 중에 잠금 해제되면 같은 세션이 이어진다`() ``.
- `session` 패키지는 TDD 필수. 전이표의 모든 행에 테스트가 있어야 완료다.
- Robolectric·계측 테스트는 쓰지 않는다. `tracking`·`notification`·`ui`는 에뮬레이터 스모크(파이프라인 5단계)로 확인한다.
- 실기기(제조사 절전 정책) 검증은 사용자가 `develop`을 폰에 설치해 몰아서 한다. 서비스·리시버·알림을 바꾼 PR은 Test plan의 "실기기 미검증"에 체크한다.

## 아키텍처

스펙 8절이 정본이다. 여기에는 어길 수 없는 것만 적는다.

- 세션 상태 머신은 Android import 없는 순수 Kotlin. Android 코드는 이벤트를 넣고 결과를 그리기만 한다.
- 단일 `app` 모듈, DI 없음(수동 조립), Kotlin coroutines.
- 타이머는 서비스 내 coroutine + `elapsedRealtime`. 유예 만료 알람만 `setAndAllowWhileIdle`. WorkManager·exact alarm 없음.
- manifest 권한은 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` 4개.
- 스펙 밖의 새 결정은 `docs/adr/`에 ADR로 남긴다.

## 개발 환경

- 필수 환경변수: `JAVA_HOME`, `ANDROID_HOME`. 저장소의 `local.properties`(gitignore)에 `sdk.dir`. 없으면 사용자에게 설정을 요청한다. 이 PC의 값: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `ANDROID_HOME=C:\Users\pc\AppData\Local\Android\Sdk`, PATH에 `%ANDROID_HOME%\platform-tools`와 `%ANDROID_HOME%\emulator`.
- 툴체인: 빌드 JDK 21, `jvmTarget` 17, Gradle 9.4.1, AGP 9.2.1. 라이브러리 버전은 `gradle/libs.versions.toml`이 정본이다.
- CI: GitHub Actions가 push·PR마다 `./gradlew test assembleDebug`.

## 주의사항

1. 저장하는 시각은 `System.currentTimeMillis()`. `elapsedRealtime`은 재부팅에 초기화되므로 프로세스 안 타이머에만 쓴다.
2. 잠금 중(유예 중)에는 임계값 알림·재알림을 보내지 않는다. 다음 잠금 해제 직후 1회만.
3. 티켓 원문과 스펙이 다르면 스펙이 우선이다(스펙 부록 A).
4. `CONTEXT.md`의 _Avoid_ 용어(스크린 타임, 쿨다운, 스누즈 등)는 코드·주석·이슈에 쓰지 않는다.
5. Orca 워크트리는 stash를 다른 세션과 공유한다. `git stash push -u -m "<태그>"`로만 쓰고 `apply` 뒤 태그로 찾아 `drop`한다.
6. Git Bash는 `./gradlew`, PowerShell은 `.\gradlew.bat`.
7. 서비스의 첫 시작은 사용자의 "측정 시작" 탭뿐이다. 설치·업데이트·부팅으로 시작하지 않고, 재기동은 `tracking_on`이 참일 때만.
8. 권한·의존성 추가는 스펙 수정 PR이 먼저다.
