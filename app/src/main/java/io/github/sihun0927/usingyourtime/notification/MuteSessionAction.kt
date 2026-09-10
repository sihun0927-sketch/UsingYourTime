package io.github.sihun0927.usingyourtime.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 임계값 알림의 액션 버튼이 보내는 브로드캐스트의 액션. 서비스가 런타임 등록한 리시버가 받아
 * `세션 알림 끄기` 이벤트로 옮긴다(스펙 3절 이벤트 표).
 *
 * 앱 안에서만 오간다. manifest에 리시버가 없어 밖에서는 받을 수도 보낼 수도 없다.
 */
internal const val ACTION_MUTE_SESSION = "io.github.sihun0927.usingyourtime.action.MUTE_SESSION"

/** 알림 탭·유예 만료 알람과 겹치지 않는 요청 코드. */
private const val REQUEST_CODE = 2

/**
 * "이번 세션 알림 끄기" 버튼이 누를 것.
 *
 * 서비스로 바로 가지 않고 브로드캐스트를 쓴다. 끌 세션은 서비스가 들고 있으므로, 서비스가 없으면
 * 끌 것도 없어 이 브로드캐스트가 받는 곳 없이 사라지는 편이 맞다. `GraceExpiryAlarm`의 깨우기와
 * 같은 길이고, 죽은 서비스를 알림 버튼이 되살리는 일도 없다.
 */
internal fun muteSessionPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    REQUEST_CODE,
    Intent(ACTION_MUTE_SESSION).setPackage(context.packageName),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
