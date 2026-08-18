package com.xaxka.openlist

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xaxka.openlist.ui.nav.AppNavHost
import com.xaxka.openlist.ui.theme.OpenListTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认按浅色进入（偏好首值到达前的一帧保持 Blue Light 基线）
        applySystemBarStyle(dark = false)
        setContent {
            val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()

            // 系统栏图标外观随主题切换（浅色主题深色图标 / 深色主题浅色图标）
            LaunchedEffect(darkMode) { applySystemBarStyle(darkMode) }

            OpenListTheme(darkTheme = darkMode, dynamicColor = dynamicColor) {
                AppNavHost()
            }
        }
    }

    /** 深色主题下系统栏用浅色图标（透明底），避免白底白图标不可见 */
    private fun applySystemBarStyle(dark: Boolean) {
        if (dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            )
        }
    }
}
