package io.github.sihun0927.usingyourtime.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.MainActivity

/**
 * 상시 표시(스펙 4절). 채널 `tracking`, ongoing, 액션 없음, 잠금 화면 `VISIBILITY_PUBLIC`.
 * 포그라운드 서비스의 알림이라 측정이 켜진 동안 항상 있다.
 *
 * 지금은 세션 진행 문구 하나뿐이다. 대기·유예 문구와 임계값 막대는 이후 티켓에서 들어온다.
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

    /** 세션 진행 중의 상시 표시. 헤더 시간이 [sessionStartedAtMillis]부터 올라가는 chronometer다. */
    fun buildActive(sessionStartedAtMillis: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.persistent_display_title_active))
            .setWhen(sessionStartedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(settingsPendingIntent())
            .build()

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
