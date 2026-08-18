package com.xaxka.openlist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xaxka.openlist.ui.nav.AppNavHost
import com.xaxka.openlist.ui.theme.OpenListTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenListRoot()
        }
    }
}

@Composable
private fun OpenListRoot(viewModel: MainViewModel = hiltViewModel()) {
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()

    OpenListTheme(darkTheme = darkMode, dynamicColor = dynamicColor) {
        AppNavHost()
    }
}
