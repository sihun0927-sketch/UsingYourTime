package io.github.sihun0927.usingyourtime.session

/**
 * 리듀서가 판정에 쓰는 사용자 설정(스펙 6절). 기본값은 스펙 8절 DataStore 키의 기본값과 같다.
 *
 * 이 티켓의 두 전이는 설정을 보지 않지만, 리듀서 계약이 `(상태, 이벤트, 지금 시각, 설정)`이라
 * 처음부터 인자로 받는다. DataStore와 잇는 일은 설정 화면 티켓에서 한다.
 */
data class TrackingSettings(
    val thresholdMinutes: Int = 30,
    val graceMinutes: Int = 3,
    val reAlertMinutes: Int = 15,
    val thresholdAlertEnabled: Boolean = true,
)
