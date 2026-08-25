package com.xaxka.openlist

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.xaxka.openlist.service.ServerManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OpenListApp : Application(), DefaultLifecycleObserver {

    @Inject
    lateinit var serverManager: ServerManager

    override fun onCreate() {
        // 显式指明 Application：DefaultLifecycleObserver 也有 onCreate，二者并存时 super 有歧义
        super<Application>.onCreate()
        // 进程前后台感知：回前台（ON_START）时校验并按需恢复 EasyTier 实例。
        // OPPO 等厂商后台冻结/清理进程后，原生实例可能丢失而应用层无感，
        // 需要在回前台的确定时机主动对账（见 ServerManager.onAppForegrounded）。
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        serverManager.onAppForegrounded()
    }
}
