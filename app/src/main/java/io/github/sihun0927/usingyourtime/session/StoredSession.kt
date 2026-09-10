package io.github.sihun0927.usingyourtime.session

/**
 * 프로세스가 죽으면서 닫히지 못하고 Room에 남은 열린 세션 행(스펙 7·8절).
 *
 * 서비스가 되살아날 때 이 값 하나와 지금 잠금 상태만으로 재동기화 규칙이 돌아간다
 * ([SessionEvent.ServiceRestart]). 열린 행은 항상 최대 1개라 값도 하나다.
 *
 * [Session]과 열이 겹치지만 같은 것이 아니다. 이쪽은 **저장된 과거**라 마지막 생존 시각을 이고
 * 있고, [Session]은 지금 살아 있는 세션이라 그 열이 없다. 재동기화가 하는 일이 곧 이 값을
 * 지금 시각으로 판정해 [Session]으로 옮기는 것이다.
 */
data class StoredSession(
    val startedAtMillis: Long,

    /** 마지막 생존 시각(`last_alive_at`). 세션 진행 중 1분마다 덮어쓴 heartbeat다(스펙 7절). */
    val lastAliveAtMillis: Long,

    /** 마지막 잠금 시각. 유예 중에 끊겼으면 있고, 세션 진행 중에 끊겼으면 null이다. */
    val lockedAtMillis: Long? = null,

    val alerts: AlertState = AlertState.None,

    val muted: Boolean = false,
) {
    /**
     * 추정 종료 시각(`estimatedEndTime`) = `max(last_alive_at, locked_at)`(스펙 7절).
     *
     * 마지막 생존 시각은 최대 1분 낡았고 잠금 시각은 관측한 그대로다. 유예 중에 끊겼다면 잠금
     * 시각이 더 늦고, 세션 진행 중에 끊겼다면 잠금 시각이 아예 없거나(한 번도 잠기지 않았다)
     * 지난 세션의 것보다 heartbeat가 늦다. 어느 쪽이든 늦은 쪽이 측정이 살아 있던 마지막 순간이다.
     */
    val estimatedEndAtMillis: Long
        get() = lockedAtMillis?.let { maxOf(lastAliveAtMillis, it) } ?: lastAliveAtMillis

    /** 저장된 행을 지금 살아 있는 세션으로 옮긴다. 알림 상태와 세션 알림 끄기가 함께 따라온다. */
    fun toSession(lockedAtMillis: Long?): Session = Session(
        startedAtMillis = startedAtMillis,
        lockedAtMillis = lockedAtMillis,
        alerts = alerts,
        muted = muted,
    )
}
