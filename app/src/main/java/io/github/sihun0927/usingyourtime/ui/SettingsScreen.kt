package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/**
 * 설정 화면(스펙 5절). Scaffold가 edge-to-edge 인셋을 처리한다.
 *
 * 지금은 측정 시작·측정 중지 임시 버튼 하나뿐이다. 상태 카드·설정·도움말은 이후 티켓에서 채우며,
 * 그때 이 버튼은 상태 카드의 버튼으로 바뀐다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    trackingOn: Boolean,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
