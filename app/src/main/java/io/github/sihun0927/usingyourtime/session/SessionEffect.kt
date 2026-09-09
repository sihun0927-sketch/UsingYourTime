package io.github.sihun0927.usingyourtime.session

/**
 * 리듀서가 돌려주는 부수효과(스펙 3절). 값일 뿐이고, 실행은 `tracking`의 서비스가 한다.
 *
 * 지금은 상시 표시·설정 저장·서비스 종료뿐이고, 알람 예약이나 임계값 알림은 이후 티켓에서 들어온다.
 */
sealed interface SessionEffect {

    /** 상시 표시를 게시하거나 갱신한다. 헤더 chronometer가 [sessionStartedAtMillis]부터 올라간다. */
    data class UpdatePersistentDisplay(val sessionStartedAtMillis: Long) : SessionEffect

    /** DataStore의 `tracking_on`을 저장한다(스펙 8절). */
    data class SaveTrackingOn(val trackingOn: Boolean) : SessionEffect

    /** 상시 표시를 지우고 포그라운드 서비스를 내린다. */
    data object StopService : SessionEffect
}
