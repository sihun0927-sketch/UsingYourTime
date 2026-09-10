package io.github.sihun0927.usingyourtime.session

/**
 * 상시 표시가 지금 보여줄 내용(스펙 4절 표). 리듀서가 `now`로 계산해 값으로 돌려주고,
 * `notification`이 문구·막대로 옮긴다. 여기에는 문자열도 리소스 id도 없다.
 */
sealed interface PersistentDisplayContent {

    /**
     * 세션이 열려 있을 때. 세션 진행과 유예 중이 헤더 chronometer·막대·색을 같은 규칙으로 쓰고,
     * 본문만 서로 다르다(스펙 4절 표).
     */
    sealed interface Open : PersistentDisplayContent {
        val sessionStartedAtMillis: Long
        val elapsedMinutes: Int
        val thresholdMinutes: Int

        /**
         * 연속 사용 시간이 임계값에 닿았는지. 제목의 "임계값 N분 초과"·가득 찬 막대·경고색이 이
         * 하나로 함께 움직인다(스펙 4절 경고색 규칙). 잠긴 동안에도 유지된다.
         */
        val exceeded: Boolean get() = elapsedMinutes >= thresholdMinutes
    }

    /** 세션 진행. 헤더 chronometer가 [sessionStartedAtMillis]부터 올라가고 막대가 찬다. */
    data class Active(
        override val sessionStartedAtMillis: Long,
        override val elapsedMinutes: Int,
        override val thresholdMinutes: Int,
        /**
         * 다음 임계값 알림까지 남은 분. 초과 상태의 본문 "다음 알림 N분 후"가 된다.
         *
         * 예고할 다음 알림이 없으면(아직 알린 적이 없거나 그 시각이 이미 지났으면) null이고,
         * 그때 초과 본문은 사실만 적는다. `docs/adr/0003-persistent-display-body-when-no-next-alert.md`.
         */
        val nextAlertMinutes: Int? = null,

        /**
         * 세션 알림 끄기 뒤인지. 참이면 초과 본문이 "임계값 초과 · 이번 세션 알림 꺼짐"이 된다
         * (스펙 4절 표). 초과 전에는 본문이 남은 시간이라 이 값이 보이지 않는다.
         */
        val muted: Boolean = false,
    ) : Open

    /** 유예 중. chronometer·막대는 세션 진행과 같고 본문만 남은 유예로 바뀐다. */
    data class Grace(
        override val sessionStartedAtMillis: Long,
        override val elapsedMinutes: Int,
        override val thresholdMinutes: Int,
        val graceRemainingMillis: Long,
    ) : Open

    /** 세션 없음. "측정 대기 중"만 남고 chronometer·막대가 없다. */
    data object Idle : PersistentDisplayContent
}
