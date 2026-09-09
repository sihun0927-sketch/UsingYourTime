package io.github.sihun0927.usingyourtime.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.session.PersistentDisplayContent
import io.github.sihun0927.usingyourtime.ui.MainActivity

/** 남은 유예를 초로 올림한다. 잠근 직후 "2:59"가 아니라 유예 시간 그대로 보이게 한다. */
private const val SECOND_MILLIS = 1_000L

/**
 * 상시 표시(스펙 4절). 채널 `tracking`, ongoing, 액션 없음, 잠금 화면 `VISIBILITY_PUBLIC`.
 * 포그라운드 서비스의 알림이라 측정이 켜진 동안 항상 있다.
 *
 * 임계값 초과 표기와 경고색은 이후 티켓(#23)에서 들어온다.
 */
class PersistentDisplay(private val context: Context) {

    /** 알림을 만들기 전에 한 번 부른다. 이미 있으면 아무 일도 하지 않는다. */
    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_tracking_name))
            .setDescription(context.getString(R.string.notification_channel_tracking_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** 리듀서가 고른 [content]를 스펙 4절 표의 제목·본문·막대로 옮긴다. */
    fun build(content: PersistentDisplayContent): Notification = when (content) {
        is PersistentDisplayContent.Active -> session(
            sessionStartedAtMillis = content.sessionStartedAtMillis,
            elapsedMinutes = content.elapsedMinutes,
            thresholdMinutes = content.thresholdMinutes,
            body = context.getString(
                R.string.persistent_display_body_active,
                content.thresholdMinutes,
                (content.thresholdMinutes - content.elapsedMinutes).coerceAtLeast(0),
            ),
        )

        is PersistentDisplayContent.Grace -> session(
            sessionStartedAtMillis = content.sessionStartedAtMillis,
            elapsedMinutes = content.elapsedMinutes,
            thresholdMinutes = content.thresholdMinutes,
            body = context.getString(
                R.string.persistent_display_body_grace,
                minuteSeconds(content.graceRemainingMillis),
            ),
        )

        PersistentDisplayContent.Idle -> base()
            .setContentTitle(context.getString(R.string.persistent_display_title_idle))
            .setContentText(context.getString(R.string.persistent_display_body_idle))
            .build()
    }

    /**
     * 세션이 열려 있을 때의 상시 표시. 헤더 시간이 [sessionStartedAtMillis]부터 올라가는
     * chronometer이고, 막대는 임계값까지의 진행이다. 유예 중에도 둘 다 그대로다(스펙 4절).
     */
    private fun session(
        sessionStartedAtMillis: Long,
        elapsedMinutes: Int,
        thresholdMinutes: Int,
        body: String,
    ): Notification = base()
        .setContentTitle(context.getString(R.string.persistent_display_title_active))
        .setContentText(body)
        .setWhen(sessionStartedAtMillis)
        .setShowWhen(true)
        .setUsesChronometer(true)
        .setProgress(thresholdMinutes, elapsedMinutes.coerceAtMost(thresholdMinutes), false)
        .build()

    /** 세션 없음의 헤더 시간은 "없음"이라(스펙 4절 표) 기본값은 시간을 감춘 쪽이다. */
    private fun base(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(settingsPendingIntent())

    /**
     * 남은 유예를 스펙 4절의 `m:ss`로 적는다. 유예는 길어야 15분이라 시간 자리가 필요 없다.
     * 초는 올림한다. 잠근 직후 "2:59"가 아니라 고른 유예 시간이 그대로 보여야 한다.
     */
    private fun minuteSeconds(remainingMillis: Long): String {
        val seconds = (remainingMillis + SECOND_MILLIS - 1) / SECOND_MILLIS
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** 탭하면 설정 화면. 앱 아이콘·알림 모두 같은 화면을 열고 딥링크 구분이 없다(스펙 5절). */
    private fun settingsPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "tracking"

        /** 상시 표시 알림 id. 스펙 4절대로 하나로 고정한다. */
        const val NOTIFICATION_ID = 1
    }
}
