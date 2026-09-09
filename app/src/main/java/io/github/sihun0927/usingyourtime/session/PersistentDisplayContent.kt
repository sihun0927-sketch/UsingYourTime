package io.github.sihun0927.usingyourtime.session

/**
 * 상시 표시가 지금 보여줄 내용(스펙 4절 표). 리듀서가 `now`로 계산해 값으로 돌려주고,
 * `notification`이 문구·막대로 옮긴다. 여기에는 문자열도 리소스 id도 없다.
 */
sealed interface PersistentDisplayContent {

    /** 세션 진행. 헤더 chronometer가 [sessionStartedAtMillis]부터 올라가고 막대가 찬다. */
    data class Active(
        val sessionStartedAtMillis: Long,
        val elapsedMinutes: Int,
        val thresholdMinutes: Int,
    ) : PersistentDisplayContent

    /** 유예 중. chronometer·막대는 세션 진행과 같고 본문만 남은 유예로 바뀐다. */
    data class Grace(
        val sessionStartedAtMillis: Long,
        val elapsedMinutes: Int,
        val thresholdMinutes: Int,
        val graceRemainingMillis: Long,
    ) : PersistentDisplayContent

    /** 세션 없음. "측정 대기 중"만 남고 chronometer·막대가 없다. */
    data object Idle : PersistentDisplayContent
}
