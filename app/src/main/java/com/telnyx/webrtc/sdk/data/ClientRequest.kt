package com.telnyx.webrtc.sdk.data

import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.model.LogLevel

data class ClientRequest(
    val sipUserName: String = "isaac",
    val sipPassword: String = "isaac2",
    val sipCallerIdName: String = "isaacCall"
) : BaseClientRequest()

open class BaseClientRequest(
    val callerIdNumber: String = "",
    val fcmToken: String = "",
    var incomingCallRing: Int = R.raw.incoming_call,
    var ringBackTone: Int = R.raw.ringback_tone
)

fun ClientRequest.toCredentialConfig(mainFcMToken:String? = null): CredentialConfig {
    return CredentialConfig(
        sipUser = sipUserName,
        sipPassword = sipPassword,
        sipCallerIDName = sipCallerIdName,
        sipCallerIDNumber = callerIdNumber,
        ringBackTone = ringBackTone,
        ringtone = incomingCallRing,
        fcmToken = mainFcMToken ?: fcmToken,
        logLevel = LogLevel.ALL
    )
}


