/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.LifecycleObserver
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber


@HiltAndroidApp
class App : Application(), LifecycleObserver {


    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    companion object {
        //This takes the application context as it is alive as the app is not killed
        //Hilt or Koin Can be used to make the #txClient a singleton global variable
        @SuppressLint("StaticFieldLeak")
        var txClient: TelnyxClient? = null
    }


}