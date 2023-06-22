package com.telnyx.webrtc.sdk.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.datastore.core.DataStore
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.telnyx.webrtc.sdk.App
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.manager.AppDataStore
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.notification.ActiveCallService.Companion.NOTIFICATION_ID_KEY
import com.telnyx.webrtc.sdk.ui.MainActivity
import com.telnyx.webrtc.sdk.ui.isServiceForegrounded
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.Exception
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ActiveCallService : LifecycleService() {


    private var notificationManager: NotificationManager? = null

    @Inject
    lateinit var appDataStore: AppDataStore


    override fun onCreate() {
        super.onCreate()
    }


    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = getString(R.string.ongoing_call)
            val description =
                getString(R.string.active_call)
            val importance: Int = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance)
            channel.description = description
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("CheckResult")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(STOP_SERVICE_KEY, false) == true) {
            try {
                lifecycleScope.launch {
                    appDataStore.changeEndCallStatus(true)
                    delay(1000)
                    if (applicationContext.isServiceForegrounded(ActiveCallService::class.java)){
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            this@ActiveCallService.stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            stopForeground(true)
                        }
                        stopSelf()
                    }

                }

            } catch (e: Exception) {
                Timber.e(e)
            }
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val callId = intent?.getStringExtra(CALL_ID_KEY) ?: App.txClient?.call?.callId ?: ""

        val endCallIntent = Intent(this, EndCallBroadCastReceiver::class.java).apply {
            putExtra(CALL_ID_KEY, callId)
            putExtra(NOTIFICATION_ID_KEY, notificationId)
        }

        val endCallPendingIntent: PendingIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                endCallIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        val endCallAction: NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                R.drawable.ic_call_end_black,
                getString(R.string.end), endCallPendingIntent
            ).build()


        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_app_icon)
            .setContentIntent(createPendingIntent())
            .setContentTitle(this.getString(R.string.ongoing_call))
            .setContentText(
                this.getString(
                    R.string.active_call
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(endCallAction)

        builder.setChannelId(channelId)
        notificationManager?.notify(notificationId, builder.build())
        startForeground(notificationId, builder.build())


        return START_NOT_STICKY
    }


    private fun createPendingIntent(): PendingIntent? {
        return TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(createGoToTransportIntent())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getPendingIntent(
                    0,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                getPendingIntent(
                    0,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        }
    }

    private fun createGoToTransportIntent(): Intent {
        return Intent(this, MainActivity::class.java)
    }

    companion object {
        const val CALL_ID_KEY = "CALL_ID_KEY"
        const val NOTIFICATION_ID_KEY = "NOTIFICATION_ID_KEY"
        const val notificationId = 500
        const val channelId = "org.telnyx.activecall.channelid"
        const val STOP_SERVICE_KEY = "org.telnyx.activecall.stopservice"
    }


}

class EndCallBroadCastReceiver() : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Timber.d("End Call from Notification")
        if (App.txClient == null) {
            return
        }
        val callId = intent?.getStringExtra(ActiveCallService.CALL_ID_KEY)
            ?: App.txClient?.call?.callId.toString() ?: ""
        App.txClient?.call?.endCall(UUID.fromString(callId))

        context ?: return
        context.apply {
            val mainIntent = Intent(context, ActiveCallService::class.java).apply {
                putExtra(ActiveCallService.STOP_SERVICE_KEY, true)
            } // Build the intent for the service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.startForegroundService(mainIntent)
            } else {
                this.startService(mainIntent)
            }
        }


    }
}