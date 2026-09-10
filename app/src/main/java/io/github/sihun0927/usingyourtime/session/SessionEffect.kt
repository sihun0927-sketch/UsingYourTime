package io.github.sihun0927.usingyourtime.session

/**
 * 리듀서가 돌려주는 부수효과(스펙 3절). 값일 뿐이고, 실행은 `tracking`의 서비스가 한다.
 *
 * 재알림 게시와 세션 알림 끄기의 알림 제거는 이후 티켓(#24·#25)에서 들어온다.
 */
sealed interface SessionEffect {

    /**
     * 상시 표시를 [content]대로 게시하거나 갱신한다.
     *
     * 포그라운드 서비스의 알림이라 이 효과가 곧 `startForeground`다. 그래서 리듀서는 한 전이의
     * 효과 목록에서 이것을 늘 맨 앞에 둔다. 앞에 선 저장을 기다리다 알림이 늦어지지 않는다.
     */
    data class UpdatePersistentDisplay(val content: PersistentDisplayContent) : SessionEffect

    /** DataStore의 `tracking_on`을 저장한다(스펙 8절). */
    data class SaveTrackingOn(val trackingOn: Boolean) : SessionEffect

    /** 상시 표시를 지우고 포그라운드 서비스를 내린다. */
    data object StopService : SessionEffect

    /** Room `sessions`에 열린 세션 행을 만든다. 열린 행은 항상 최대 1개다(스펙 8절). */
    data class OpenSession(val startedAtMillis: Long) : SessionEffect

    /**
     * 열린 세션 행의 잠금 시각을 적는다. 추정 종료 시각의 재료다(스펙 7절).
     *
     * 유예 안에 다시 잠금 해제되면 null로 지운다. 잠금 시각은 "유예 중일 때"의 열이라(스펙 8절)
     * 세션이 이어지는 동안 남아 있으면 추정 종료 시각을 과거로 끌어내린다.
     */
    data class SaveLockedAt(val lockedAtMillis: Long?) : SessionEffect

    /** 열린 세션 행을 [endedAtMillis]·[reason]으로 닫는다. */
    data class CloseSession(val endedAtMillis: Long, val reason: SessionEndReason) : SessionEffect

    /** [atMillis]에 `유예 만료` 이벤트로 깨우도록 예약한다(서비스 내 타이머 + 알람). */
    data class ScheduleGraceExpiry(val atMillis: Long) : SessionEffect

    /** 예약해 둔 유예 만료 깨우기를 취소한다. 유예 중을 벗어날 때마다 낸다. */
    data object CancelGraceExpiry : SessionEffect

    /** 임계값 알림을 [content]대로 게시한다(스펙 4절). 채널 `threshold`, heads-up. */
    data class PostThresholdAlert(val content: ThresholdAlertContent) : SessionEffect

    /** 게시해 둔 임계값 알림을 알림 창에서 걷는다. 세션이 닫힐 때 낸다(스펙 4절 제거 시점). */
    data object DismissThresholdAlert : SessionEffect

    /** 열린 세션 행에 알림 상태를 적는다. 세션이 이어질 때 다시 울리지 않게 하는 재료다(스펙 7절). */
    data class SaveAlertState(val alerts: AlertState) : SessionEffect

    /**
     * [atMillis]에 `임계값 도달` 이벤트로 깨우도록 예약한다(서비스 내 타이머).
     *
     * 세션을 열 때 한 번 건다. 유예 만료와 달리 알람을 함께 걸지 않는다. 임계값 알림은 잠금 해제
     * 상태에서만 나가므로(스펙 4절) 기기가 잠든 동안 깨울 이유가 없다.
     */
    data class ScheduleThresholdAlert(val atMillis: Long) : SessionEffect
}
