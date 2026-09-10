package io.github.sihun0927.usingyourtime.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 1분. 전이표의 시간이 분 단위라 테스트도 분으로 읽는다. */
private const val MINUTE_MILLIS = 60_000L

/**
 * 스펙 3절 전이표를 한 줄씩 값 비교로 검증한다.
 *
 * 재알림·세션 알림 끄기 줄은 이후 티켓(#24·#25)에서 이 파일에 들어온다.
 */
class SessionReducerTest {

    private val settings = TrackingSettings()
    private val nowMillis = 1_700_000_000_000L

    @Test
    fun `측정 꺼짐에서 측정 시작을 누르면 지금 시각으로 세션이 열린다`() {
        val reduction = reduce(SessionState.Off, SessionEvent.StartTracking)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reduction.state,
        )
    }

    @Test
    fun `측정 꺼짐에서 측정 시작을 누르면 상시 표시를 게시하고 세션을 열고 tracking_on을 켠다`() {
        val reduction = reduce(SessionState.Off, SessionEvent.StartTracking)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(nowMillis, elapsedMinutes = 0)),
                SessionEffect.OpenSession(startedAtMillis = nowMillis),
                SessionEffect.ScheduleThresholdAlert(atMillis = nowMillis + 30 * MINUTE_MILLIS),
                SessionEffect.SaveTrackingOn(trackingOn = true),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 진행에서 측정 중지를 누르면 측정 꺼짐이 되고 열린 세션이 닫힌다`() {
        val reduction = reduce(activeSince(nowMillis - MINUTE_MILLIS), SessionEvent.Pause)

        assertEquals(SessionState.Off, reduction.state)
    }

    @Test
    fun `세션 진행에서 측정 중지를 누르면 열린 세션을 지금 시각으로 닫고 서비스를 종료한다`() {
        val reduction = reduce(activeSince(nowMillis - MINUTE_MILLIS), SessionEvent.Pause)

        assertEquals(
            listOf(
                SessionEffect.CloseSession(
                    endedAtMillis = nowMillis,
                    reason = SessionEndReason.PAUSED,
                ),
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.StopService,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중에 측정 중지를 누르면 유예를 기다리지 않고 지금 시각으로 닫는다`() {
        val grace = graceSince(nowMillis - 21 * MINUTE_MILLIS, nowMillis - MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.Pause)

        assertEquals(SessionState.Off, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.CloseSession(
                    endedAtMillis = nowMillis,
                    reason = SessionEndReason.PAUSED,
                ),
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.CancelGraceExpiry,
                SessionEffect.StopService,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 없음에서 측정 중지를 누르면 닫을 세션이 없다`() {
        val reduction = reduce(SessionState.Idle, SessionEvent.Pause)

        assertEquals(SessionState.Off, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.StopService,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 진행 중 1분 tick은 상시 표시를 다시 그린다`() {
        val startedAtMillis = nowMillis - 12 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis)

        val reduction = reduce(active, SessionEvent.MinuteTick)

        assertEquals(active, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 12),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 진행 중 잠금 해제는 무시한다`() {
        val active = activeSince(nowMillis - 12 * MINUTE_MILLIS)

        val reduction = reduce(active, SessionEvent.Unlock)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `측정 꺼짐에서 측정 중지가 와도 tracking_on을 false로 만들고 서비스를 종료한다`() {
        val reduction = reduce(SessionState.Off, SessionEvent.Pause)

        assertEquals(SessionState.Off, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.StopService,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 진행에서 측정 시작은 세션을 새로 열지 않는다`() {
        val active = activeSince(nowMillis - MINUTE_MILLIS)

        val reduction = reduce(active, SessionEvent.StartTracking)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `세션 진행에서 잠그면 유예 중이 되고 잠금 시각이 남는다`() {
        val startedAtMillis = nowMillis - 10 * MINUTE_MILLIS

        val reduction = reduce(activeSince(startedAtMillis), SessionEvent.Lock)

        assertEquals(
            SessionState(
                Phase.Grace,
                Session(startedAtMillis = startedAtMillis, lockedAtMillis = nowMillis),
            ),
            reduction.state,
        )
    }

    @Test
    fun `세션 진행에서 잠그면 유예 만료를 예약하고 상시 표시가 유예 문구로 바뀐다`() {
        val startedAtMillis = nowMillis - 10 * MINUTE_MILLIS

        val reduction = reduce(activeSince(startedAtMillis), SessionEvent.Lock)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    graceContent(
                        startedAtMillis,
                        elapsedMinutes = 10,
                        graceRemainingMillis = 3 * MINUTE_MILLIS,
                    ),
                ),
                SessionEffect.SaveLockedAt(lockedAtMillis = nowMillis),
                SessionEffect.ScheduleGraceExpiry(atMillis = nowMillis + 3 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 시간이 0분이면 잠그는 즉시 세션 없음이 된다`() {
        val startedAtMillis = nowMillis - 10 * MINUTE_MILLIS

        val reduction = reduce(
            activeSince(startedAtMillis),
            SessionEvent.Lock,
            settings = TrackingSettings(graceMinutes = 0),
        )

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(
                    endedAtMillis = nowMillis,
                    reason = SessionEndReason.GRACE_EXPIRED,
                ),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중에 잠금 해제되면 같은 세션이 이어진다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 2 * MINUTE_MILLIS

        val reduction = reduce(graceSince(startedAtMillis, lockedAtMillis), SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 20),
                ),
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `잠금 뒤 딱 유예 시간만큼 지난 순간에 잠금 해제되면 아직 같은 세션이다`() {
        val startedAtMillis = nowMillis - 23 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 3 * MINUTE_MILLIS

        val reduction = reduce(graceSince(startedAtMillis, lockedAtMillis), SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            reduction.state,
        )
    }

    @Test
    fun `유예가 지나면 세션이 잠금 시각으로 닫히고 세션 없음이 된다`() {
        val startedAtMillis = nowMillis - 23 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 3 * MINUTE_MILLIS - 1

        val reduction = reduce(graceSince(startedAtMillis, lockedAtMillis), SessionEvent.GraceExpired)

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(lockedAtMillis, SessionEndReason.GRACE_EXPIRED),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 만료가 이르게 오면 유예 중 그대로 남은 유예만큼 다시 예약한다`() {
        val lockedAtMillis = nowMillis - MINUTE_MILLIS
        val grace = graceSince(nowMillis - 21 * MINUTE_MILLIS, lockedAtMillis)

        val reduction = reduce(grace, SessionEvent.GraceExpired)

        assertEquals(grace, reduction.state)
        assertEquals(
            listOf(SessionEffect.ScheduleGraceExpiry(atMillis = lockedAtMillis + 3 * MINUTE_MILLIS)),
            reduction.effects,
        )
    }

    @Test
    fun `유예가 지난 뒤 잠금 해제되면 이전 세션이 닫히고 새 세션이 시작된다`() {
        val startedAtMillis = nowMillis - 30 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 10 * MINUTE_MILLIS

        val reduction = reduce(graceSince(startedAtMillis, lockedAtMillis), SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(nowMillis, elapsedMinutes = 0)),
                SessionEffect.CloseSession(lockedAtMillis, SessionEndReason.GRACE_EXPIRED),
                SessionEffect.OpenSession(startedAtMillis = nowMillis),
                SessionEffect.CancelGraceExpiry,
                SessionEffect.ScheduleThresholdAlert(atMillis = nowMillis + 30 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중 화면만 켜지면 유예 중 그대로다`() {
        val grace = graceSince(nowMillis - 21 * MINUTE_MILLIS, nowMillis - MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.ScreenOn)

        assertEquals(grace, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `유예 중 화면이 켜졌을 때 유예가 이미 지났으면 세션이 닫힌다`() {
        val startedAtMillis = nowMillis - 30 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 10 * MINUTE_MILLIS

        val reduction = reduce(graceSince(startedAtMillis, lockedAtMillis), SessionEvent.ScreenOn)

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(lockedAtMillis, SessionEndReason.GRACE_EXPIRED),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중 1분 tick은 남은 유예를 갱신한다`() {
        val startedAtMillis = nowMillis - 21 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, lockedAtMillis)

        val reduction = reduce(grace, SessionEvent.MinuteTick)

        assertEquals(grace, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    graceContent(
                        startedAtMillis,
                        elapsedMinutes = 21,
                        graceRemainingMillis = 2 * MINUTE_MILLIS,
                    ),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중 1분 tick이 늦게 와 유예가 이미 지났으면 세션이 닫힌다`() {
        val startedAtMillis = nowMillis - 30 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 10 * MINUTE_MILLIS

        val reduction = reduce(graceSince(startedAtMillis, lockedAtMillis), SessionEvent.MinuteTick)

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(lockedAtMillis, SessionEndReason.GRACE_EXPIRED),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 없음에서 1분 tick은 다시 그릴 것이 없다`() {
        assertEquals(
            Reduction(SessionState.Idle),
            reduce(SessionState.Idle, SessionEvent.MinuteTick),
        )
    }

    @Test
    fun `유예 중에 다시 잠금이 와도 아무 일도 하지 않는다`() {
        val grace = graceSince(nowMillis - 21 * MINUTE_MILLIS, nowMillis - MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.Lock)

        assertEquals(grace, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `세션 없음에서 잠금 해제되면 새 세션이 시작된다`() {
        val reduction = reduce(SessionState.Idle, SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(nowMillis, elapsedMinutes = 0)),
                SessionEffect.OpenSession(startedAtMillis = nowMillis),
                SessionEffect.ScheduleThresholdAlert(atMillis = nowMillis + 30 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 없음에서 화면 켜짐과 잠금은 아무 일도 하지 않는다`() {
        assertEquals(Reduction(SessionState.Idle), reduce(SessionState.Idle, SessionEvent.ScreenOn))
        assertEquals(Reduction(SessionState.Idle), reduce(SessionState.Idle, SessionEvent.Lock))
    }

    @Test
    fun `세션 진행 중 임계값에 닿으면 임계값 알림을 보내고 알림 상태를 적는다`() {
        val startedAtMillis = nowMillis - 30 * MINUTE_MILLIS
        val alerts = AlertState(
            thresholdAlertedAtMillis = nowMillis,
            lastAlertAtMillis = nowMillis,
            count = 1,
        )

        val reduction = reduce(activeSince(startedAtMillis), SessionEvent.ThresholdReached)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = alerts)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 30, nextAlertMinutes = 15),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 30, reAlertMinutes = 15),
                ),
                SessionEffect.SaveAlertState(alerts),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션을 열면 임계값 시각에 깨우도록 예약한다`() {
        val reduction = reduce(SessionState.Idle, SessionEvent.Unlock)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(nowMillis, elapsedMinutes = 0)),
                SessionEffect.OpenSession(startedAtMillis = nowMillis),
                SessionEffect.ScheduleThresholdAlert(atMillis = nowMillis + 30 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `임계값을 이미 지난 값으로 낮추면 다음 1분 tick이 임계값 도달로 처리한다`() {
        val startedAtMillis = nowMillis - 40 * MINUTE_MILLIS
        val lowered = TrackingSettings(thresholdMinutes = 15)

        val reduction = reduce(activeSince(startedAtMillis), SessionEvent.MinuteTick, lowered)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(
                        startedAtMillis,
                        elapsedMinutes = 40,
                        nextAlertMinutes = 15,
                        thresholdMinutes = 15,
                    ),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 40, reAlertMinutes = 15),
                ),
                SessionEffect.SaveAlertState(
                    AlertState(
                        thresholdAlertedAtMillis = nowMillis,
                        lastAlertAtMillis = nowMillis,
                        count = 1,
                    ),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `알림을 보낸 세션이 유예 만료로 닫히면 임계값 알림을 걷는다`() {
        val startedAtMillis = nowMillis - 50 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - 10 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, lockedAtMillis, alertedMinutesAgo(20))

        val reduction = reduce(grace, SessionEvent.GraceExpired)

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(lockedAtMillis, SessionEndReason.GRACE_EXPIRED),
                SessionEffect.DismissThresholdAlert,
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `알림을 보낸 세션에서 측정 중지를 누르면 임계값 알림도 걷는다`() {
        val active = activeSince(nowMillis - 50 * MINUTE_MILLIS, alertedMinutesAgo(20))

        val reduction = reduce(active, SessionEvent.Pause)

        assertEquals(
            listOf(
                SessionEffect.CloseSession(nowMillis, SessionEndReason.PAUSED),
                SessionEffect.DismissThresholdAlert,
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.StopService,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `이미 알린 세션에는 임계값 도달이 다시 와도 보내지 않는다`() {
        val active = activeSince(nowMillis - 50 * MINUTE_MILLIS, alertedMinutesAgo(20))

        val reduction = reduce(active, SessionEvent.ThresholdReached)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `임계값에 닿기 전에 깨우기가 오면 아무 일도 하지 않는다`() {
        val active = activeSince(nowMillis - 10 * MINUTE_MILLIS)

        val reduction = reduce(active, SessionEvent.ThresholdReached)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `유예 중에 임계값에 닿아도 임계값 알림을 보내지 않는다`() {
        val grace = graceSince(nowMillis - 40 * MINUTE_MILLIS, nowMillis - MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.ThresholdReached)

        assertEquals(grace, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `유예 중 1분 tick은 임계값에 닿아도 임계값 알림을 보내지 않는다`() {
        val startedAtMillis = nowMillis - 40 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, lockedAtMillis)

        val reduction = reduce(grace, SessionEvent.MinuteTick)

        assertEquals(grace, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    graceContent(
                        startedAtMillis,
                        elapsedMinutes = 40,
                        graceRemainingMillis = 2 * MINUTE_MILLIS,
                    ),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `초과 상태의 상시 표시는 다음 알림까지 남은 분을 함께 그린다`() {
        val startedAtMillis = nowMillis - 34 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(4))

        val reduction = reduce(active, SessionEvent.MinuteTick)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 34, nextAlertMinutes = 11),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 안에 잠금 해제되어 세션이 이어지면 알림 상태가 그대로 남는다`() {
        val startedAtMillis = nowMillis - 50 * MINUTE_MILLIS
        val alerts = alertedMinutesAgo(20)
        val grace = graceSince(startedAtMillis, nowMillis - 2 * MINUTE_MILLIS, alerts)

        val reduction = reduce(grace, SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = alerts)),
            reduction.state,
        )
    }

    @Test
    fun `다음 재알림 시각이 지나면 상시 표시에 남은 분을 적지 않는다`() {
        val startedAtMillis = nowMillis - 50 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(20))

        val reduction = reduce(active, SessionEvent.MinuteTick)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 50, nextAlertMinutes = null),
                ),
            ),
            reduction.effects,
        )
    }

    private fun reduce(
        state: SessionState,
        event: SessionEvent,
        settings: TrackingSettings = this.settings,
    ): Reduction = SessionReducer.reduce(state, event, nowMillis, settings)

    private fun activeSince(
        startedAtMillis: Long,
        alerts: AlertState = AlertState.None,
    ): SessionState =
        SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = alerts))

    private fun graceSince(
        startedAtMillis: Long,
        lockedAtMillis: Long,
        alerts: AlertState = AlertState.None,
    ): SessionState = SessionState(
        Phase.Grace,
        Session(
            startedAtMillis = startedAtMillis,
            lockedAtMillis = lockedAtMillis,
            alerts = alerts,
        ),
    )

    /** [minutesAgo]분 전에 임계값 알림을 1회 보낸 세션의 알림 상태. */
    private fun alertedMinutesAgo(minutesAgo: Int): AlertState = AlertState(
        thresholdAlertedAtMillis = nowMillis - minutesAgo * MINUTE_MILLIS,
        lastAlertAtMillis = nowMillis - minutesAgo * MINUTE_MILLIS,
        count = 1,
    )

    private fun activeContent(
        startedAtMillis: Long,
        elapsedMinutes: Int,
        nextAlertMinutes: Int? = null,
        thresholdMinutes: Int = settings.thresholdMinutes,
    ): PersistentDisplayContent = PersistentDisplayContent.Active(
        sessionStartedAtMillis = startedAtMillis,
        elapsedMinutes = elapsedMinutes,
        thresholdMinutes = thresholdMinutes,
        nextAlertMinutes = nextAlertMinutes,
    )

    private fun graceContent(
        startedAtMillis: Long,
        elapsedMinutes: Int,
        graceRemainingMillis: Long,
    ): PersistentDisplayContent = PersistentDisplayContent.Grace(
        sessionStartedAtMillis = startedAtMillis,
        elapsedMinutes = elapsedMinutes,
        thresholdMinutes = settings.thresholdMinutes,
        graceRemainingMillis = graceRemainingMillis,
    )
}
