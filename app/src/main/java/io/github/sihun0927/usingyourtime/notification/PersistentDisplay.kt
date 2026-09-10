package io.github.sihun0927.usingyourtime.notification

import android.app.Notification
import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.session.PersistentDisplayContent

/** 남은 유예를 초로 올림한다. 잠근 직후 "2:59"가 아니라 유예 시간 그대로 보이게 한다. */
private const val SECOND_MILLIS = 1_000L

/**
 * 상시 표시(스펙 4절). 채널 `tracking`, ongoing, 액션 없음, 잠금 화면 `VISIBILITY_PUBLIC`.
 * 포그라운드 서비스의 알림이라 측정이 켜진 동안 항상 있다.
 *
 * 임계값을 넘기면 제목에 "임계값 N분 초과"가 붙고, 막대가 가득 차고, 색이 경고색으로 바뀐다.
 * 셋은 [PersistentDisplayContent.Open.exceeded] 하나로 함께 움직인다(#16 결정).
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
        is PersistentDisplayContent.Active -> open(content, activeBody(content))

        is PersistentDisplayContent.Grace -> open(
            content = content,
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
     * 세션이 열려 있을 때의 상시 표시. 헤더 시간이 세션 시작 시각부터 올라가는 chronometer이고,
     * 막대는 임계값까지의 진행이다. 유예 중에도 셋 다 그대로다(스펙 4절).
     */
    private fun open(content: PersistentDisplayContent.Open, body: String): Notification = base(
        colorRes = if (content.exceeded) R.color.notification_warning else R.color.notification_accent,
    )
        .setContentTitle(
            if (content.exceeded) {
                context.getString(R.string.persistent_display_title_exceeded, content.thresholdMinutes)
            } else {
                context.getString(R.string.persistent_display_title_active)
            },
        )
        .setContentText(body)
        .setWhen(content.sessionStartedAtMillis)
        .setShowWhen(true)
        .setUsesChronometer(true)
        .setProgress(
            content.thresholdMinutes,
            content.elapsedMinutes.coerceAtMost(content.thresholdMinutes),
            false,
        )
        .build()

    /**
     * 세션 진행 중 본문(스펙 4절 표). 임계값 전에는 남은 시간을, 넘긴 뒤에는 다음 알림 예고를 적는다.
     *
     * 세션 알림 끄기 뒤에는 그 사실을 적는다. 알림이 멈춘 것이지 측정이 멈춘 것이 아니라는 말이
     * 필요한 자리다(스펙 4절 표).
     *
     * 예고할 다음 알림이 없으면 초과했다는 사실만 적는다. 임계값 알림 토글을 끈 동안, 유예 안에
     * 임계값을 넘긴 세션이 잠금 해제로 돌아온 직후(그 자리에서 알리는 일은 #26), 그리고 재알림이
     * 아직 없어 주기가 지나가 버린 동안(#24)이 그렇다.
     * 결정 배경은 `docs/adr/0003-persistent-display-body-when-no-next-alert.md`.
     */
    private fun activeBody(content: PersistentDisplayContent.Active): String = when {
        !content.exceeded -> context.getString(
            R.string.persistent_display_body_active,
            content.thresholdMinutes,
            (content.thresholdMinutes - content.elapsedMinutes).coerceAtLeast(0),
        )

        content.muted -> context.getString(R.string.persistent_display_body_muted)

        content.nextAlertMinutes != null -> context.getString(
            R.string.persistent_display_body_next_alert,
            content.nextAlertMinutes,
        )

        else -> context.getString(R.string.persistent_display_body_exceeded, content.thresholdMinutes)
    }

    /**
     * 세션 없음의 헤더 시간은 "없음"이라(스펙 4절 표) 기본값은 시간을 감춘 쪽이다.
     * 색도 마찬가지로 초과가 없는 쪽, 곧 앱 accent가 기본이다.
     */
    private fun base(@ColorRes colorRes: Int = R.color.notification_accent): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(settingsPendingIntent(context))

    /**
     * 남은 유예를 스펙 4절의 `m:ss`로 적는다. 유예는 길어야 15분이라 시간 자리가 필요 없다.
     * 초는 올림한다. 잠근 직후 "2:59"가 아니라 고른 유예 시간이 그대로 보여야 한다.
     */
    private fun minuteSeconds(remainingMillis: Long): String {
        val seconds = (remainingMillis + SECOND_MILLIS - 1) / SECOND_MILLIS
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    companion object {
        const val CHANNEL_ID = "tracking"

        /** 상시 표시 알림 id. 스펙 4절대로 하나로 고정한다. */
        const val NOTIFICATION_ID = 1
    }
}
