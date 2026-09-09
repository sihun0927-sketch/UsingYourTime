package io.github.sihun0927.usingyourtime.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import io.github.sihun0927.usingyourtime.R

// 설정 화면이 여는 앱 밖 화면들(스펙 5절). 열 앱이 없는 기기가 있을 수 있어 실패는 로그만 남긴다.

private const val LOG_TAG = "UsingTime"

/** 개인정보처리방침. 앱에는 `INTERNET` 권한이 없으므로 방침을 내려받는 것은 브라우저다. */
internal fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, context.getString(R.string.privacy_policy_url).toUri())
    startExternal(context, intent, "방침 URL을 열 브라우저가 없다")
}

/**
 * 앱 정보 화면. 알림 권한을 거부한 사용자가 여기서 다시 켠다(스펙 5절 앱 정보 딥링크).
 * 도움말의 배터리 최적화 안내도 같은 화면을 쓴다(스펙 5절 3항, 티켓 #29).
 */
internal fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData("package:${context.packageName}".toUri())
    startExternal(context, intent, "앱 정보 화면을 열 수 없다")
}

private fun startExternal(context: Context, intent: Intent, failureMessage: String) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(LOG_TAG, failureMessage, e)
    }
}
