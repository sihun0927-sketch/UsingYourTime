package io.github.sihun0927.usingyourtime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/** 앱의 유일한 액티비티. 설정 화면 하나만 띄운다(스펙 5절). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            UsingTimeTheme {
                SettingsScreen()
            }
        }
    }
}
