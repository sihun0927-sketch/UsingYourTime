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
 */
object SessionReducer {

    fun reduce(
        state: SessionState,
        event: SessionEvent,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = when (event) {
        SessionEvent.StartTracking -> startTracking(state, nowMillis)
        SessionEvent.Pause -> pause(state)
    }

    /**
     * 측정 꺼짐 → 세션 진행. 버튼을 누른 시점은 잠금 해제 상태이므로 **지금** 세션을 연다.
     *
     * 상시 표시를 먼저 내보내야 서비스가 `startForeground`를 미루지 않는다.
     */
    private fun startTracking(state: SessionState, nowMillis: Long): Reduction {
        if (state.phase != Phase.Off) return Reduction(state)

        val session = Session(startedAtMillis = nowMillis)
        return Reduction(
            state = SessionState(Phase.Active, session),
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(session.startedAtMillis),
                SessionEffect.SaveTrackingOn(trackingOn = true),
            ),
        )
    }

    /** 세션 진행 → 측정 꺼짐. 열린 세션은 유예 없이 지금 닫힌다. */
    private fun pause(state: SessionState): Reduction {
        if (state.phase == Phase.Off) return Reduction(state)

        return Reduction(
            state = SessionState.Off,
            effects = listOf(
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.StopService,
            ),
        )
    }
}
