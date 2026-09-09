package io.github.sihun0927.usingyourtime.tracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService

/**
 * 유예 만료로 깨우는 알람(스펙 8절). `setAndAllowWhileIdle`이라 Doze에서 늦게 올 수 있고,
 * 그래도 되도록 리듀서가 잠금 시각으로 만료를 다시 판정한다. exact alarm은 쓰지 않는다.
 *
 * 서비스 안 coroutine 타이머의 **보조 수단**이다. 화면이 꺼진 채 프로세스가 잠들면 타이머가
 * 늦어지므로, 같은 시각에 알람도 걸어 둔다.
 *
 * 깨우기는 서비스가 런타임 등록한 리시버로 가는 브로드캐스트다. 서비스가 없으면 받을 곳이 없어
 * 그냥 사라진다. 죽은 서비스를 알람이 되살리는 일은 없고, 재기동은 스펙 7절의 경로만 쓴다.
 */
class GraceExpiryAlarm(private val context: Context) {

    private val alarmManager = context.getSystemService<AlarmManager>()

    /** [atMillis](epoch)에 `유예 만료`로 깨운다. 같은 PendingIntent라 이전 예약을 덮어쓴다. */
    fun schedule(atMillis: Long) {
        alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent())
    }

    fun cancel() {
        alarmManager?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(ACTION).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** 앱 안에서만 오가는 액션. manifest에 리시버가 없어 밖에서는 받을 수도 보낼 수도 없다. */
        const val ACTION = "io.github.sihun0927.usingyourtime.action.GRACE_EXPIRED"

        private const val REQUEST_CODE = 1
    }
}
