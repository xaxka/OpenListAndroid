package com.xaxka.openlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 主题/全局偏好状态（darkMode 与 dynamicColor 默认均为 false，保持固定 0xFF91C6FF 派生主题） */
@HiltViewModel
class MainViewModel @Inject constructor(
    prefs: AppPrefsRepository,
) : ViewModel() {
    val darkMode: StateFlow<Boolean> =
        prefs.darkMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val dynamicColor: StateFlow<Boolean> =
        prefs.dynamicColor.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
