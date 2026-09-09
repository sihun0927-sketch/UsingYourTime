package io.github.sihun0927.usingyourtime.session

/** 리듀서가 돌려주는 결과. 새 상태와 서비스가 실행할 효과 목록이다. */
data class Reduction(
    val state: SessionState,
    val effects: List<SessionEffect> = emptyList(),
)

/**
 * 세션 상태 머신(스펙 3절 전이표). `android.*`를 import하지 않는 순수 함수라 시간도 저장도 모른다.
 * 지금 시각은 [reduce]의 인자로 받고, 바깥 세상에 할 일은 [SessionEffect] 값으로 돌려준다.
 *
 * 전이표에 없는 (상태, 이벤트) 짝은 상태를 그대로 두고 효과도 내지 않는다.
 *
 * 유예 만료를 정하는 것은 타이머가 아니라 **잠금 시각과 지금 시각의 차이**다. 유예 중에 들어오는
 * 모든 이벤트가 그 차이를 다시 재므로, 알람이 Doze로 늦게 오거나 유예 설정이 바뀌어 이르게 와도
 * 결과가 같다.
 */
object SessionReducer {

    fun reduce(
        state: SessionState,
        event: SessionEvent,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = when (event) {
        SessionEvent.StartTracking -> startTracking(state, nowMillis, settings)
        SessionEvent.Pause -> pause(state, nowMillis)
        SessionEvent.Unlock -> unlock(state, nowMillis, settings)
        SessionEvent.Lock -> lock(state, nowMillis, settings)
        // 전이표대로 재판정만 하고, 아직 유예가 남았으면 아무 일도 하지 않는다.
        SessionEvent.ScreenOn -> closeExpiredGrace(state, nowMillis, settings) ?: Reduction(state)
        SessionEvent.GraceExpired -> closeExpiredGrace(state, nowMillis, settings)
            ?: rescheduleGraceExpiry(state, nowMillis, settings)

        SessionEvent.MinuteTick -> closeExpiredGrace(state, nowMillis, settings)
            ?: refreshPersistentDisplay(state, nowMillis, settings)
    }

    /** 측정 꺼짐 → 세션 진행. 버튼을 누른 시점은 잠금 해제 상태이므로 **지금** 세션을 연다. */
    private fun startTracking(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Off) return Reduction(state)

        val started = startNewSession(nowMillis, settings)
        return started.copy(effects = started.effects + SessionEffect.SaveTrackingOn(trackingOn = true))
    }

    /**
     * 세션 없음·세션 진행·유예 중 → 측정 꺼짐. 열린 세션은 유예 없이 **지금** 닫힌다.
     *
     * 이미 측정 꺼짐이어도 `tracking_on`을 끄고 서비스를 내린다. 프로세스가 죽어 서비스의 상태만
     * 사라진 뒤에도 저장된 `tracking_on`은 켜진 채일 수 있어, 두 효과가 그 어긋남을 되돌린다.
     */
    private fun pause(state: SessionState, nowMillis: Long): Reduction = Reduction(
        state = SessionState.Off,
        effects = buildList {
            if (state.session != null) {
                add(SessionEffect.CloseSession(nowMillis, SessionEndReason.PAUSED))
            }
            add(SessionEffect.SaveTrackingOn(trackingOn = false))
            if (state.phase == Phase.Grace) add(SessionEffect.CancelGraceExpiry)
            add(SessionEffect.StopService)
        },
    )

    /**
     * `잠금 해제` 수신.
     *
     * - 세션 없음 → 지금 새 세션을 연다.
     * - 유예 중 → 유예가 남았으면 같은 세션이 이어지고, 이미 지났으면 잠금 시각으로 닫고 새 세션.
     * - 세션 진행 → 없음. 잠금 화면이 '없음'인 기기는 화면이 켜질 때마다 이 이벤트가 온다.
     */
    private fun unlock(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = when (state.phase) {
        Phase.Idle -> startNewSession(nowMillis, settings)

        Phase.Grace -> {
            val session = state.openSession()
            if (gracePeriodRemains(session, nowMillis, settings)) {
                val resumed = session.copy(lockedAtMillis = null)
                Reduction(
                    state = SessionState(Phase.Active, resumed),
                    effects = listOf(
                        SessionEffect.UpdatePersistentDisplay(activeContent(resumed, nowMillis, settings)),
                        SessionEffect.SaveLockedAt(lockedAtMillis = null),
                        SessionEffect.CancelGraceExpiry,
                    ),
                )
            } else {
                startNewSession(
                    nowMillis = nowMillis,
                    settings = settings,
                    closing = closeExpiredSession(session),
                    cancelGraceExpiry = true,
                )
            }
        }

        Phase.Off, Phase.Active -> Reduction(state)
    }

    /**
     * 세션 진행 → 유예 중. 잠금 시각을 남기고 유예가 끝날 시각에 깨우도록 예약한다.
     *
     * 유예 시간이 0이면 잠그는 순간이 곧 유예 만료라 세션 없음으로 곧장 간다(전이표).
     */
    private fun lock(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Active) return Reduction(state)
        val locked = state.openSession().copy(lockedAtMillis = nowMillis)

        if (!gracePeriodRemains(locked, nowMillis, settings)) {
            return Reduction(
                state = SessionState.Idle,
                effects = listOf(
                    SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                    closeExpiredSession(locked),
                ),
            )
        }

        return Reduction(
            state = SessionState(Phase.Grace, locked),
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(graceContent(locked, nowMillis, settings)),
                SessionEffect.SaveLockedAt(lockedAtMillis = nowMillis),
                SessionEffect.ScheduleGraceExpiry(atMillis = nowMillis + settings.gracePeriodMillis),
            ),
        )
    }

    /**
     * 유예 중이고 잠금 시각으로부터 유예가 이미 지났으면 유예 중 → 세션 없음 전이, 아니면 null.
     *
     * 유예 중에 오는 이벤트마다 이 재판정을 먼저 한다. 깨우기 취소는 예약이 남아 있든 아니든 낸다.
     */
    private fun closeExpiredGrace(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction? {
        if (state.phase != Phase.Grace) return null
        val session = state.openSession()
        if (gracePeriodRemains(session, nowMillis, settings)) return null

        return Reduction(
            state = SessionState.Idle,
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                closeExpiredSession(session),
                SessionEffect.CancelGraceExpiry,
            ),
        )
    }

    /**
     * 아직 유예가 남았는데 `유예 만료`로 깨어났을 때. 유예 시간이 늘어난 뒤 옛 예약이 먼저 온
     * 경우이므로 남은 유예만큼 다시 예약한다.
     */
    private fun rescheduleGraceExpiry(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Grace) return Reduction(state)
        val session = state.openSession()

        return Reduction(
            state = state,
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(graceContent(session, nowMillis, settings)),
                SessionEffect.ScheduleGraceExpiry(session.lockedAt() + settings.gracePeriodMillis),
            ),
        )
    }

    /**
     * `1분 tick`. 열린 세션이 있으면 상시 표시를 다시 그린다. 유예 중이면 남은 유예까지.
     *
     * 세션 없음의 상시 표시는 문구가 고정이라 다시 그릴 것이 없다. 스와이프로 지워진 상시 표시를
     * 다시 게시하는 일은 티켓 #29가 맡는다.
     */
    private fun refreshPersistentDisplay(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val content = when (state.phase) {
            Phase.Active -> activeContent(state.openSession(), nowMillis, settings)
            Phase.Grace -> graceContent(state.openSession(), nowMillis, settings)
            Phase.Off, Phase.Idle -> return Reduction(state)
        }
        return Reduction(state, listOf(SessionEffect.UpdatePersistentDisplay(content)))
    }

    /**
     * 지금 새 세션을 여는 전이. [closing]이 있으면 이전 세션을 먼저 닫는다(열린 행은 최대 1개다).
     *
     * 상시 표시가 늘 첫 효과다. 포그라운드 서비스의 알림이라 저장보다 뒤로 밀리면 안 된다.
     */
    private fun startNewSession(
        nowMillis: Long,
        settings: TrackingSettings,
        closing: SessionEffect.CloseSession? = null,
        cancelGraceExpiry: Boolean = false,
    ): Reduction {
        val session = Session(startedAtMillis = nowMillis)
        val effects = buildList {
            add(SessionEffect.UpdatePersistentDisplay(activeContent(session, nowMillis, settings)))
            if (closing != null) add(closing)
            add(SessionEffect.OpenSession(startedAtMillis = session.startedAtMillis))
            if (cancelGraceExpiry) add(SessionEffect.CancelGraceExpiry)
        }
        return Reduction(SessionState(Phase.Active, session), effects)
    }

    /** 유예가 지난 세션을 **잠금 시각**으로 닫는다. 잠겨 있던 구간은 세션에 넣지 않는다. */
    private fun closeExpiredSession(session: Session): SessionEffect.CloseSession =
        SessionEffect.CloseSession(session.lockedAt(), SessionEndReason.GRACE_EXPIRED)

    /** 잠금 시각으로부터 유예 시간이 아직 남았는지. 유예 0분이면 잠그는 순간 이미 거짓이다. */
    private fun gracePeriodRemains(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Boolean = nowMillis - session.lockedAt() < settings.gracePeriodMillis

    private fun activeContent(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): PersistentDisplayContent.Active = PersistentDisplayContent.Active(
        sessionStartedAtMillis = session.startedAtMillis,
        elapsedMinutes = elapsedMinutes(session, nowMillis),
        thresholdMinutes = settings.thresholdMinutes,
    )

    private fun graceContent(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): PersistentDisplayContent.Grace = PersistentDisplayContent.Grace(
        sessionStartedAtMillis = session.startedAtMillis,
        elapsedMinutes = elapsedMinutes(session, nowMillis),
        thresholdMinutes = settings.thresholdMinutes,
        graceRemainingMillis = (session.lockedAt() + settings.gracePeriodMillis - nowMillis)
            .coerceAtLeast(0),
    )

    /**
     * 연속 사용 시간(`sessionDuration`)의 분 단위. 유예 구간도 포함한다(스펙 3절).
     * 상시 표시의 본문·막대가 이 값을 쓴다.
     */
    private fun elapsedMinutes(session: Session, nowMillis: Long): Int =
        ((nowMillis - session.startedAtMillis) / MINUTE_MILLIS).coerceAtLeast(0).toInt()

    /** 세션 진행·유예 중에는 열린 세션이 있다([SessionState]의 불변식). */
    private fun SessionState.openSession(): Session =
        requireNotNull(session) { "$phase 국면에는 열린 세션이 있다" }

    /** 유예 중에는 잠금 시각이 있다([SessionState]의 불변식). */
    private fun Session.lockedAt(): Long =
        requireNotNull(lockedAtMillis) { "유예 중에는 잠금 시각이 있다" }
}
