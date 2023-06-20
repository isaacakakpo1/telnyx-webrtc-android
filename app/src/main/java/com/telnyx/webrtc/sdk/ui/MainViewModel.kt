/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.App
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.notification.ActiveCallService
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userManager: UserManager
) : ViewModel() {

    private var telnyxClient: TelnyxClient? = null

    private var currentCall: Call? = null
    private var previousCall: Call? = null

    private var calls: Map<UUID, Call> = mapOf()

    private val _selectedClientIndex = MutableStateFlow(Int.MAX_VALUE)
    val selectedClientIndex: StateFlow<Int>
        get() = _selectedClientIndex

    var selectedDestination = ""

    fun setSelectedIndex(value:Int){
        _selectedClientIndex.value = value
    }

    fun initConnection(context: Context, providedServerConfig: TxServerConfiguration?) {
        //Initialise after every connection
        App.txClient = TelnyxClient(context)
        telnyxClient = App.txClient
        providedServerConfig?.let {
            telnyxClient?.connect(it)
        } ?: run {
            telnyxClient?.connect()
        }

    }

    fun saveUserData(
        userName: String,
        password: String,
        fcmToken: String?,
        callerIdName: String,
        callerIdNumber: String,
        isDev: Boolean
    ) {
        if (!userManager.isUserLogin) {
            userManager.isUserLogin = true
            userManager.sipUsername = userName
            userManager.sipPass = password
            userManager.fcmToken = fcmToken
            userManager.callerIdName = callerIdName
            userManager.callerIdNumber = callerIdNumber
            userManager.isDev = isDev
        }
    }

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>>? =
        telnyxClient?.getSocketResponse()

    fun getWsMessageResponse(): LiveData<JsonObject>? = telnyxClient?.getWsMessageResponse()

    fun setCurrentCall(callId: UUID) {
        calls = telnyxClient?.getActiveCalls()!!
        if (calls.size > 1) {
            previousCall = currentCall
        }
        currentCall = calls[callId]
    }



    fun getCallState(): LiveData<CallState>? = currentCall?.getCallState()
    fun getIsMuteStatus(): LiveData<Boolean>? = currentCall?.getIsMuteStatus()
    fun getIsOnHoldStatus(): LiveData<Boolean>? = currentCall?.getIsOnHoldStatus()
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean>? = currentCall?.getIsOnLoudSpeakerStatus()

    fun doLoginWithCredentials(credentialConfig: CredentialConfig) {
        telnyxClient?.credentialLogin(credentialConfig)
    }

    fun doLoginWithToken(tokenConfig: TokenConfig) {
        telnyxClient?.tokenLogin(tokenConfig)
    }

    fun sendInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String
    ) {
        telnyxClient?.call?.newInvite(callerName, callerNumber, destinationNumber, clientState)
    }

    fun acceptCall(callId: UUID, destinationNumber: String) {
        telnyxClient?.call?.acceptCall(callId, destinationNumber)
    }

    fun endCall(callId: UUID? = null) {

        callId?.let {
            try {
                telnyxClient?.call?.endCall(callId)
            }catch (e:java.lang.Exception){
                Timber.e(e)
            }
        } ?: run {
            try {
                val clientCallId = telnyxClient?.call?.callId
                clientCallId?.let { telnyxClient?.call?.endCall(it) }
            }catch (e:Exception){
                Timber.e(e)
            }

        }
        previousCall?.let {
            currentCall = it
        }

    }

    fun stopActiveCallService(context: Context){
        try {
            context.apply {
                Timber.d("Launching ActiveCallService")
                val mainIntent = Intent(this, ActiveCallService::class.java).apply {
                    putExtra(ActiveCallService.STOP_SERVICE_KEY,true)
                } // Build the intent for the service
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this.startForegroundService(mainIntent)
                }else {
                    this.startService(mainIntent)
                }
            }
        }catch (e:Exception){
            Timber.e(e)
        }


    }

    fun onHoldUnholdPressed(callId: UUID) {
        currentCall?.onHoldUnholdPressed(callId)
    }

    fun onMuteUnmutePressed() {
        currentCall?.onMuteUnmutePressed()
    }

    fun onLoudSpeakerPressed() {
        currentCall?.onLoudSpeakerPressed()
    }

    fun dtmfPressed(callId: UUID, tone: String) {
        currentCall?.dtmf(callId, tone)
    }

    fun disconnect() {
        telnyxClient?.onDisconnect()
        userManager.isUserLogin = false
    }

    fun changeAudioOutput(audioDevice: AudioDevice) {
        telnyxClient?.setAudioOutputDevice(audioDevice)
    }
}
