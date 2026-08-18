package com.xaxka.openlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Activity 级主题偏好：界面组「深色模式 / 动态取色」（照源 AppConfig 界面组）。
 * 默认均关闭（Blue Light 固定浅色），开启后经 [com.xaxka.openlist.ui.theme.OpenListTheme] 生效。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    prefs: AppPrefsRepository,
) : ViewModel() {

    val darkMode: StateFlow<Boolean> = prefs.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
