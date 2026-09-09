package io.github.sihun0927.usingyourtime.session

/**
 * 리듀서에 들어가는 이벤트(스펙 3절).
 *
 * 지금은 설정 화면 버튼에서 오는 둘뿐이고, 잠금·잠금 해제·타이머 이벤트는 이후 티켓에서 들어온다.
 */
sealed interface SessionEvent {

    /** 사용자가 "측정 시작"을 눌렀다. */
    data object StartTracking : SessionEvent

    /** 사용자가 "측정 중지"를 눌렀다. */
    data object Pause : SessionEvent
}
