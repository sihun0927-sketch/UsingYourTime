package io.github.sihun0927.usingyourtime.session

/**
 * 측정 상태의 국면(스펙 3절, `CONTEXT.md`의 상태 4종).
 *
 * 지금은 두 국면뿐이고 `Idle`(세션 없음)·`Grace`(유예 중)는 이후 티켓에서 들어온다.
 */
enum class Phase {
    /** 측정 꺼짐. 측정 시작을 누른 적이 없거나 측정 중지를 누른 뒤. */
    Off,

    /** 세션 진행. 잠금 해제 상태이고 세션이 열려 있다. */
    Active,
}

/**
 * 열린 연속 사용 세션. 시각은 모두 epoch 밀리초다(스펙 8절).
 *
 * 세션 기록을 Room에 남기는 일은 복구 티켓에서 붙는다. 여기에는 리듀서가 판정에 쓰는 값만 둔다.
 */
data class Session(
    val startedAtMillis: Long,
)

/**
 * 리듀서가 주고받는 상태 전체(스펙 3절). Android 타입을 담지 않는 순수 값이라 값 비교로 검증한다.
 */
data class SessionState(
    val phase: Phase,
    val session: Session? = null,
) {
    init {
        require((phase == Phase.Active) == (session != null)) {
            "$phase 국면과 열린 세션 유무가 맞지 않는다: session=$session"
        }
    }

    companion object {
        /** 측정 꺼짐. 앱을 처음 켰을 때의 상태다. */
        val Off = SessionState(Phase.Off)
    }
}
