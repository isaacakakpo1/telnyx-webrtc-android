package com.telnyx.webrtc.sdk.verto.receive

import com.google.gson.annotations.SerializedName

data class FcmRegistrationResponse (
    @SerializedName("message") val message : String,
)