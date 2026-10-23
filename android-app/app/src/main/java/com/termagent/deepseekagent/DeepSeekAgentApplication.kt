package com.termagent.deepseekagent

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner

class DeepSeekAgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())
    }

    companion object {
        private var instance: DeepSeekAgentApplication? = null

        fun getInstance(): DeepSeekAgentApplication {
            return instance!!
        }

        fun getContext(): Context {
            return instance!!.applicationContext
        }
    }

    init {
        instance = this
    }
}

class AppLifecycleObserver : androidx.lifecycle.DefaultLifecycleObserver {
    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
        // App entered foreground
    }

    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
        // App entered background
    }
}