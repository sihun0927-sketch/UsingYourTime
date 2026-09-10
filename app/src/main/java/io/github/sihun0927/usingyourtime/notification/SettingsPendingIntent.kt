package io.github.sihun0927.usingyourtime.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.sihun0927.usingyourtime.ui.MainActivity

/**
 * 알림을 탭했을 때 열 설정 화면. 앱 아이콘·상시 표시·임계값 알림이 모두 같은 화면을 열고
 * 딥링크 구분이 없다(스펙 5절). 그래서 요청 코드도 하나를 함께 쓴다.
 */
internal fun settingsPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
