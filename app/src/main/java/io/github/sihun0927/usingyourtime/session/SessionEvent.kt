package io.github.sihun0927.usingyourtime.session

/**
 * 리듀서에 들어가는 이벤트(스펙 3절).
 *
 * 임계값 도달·재알림 주기 경과·세션 알림 끄기·서비스 재시작은 이후 티켓(#23~#27)에서 들어온다.
 */
sealed interface SessionEvent {

    /** 사용자가 "측정 시작"을 눌렀다. */
    data object StartTracking : SessionEvent

    /** 사용자가 "측정 중지"를 눌렀다. */
    data object Pause : SessionEvent

    /** `ACTION_USER_PRESENT`. 잠금이 풀렸다. */
    data object Unlock : SessionEvent

    /** `ACTION_SCREEN_OFF`. 화면이 꺼졌다. */
    data object Lock : SessionEvent

    /** `ACTION_SCREEN_ON`. 잠금 해제 없이 잠금 화면만 켜졌다. */
    data object ScreenOn : SessionEvent

    /** 유예 만료 타이머나 알람이 깨웠다. 늦게 와도 되도록 리듀서가 잠금 시각으로 다시 판정한다. */
    data object GraceExpired : SessionEvent

    /** 서비스 내 1분 타이머. 상시 표시를 다시 그린다. */
    data object MinuteTick : SessionEvent
}
