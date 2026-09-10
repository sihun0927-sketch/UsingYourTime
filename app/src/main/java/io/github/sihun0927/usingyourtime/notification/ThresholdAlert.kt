package io.github.sihun0927.usingyourtime.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.session.ThresholdAlertContent

/**
 * 임계값 알림(스펙 4절). 채널 `threshold`, `IMPORTANCE_HIGH`라 heads-up으로 뜨고 소리·진동은
 * 채널 기본값을 따른다. 상시 표시와 달리 사용자가 지울 수 있고, 지워도 다음 재알림이 다시 온다.
 *
 * 이 알림은 항상 임계값을 넘긴 상태에서만 뜨므로 색이 늘 경고색이다(#16 결정).
 *
 * 액션 버튼 "이번 세션 알림 끄기"와 재알림 본문·부제 회차는 이후 티켓(#24·#25)에서 들어온다.
 */
class ThresholdAlert(private val context: Context) {

    /** 알림을 만들기 전에 한 번 부른다. 이미 있으면 아무 일도 하지 않는다. */
    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.notification_channel_threshold_name))
            .setDescription(context.getString(R.string.notification_channel_threshold_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** 리듀서가 고른 [content]를 스펙 4절 표의 제목·본문으로 옮긴다. 본문은 한 줄이다(#7 결정). */
    fun build(content: ThresholdAlertContent): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.threshold_alert_title, content.elapsedMinutes))
            .setContentText(context.getString(R.string.threshold_alert_body_first, content.reAlertMinutes))
            .setColor(ContextCompat.getColor(context, R.color.notification_warning))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(settingsPendingIntent(context))
            .build()

    companion object {
        const val CHANNEL_ID = "threshold"

        /** 임계값 알림 id. 재알림도 같은 id로 다시 게시해 알림 창에 한 개만 남는다(스펙 4절). */
        const val NOTIFICATION_ID = 2
    }
}
