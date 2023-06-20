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
    val incomingCallRing: Int = R.raw.incoming_call,
    val ringBackTone: Int = R.raw.ringback_tone
)

fun ClientRequest.toCredentialConfig(): CredentialConfig {
    return CredentialConfig(
        sipUser = sipUserName,
        sipPassword = sipPassword,
        sipCallerIDName = sipCallerIdName,
        sipCallerIDNumber = callerIdNumber,
        ringBackTone = ringBackTone,
        ringtone = incomingCallRing,
        fcmToken = fcmToken,
        logLevel = LogLevel.ALL
    )
}


