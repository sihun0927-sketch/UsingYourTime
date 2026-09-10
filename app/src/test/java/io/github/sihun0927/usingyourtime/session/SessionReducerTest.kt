package io.github.sihun0927.usingyourtime.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 1분. 전이표의 시간이 분 단위라 테스트도 분으로 읽는다. */
private const val MINUTE_MILLIS = 60_000L

/**
 * 스펙 3절 전이표를 한 줄씩 값 비교로 검증한다.
 */
class SessionReducerTest {

    private val settings = TrackingSettings()

    /** 임계값 알림 토글을 끈 설정(스펙 6절). 상시 표시만 남고 임계값 알림이 아예 오지 않는다. */
    private val alertsDisabled = TrackingSettings(thresholdAlertEnabled = false)

    /**
     * 유예 시간이 재알림 주기보다 긴 설정(스펙 6절 범위 안). 잠긴 동안 주기가 실제로 지날 수 있는
     * 유일한 조합이라, 밀린 재알림을 다루는 테스트는 이 설정을 쓴다.
     */
    private val graceOutlastsReAlert = TrackingSettings(graceMinutes = 15, reAlertMinutes = 5)

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
                SessionEffect.SaveRestartNotice(atMillis = null),
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
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
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
                SessionEffect.ScheduleThresholdAlert(atMillis = startedAtMillis + 30 * MINUTE_MILLIS),
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 안에 잠금 해제되면 임계값 깨우기를 다시 건다`() {
        // 잠긴 동안 임계값 깨우기가 왔다 가며 소모됐을 수 있다. 그때는 잠금 중이라 알림이 나가지
        // 않았고, 다시 걸리지도 않았다. 여기서 걸지 않으면 `1분 tick`이 메울 때까지 밀린다.
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, nowMillis - 2 * MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.Unlock)

        assertEquals(
            listOf(SessionEffect.ScheduleThresholdAlert(atMillis = startedAtMillis + 30 * MINUTE_MILLIS)),
            reduction.effects.filterIsInstance<SessionEffect.ScheduleThresholdAlert>(),
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
                SessionEffect.SaveRestartNotice(atMillis = null),
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
    fun `세션 없음에서 1분 tick은 대기 문구를 다시 게시한다`() {
        assertEquals(
            Reduction(
                SessionState.Idle,
                listOf(SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle)),
            ),
            reduce(SessionState.Idle, SessionEvent.MinuteTick),
        )
    }

    @Test
    fun `측정 꺼짐에서 1분 tick이 와도 게시할 상시 표시가 없다`() {
        assertEquals(
            Reduction(SessionState.Off),
            reduce(SessionState.Off, SessionEvent.MinuteTick),
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
                SessionEffect.SaveRestartNotice(atMillis = null),
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
                    ThresholdAlertContent(elapsedMinutes = 30, reAlertMinutes = 15, count = 1),
                ),
                SessionEffect.SaveAlertState(alerts),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 15 * MINUTE_MILLIS),
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
                SessionEffect.SaveRestartNotice(atMillis = null),
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
                    ThresholdAlertContent(elapsedMinutes = 40, reAlertMinutes = 15, count = 1),
                ),
                SessionEffect.SaveAlertState(
                    AlertState(
                        thresholdAlertedAtMillis = nowMillis,
                        lastAlertAtMillis = nowMillis,
                        count = 1,
                    ),
                ),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 15 * MINUTE_MILLIS),
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
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
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중에 임계값을 넘긴 세션은 잠금 해제 직후 임계값 알림을 1회 받는다`() {
        val startedAtMillis = nowMillis - 31 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, nowMillis - 2 * MINUTE_MILLIS)
        val alerts = AlertState(
            thresholdAlertedAtMillis = nowMillis,
            lastAlertAtMillis = nowMillis,
            count = 1,
        )

        val reduction = reduce(grace, SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = alerts)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 31, nextAlertMinutes = 15),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 31, reAlertMinutes = 15, count = 1),
                ),
                SessionEffect.SaveAlertState(alerts),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 15 * MINUTE_MILLIS),
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중에 재알림 주기가 지난 세션은 잠금 해제 직후 재알림을 1회 받는다`() {
        // 38분 전 시작 → 8분 전(임계값 30분)에 첫 알림 → 5분 전 잠금 → 유예 안에서 주기 5분 경과.
        val startedAtMillis = nowMillis - 38 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, nowMillis - 5 * MINUTE_MILLIS, alertedMinutesAgo(8))
        val alerts = AlertState(
            thresholdAlertedAtMillis = nowMillis - 8 * MINUTE_MILLIS,
            lastAlertAtMillis = nowMillis,
            count = 2,
        )

        val reduction = reduce(grace, SessionEvent.Unlock, graceOutlastsReAlert)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = alerts)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 38, nextAlertMinutes = 5),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 38, reAlertMinutes = 5, count = 2),
                ),
                SessionEffect.SaveAlertState(alerts),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 5 * MINUTE_MILLIS),
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `재알림 주기가 유예 중에 두 번 지나도 잠금 해제 직후 재알림은 1회다`() {
        // 43분 전 시작 → 13분 전에 첫 알림 → 11분 전 잠금. 유예 15분 안에 주기 5분이 두 번 지난다.
        val startedAtMillis = nowMillis - 43 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, nowMillis - 11 * MINUTE_MILLIS, alertedMinutesAgo(13))

        val reduction = reduce(grace, SessionEvent.Unlock, graceOutlastsReAlert)

        // 밀린 두 주기가 회차를 둘 올리지 않는다. 회차는 1에서 2로만 간다(전이표).
        assertEquals(2, reduction.state.session?.alerts?.count)
        assertEquals(
            listOf(
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 43, reAlertMinutes = 5, count = 2),
                ),
            ),
            reduction.effects.filterIsInstance<SessionEffect.PostThresholdAlert>(),
        )
        // 다음 재알림은 밀린 주기가 아니라 방금 보낸 알림부터 잰다.
        assertEquals(
            listOf(SessionEffect.ScheduleReAlert(atMillis = nowMillis + 5 * MINUTE_MILLIS)),
            reduction.effects.filterIsInstance<SessionEffect.ScheduleReAlert>(),
        )
    }

    @Test
    fun `임계값 알림 토글이 꺼져 있으면 유예 안에 잠금 해제되어도 알림이 나가지 않는다`() {
        val startedAtMillis = nowMillis - 31 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, nowMillis - 2 * MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.Unlock, alertsDisabled)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            reduction.state,
        )
        // 초과 표기는 토글과 무관하게 그대로이고, 예고할 다음 알림만 없다(스펙 4절,
        // `docs/adr/0003-persistent-display-body-when-no-next-alert.md`).
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 31, nextAlertMinutes = null),
                ),
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.CancelGraceExpiry,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중에 임계값을 넘겨도 화면만 켜지면 알림이 나가지 않는다`() {
        val grace = graceSince(nowMillis - 31 * MINUTE_MILLIS, nowMillis - 2 * MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.ScreenOn)

        assertEquals(grace, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `유예가 지난 뒤 잠금 해제되면 새 세션이라 밀린 알림이 나가지 않는다`() {
        val grace = graceSince(nowMillis - 40 * MINUTE_MILLIS, nowMillis - 10 * MINUTE_MILLIS)

        val reduction = reduce(grace, SessionEvent.Unlock)

        assertEquals(AlertState.None, reduction.state.session?.alerts)
        assertEquals(
            emptyList<SessionEffect>(),
            reduction.effects.filterIsInstance<SessionEffect.PostThresholdAlert>(),
        )
    }

    @Test
    fun `유예 안에 잠금 해제되어 세션이 이어지면 알림 상태가 그대로 남는다`() {
        // 35분 전 시작 → 5분 전(임계값 30분)에 첫 알림 → 2분 전 잠금. 재알림 주기 15분이 아직
        // 남은 세션이라 잠금 해제의 즉시 판정에 걸리지 않는다.
        val startedAtMillis = nowMillis - 35 * MINUTE_MILLIS
        val alerts = alertedMinutesAgo(5)
        val grace = graceSince(startedAtMillis, nowMillis - 2 * MINUTE_MILLIS, alerts)

        val reduction = reduce(grace, SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = alerts)),
            reduction.state,
        )
    }

    @Test
    fun `세션 진행 중 재알림 주기가 지나면 회차를 올려 재알림을 보낸다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(15))
        val reAlerted = AlertState(
            thresholdAlertedAtMillis = nowMillis - 15 * MINUTE_MILLIS,
            lastAlertAtMillis = nowMillis,
            count = 2,
        )

        val reduction = reduce(active, SessionEvent.ReAlertIntervalElapsed)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis, alerts = reAlerted)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 45, nextAlertMinutes = 15),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 45, reAlertMinutes = 15, count = 2),
                ),
                SessionEffect.SaveAlertState(reAlerted),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 15 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `재알림 주기는 세션 시작이 아니라 직전 알림 시각부터 잰다`() {
        val startedAtMillis = nowMillis - 100 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(14))

        val reduction = reduce(active, SessionEvent.ReAlertIntervalElapsed)

        // 세션은 100분째지만 직전 알림에서는 14분밖에 지나지 않아 재알림이 나가지 않는다.
        assertEquals(active, reduction.state)
        assertEquals(
            listOf(SessionEffect.ScheduleReAlert(atMillis = nowMillis + MINUTE_MILLIS)),
            reduction.effects,
        )
    }

    @Test
    fun `재알림 주기를 늘려 깨우기가 이르게 오면 직전 알림 시각 기준으로 다시 예약한다`() {
        val active = activeSince(nowMillis - 45 * MINUTE_MILLIS, alertedMinutesAgo(10))
        val raised = TrackingSettings(reAlertMinutes = 30)

        val reduction = reduce(active, SessionEvent.ReAlertIntervalElapsed, raised)

        assertEquals(active, reduction.state)
        assertEquals(
            listOf(SessionEffect.ScheduleReAlert(atMillis = nowMillis + 20 * MINUTE_MILLIS)),
            reduction.effects,
        )
    }

    @Test
    fun `직전 알림에서 딱 재알림 주기만큼 지난 순간에 재알림이 나간다`() {
        val active = activeSince(nowMillis - 45 * MINUTE_MILLIS, alertedMinutesAgo(15))

        val reduction = reduce(active, SessionEvent.ReAlertIntervalElapsed)

        assertEquals(2, reduction.state.session?.alerts?.count)
    }

    @Test
    fun `재알림에는 상한이 없어 주기마다 회차가 계속 올라간다`() {
        // 사용자가 알림을 지웠는지는 리듀서가 아예 모르는 사실이라, 회차는 그것과 무관하게
        // 주기마다 계속 올라간다(스펙 4절).
        var state = activeSince(nowMillis - 30 * MINUTE_MILLIS, alertedMinutesAgo(0))
        var atMillis = nowMillis

        repeat(5) {
            atMillis += 15 * MINUTE_MILLIS
            state = SessionReducer
                .reduce(state, SessionEvent.ReAlertIntervalElapsed, atMillis, settings)
                .state
        }

        assertEquals(6, state.session?.alerts?.count)
    }

    @Test
    fun `임계값 알림을 보낸 적 없는 세션에는 재알림 주기 경과가 와도 보내지 않는다`() {
        val active = activeSince(nowMillis - 10 * MINUTE_MILLIS)

        val reduction = reduce(active, SessionEvent.ReAlertIntervalElapsed)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `유예 중에는 재알림 주기가 지나도 재알림을 보내지 않는다`() {
        val grace = graceSince(
            startedAtMillis = nowMillis - 60 * MINUTE_MILLIS,
            lockedAtMillis = nowMillis - MINUTE_MILLIS,
            alerts = alertedMinutesAgo(20),
        )

        val reduction = reduce(grace, SessionEvent.ReAlertIntervalElapsed)

        assertEquals(grace, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `세션 없음에서 재알림 주기 경과가 와도 아무 일도 하지 않는다`() {
        assertEquals(
            Reduction(SessionState.Idle),
            reduce(SessionState.Idle, SessionEvent.ReAlertIntervalElapsed),
        )
    }

    @Test
    fun `재알림 주기를 이미 지난 값으로 낮추면 다음 1분 tick이 재알림으로 처리한다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(10))
        val lowered = TrackingSettings(reAlertMinutes = 5)

        val reduction = reduce(active, SessionEvent.MinuteTick, lowered)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 45, nextAlertMinutes = 5),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 45, reAlertMinutes = 5, count = 2),
                ),
                SessionEffect.SaveAlertState(
                    AlertState(
                        thresholdAlertedAtMillis = nowMillis - 10 * MINUTE_MILLIS,
                        lastAlertAtMillis = nowMillis,
                        count = 2,
                    ),
                ),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 5 * MINUTE_MILLIS),
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `첫 임계값 알림이 나가는 1분 tick은 재알림을 겹쳐 보내지 않는다`() {
        val active = activeSince(nowMillis - 40 * MINUTE_MILLIS)

        val reduction = reduce(active, SessionEvent.MinuteTick)

        assertEquals(1, reduction.state.session?.alerts?.count)
    }

    @Test
    fun `세션 진행 중 세션 알림 끄기를 누르면 세션이 muted가 된다`() {
        val startedAtMillis = nowMillis - 34 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(4))

        val reduction = reduce(active, SessionEvent.MuteSession)

        assertEquals(
            SessionState(
                Phase.Active,
                Session(
                    startedAtMillis = startedAtMillis,
                    alerts = alertedMinutesAgo(4),
                    muted = true,
                ),
            ),
            reduction.state,
        )
    }

    @Test
    fun `세션 알림 끄기는 상시 표시를 다시 그리고 임계값 알림을 걷고 muted를 저장한다`() {
        val startedAtMillis = nowMillis - 34 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(4))

        val reduction = reduce(active, SessionEvent.MuteSession)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 34, muted = true),
                ),
                SessionEffect.DismissThresholdAlert,
                SessionEffect.SaveMuted(muted = true),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 알림 끄기 뒤에는 임계값에 닿아도 알림 없이 상시 표시만 초과로 바뀐다`() {
        val startedAtMillis = nowMillis - 30 * MINUTE_MILLIS
        val muted = mutedSince(startedAtMillis)

        val reduction = reduce(muted, SessionEvent.ThresholdReached)

        assertEquals(muted, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 30, muted = true),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 알림 끄기 뒤 1분 tick은 초과 상시 표시를 알림 꺼짐으로 그린다`() {
        val startedAtMillis = nowMillis - 34 * MINUTE_MILLIS
        val muted = mutedSince(startedAtMillis, alertedMinutesAgo(4))

        val reduction = reduce(muted, SessionEvent.MinuteTick)

        assertEquals(muted, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 34, muted = true),
                ),
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션이 닫히고 새 세션이 열리면 세션 알림 끄기가 풀린다`() {
        val muted = SessionState(
            Phase.Grace,
            Session(
                startedAtMillis = nowMillis - 50 * MINUTE_MILLIS,
                lockedAtMillis = nowMillis - 10 * MINUTE_MILLIS,
                alerts = alertedMinutesAgo(20),
                muted = true,
            ),
        )

        val expired = reduce(muted, SessionEvent.GraceExpired)
        val reopened = reduce(expired.state, SessionEvent.Unlock)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reopened.state,
        )
    }

    @Test
    fun `임계값 알림 토글이 꺼져 있으면 임계값에 닿아도 알림 없이 상시 표시만 초과로 바뀐다`() {
        val startedAtMillis = nowMillis - 30 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis)

        val reduction = reduce(active, SessionEvent.ThresholdReached, alertsDisabled)

        assertEquals(active, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 30, nextAlertMinutes = null),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `임계값 알림 토글을 세션 중에 끄면 상시 표시가 다음 알림을 예고하지 않는다`() {
        val startedAtMillis = nowMillis - 34 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(4))

        val reduction = reduce(active, SessionEvent.MinuteTick, alertsDisabled)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 34, nextAlertMinutes = null),
                ),
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `임계값 알림 토글이 꺼진 채 임계값을 넘겨도 알림 상태를 적지 않아 다시 켜면 알린다`() {
        val startedAtMillis = nowMillis - 34 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis)

        val whileDisabled = reduce(active, SessionEvent.MinuteTick, alertsDisabled)
        val afterEnabled = reduce(whileDisabled.state, SessionEvent.MinuteTick)

        assertEquals(active, whileDisabled.state)
        assertEquals(
            SessionEffect.PostThresholdAlert(
                ThresholdAlertContent(elapsedMinutes = 34, reAlertMinutes = 15, count = 1),
            ),
            afterEnabled.effects[1],
        )
    }

    @Test
    fun `유예 중에 세션 알림 끄기를 누르면 유예 중 그대로 muted가 된다`() {
        val startedAtMillis = nowMillis - 40 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, lockedAtMillis, alertedMinutesAgo(10))

        val reduction = reduce(grace, SessionEvent.MuteSession)

        assertEquals(
            SessionState(
                Phase.Grace,
                Session(
                    startedAtMillis = startedAtMillis,
                    lockedAtMillis = lockedAtMillis,
                    alerts = alertedMinutesAgo(10),
                    muted = true,
                ),
            ),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    graceContent(
                        startedAtMillis,
                        elapsedMinutes = 40,
                        graceRemainingMillis = 2 * MINUTE_MILLIS,
                    ),
                ),
                SessionEffect.DismissThresholdAlert,
                SessionEffect.SaveMuted(muted = true),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중에 끈 세션이 잠금 해제로 이어지면 세션 알림 끄기가 그대로 남는다`() {
        val startedAtMillis = nowMillis - 40 * MINUTE_MILLIS
        val grace = graceSince(startedAtMillis, nowMillis - MINUTE_MILLIS, alertedMinutesAgo(10))

        val muted = reduce(grace, SessionEvent.MuteSession)
        val resumed = reduce(muted.state, SessionEvent.Unlock)

        assertEquals(
            SessionState(
                Phase.Active,
                Session(
                    startedAtMillis = startedAtMillis,
                    alerts = alertedMinutesAgo(10),
                    muted = true,
                ),
            ),
            resumed.state,
        )
        assertEquals(
            SessionEffect.UpdatePersistentDisplay(
                activeContent(startedAtMillis, elapsedMinutes = 40, muted = true),
            ),
            resumed.effects.first(),
        )
    }

    @Test
    fun `이미 끈 세션에 세션 알림 끄기가 또 오면 아무 일도 하지 않는다`() {
        val muted = mutedSince(nowMillis - 34 * MINUTE_MILLIS, alertedMinutesAgo(4))

        val reduction = reduce(muted, SessionEvent.MuteSession)

        assertEquals(muted, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `세션 알림 끄기 뒤에는 재알림 주기가 지나도 재알림을 보내지 않는다`() {
        // 깨우기를 다시 걸지도 않는다. 직전 알림 시각이 더는 움직이지 않아, 다시 걸면 이미 지난
        // 시각으로 잡혀 곧바로 깨어나는 쳇바퀴가 된다.
        val muted = mutedSince(nowMillis - 45 * MINUTE_MILLIS, alertedMinutesAgo(15))

        val reduction = reduce(muted, SessionEvent.ReAlertIntervalElapsed)

        assertEquals(muted, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `임계값 알림 토글이 꺼져 있으면 재알림 주기가 지나도 재알림을 보내지 않는다`() {
        val active = activeSince(nowMillis - 45 * MINUTE_MILLIS, alertedMinutesAgo(15))

        val reduction = reduce(active, SessionEvent.ReAlertIntervalElapsed, alertsDisabled)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `세션 알림 끄기 뒤 1분 tick은 재알림 주기가 지났어도 다시 그리기만 한다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val muted = mutedSince(startedAtMillis, alertedMinutesAgo(15))

        val reduction = reduce(muted, SessionEvent.MinuteTick)

        assertEquals(muted, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 45, muted = true),
                ),
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `임계값 알림 토글을 다시 켜면 다음 1분 tick이 밀린 재알림을 집어 올린다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val active = activeSince(startedAtMillis, alertedMinutesAgo(15))

        val whileDisabled = reduce(active, SessionEvent.MinuteTick, alertsDisabled)
        val afterEnabled = reduce(whileDisabled.state, SessionEvent.MinuteTick)

        assertEquals(1, whileDisabled.state.session?.alerts?.count)
        assertEquals(2, afterEnabled.state.session?.alerts?.count)
    }

    @Test
    fun `알림이 막힌 세션에는 재알림 주기가 남았어도 깨우기를 다시 걸지 않는다`() {
        val muted = mutedSince(nowMillis - 40 * MINUTE_MILLIS, alertedMinutesAgo(10))

        val reduction = reduce(muted, SessionEvent.ReAlertIntervalElapsed)

        assertEquals(muted, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
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

    /** 세션 알림 끄기를 누른 뒤의 세션 진행. 알림은 이미 걷혔으므로 자취만 남는다. */
    private fun mutedSince(
        startedAtMillis: Long,
        alerts: AlertState = AlertState.None,
    ): SessionState = SessionState(
        Phase.Active,
        Session(startedAtMillis = startedAtMillis, alerts = alerts, muted = true),
    )

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
        muted: Boolean = false,
    ): PersistentDisplayContent = PersistentDisplayContent.Active(
        sessionStartedAtMillis = startedAtMillis,
        elapsedMinutes = elapsedMinutes,
        thresholdMinutes = thresholdMinutes,
        nextAlertMinutes = nextAlertMinutes,
        muted = muted,
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
