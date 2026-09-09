package io.github.sihun0927.usingyourtime.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

private const val LOG_TAG = "UsingTime"

/**
 * 설정 화면(스펙 5절). Scaffold가 edge-to-edge 인셋을 처리한다.
 *
 * 지금은 측정 시작·측정 중지 임시 버튼과 맨 아래 개인정보처리방침 행뿐이다. 상태 카드·설정·도움말은
 * 이후 티켓에서 채우며, 그때 이 버튼은 상태 카드의 버튼으로 바뀐다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    trackingOn: Boolean,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (trackingOn) {
                    OutlinedButton(onClick = onPause) {
                        Text(stringResource(R.string.action_pause))
                    }
                } else {
                    Button(onClick = onStartTracking) {
                        Text(stringResource(R.string.action_start_tracking))
                    }
                }
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.privacy_policy),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { openPrivacyPolicy(context) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

/** 방침 페이지를 브라우저로 연다. 앱에는 `INTERNET` 권한이 없으므로 여는 것은 브라우저다. */
private fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, context.getString(R.string.privacy_policy_url).toUri())
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(LOG_TAG, "방침 URL을 열 브라우저가 없다", e)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenOffPreview() {
    UsingTimeTheme {
        SettingsScreen(trackingOn = false, onStartTracking = {}, onPause = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenOnPreview() {
    UsingTimeTheme {
        SettingsScreen(trackingOn = true, onStartTracking = {}, onPause = {})
    }
}
