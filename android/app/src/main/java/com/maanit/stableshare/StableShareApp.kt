package com.maanit.stableshare

import android.app.Application
import com.maanit.stableshare.di.AppContainer

class StableShareApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
