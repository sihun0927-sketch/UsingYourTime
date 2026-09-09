package io.github.sihun0927.usingyourtime.ui

/**
 * 알림 권한 흐름의 국면(스펙 5절). 런타임 요청이 없는 API 32 이하에서는 항상 [Granted]다.
 */
enum class NotificationPermission {

    /** 허용됨, 또는 요청이 필요 없는 API 32 이하. "측정 시작"을 누르면 바로 서비스가 뜬다. */
    Granted,

    /** API 33+이고 아직 허용되지 않았다. 안내 1줄을 띄우고, "측정 시작"을 누르면 요청한다. */
    Required,

    /** 요청했지만 거부됐다. 안내 줄과 함께 "측정 시작"이 비활성이고 서비스는 뜨지 않는다. */
    Denied,
}
