# UsingYourTime

휴대폰을 잠금 해제한 뒤 끊김 없이 계속 사용한 시간을 상태바에 보여주고, 정한 시간이 지나면 알려주는 Android 앱 "UsingTime".

## 문서

- [구현 스펙](docs/spec/usingtime-spec.md): 세션 상태 모델, 알림 규칙, 화면, 설정 값, 복구 정책, 기술 스택, 스토어 요건
- [도메인 용어](CONTEXT.md)
- [결정 기록 (Wayfinder 지도)](https://github.com/sihun0927-sketch/UsingYourTime/issues/1)

## 현재 진행 상황

스펙 확정 단계입니다. 아직 앱 코드는 없습니다.

| 항목 | 상태 |
|------|------|
| 저장소 초기화 · LICENSE (MIT) | 완료 |
| 플랫폼 선정 | 완료 (Android 단독, Kotlin) |
| 요구사항 · 스펙 | 완료 ([docs/spec/usingtime-spec.md](docs/spec/usingtime-spec.md)) |
| 구현 | 진행 전 |
| 스토어 등록 | 진행 전 |

## 다음 할 일

스펙의 [권장 구현 순서](docs/spec/usingtime-spec.md#11-권장-구현-순서)를 따른다.

1. 프로젝트 골격 (Gradle · 권한 · CI)
2. 순수 Kotlin 세션 상태 머신 + JUnit
3. 저장 (DataStore · Room)
4. 포그라운드 서비스 · 리시버 · 타이머
5. 알림 2종
6. 설정 화면
7. 복구 경로 + 실기기 검증
8. 스토어 자료 · 릴리스 체크리스트
