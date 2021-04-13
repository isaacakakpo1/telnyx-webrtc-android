package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.telnyx.webrtc.sdk.socket.TxCallSocket
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.ktor.client.features.websocket.*
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class CallTest: BaseTest() {


    @MockK private lateinit var mockContext: Context
    @MockK
    lateinit var client: TelnyxClient
    @MockK
    lateinit var callSocket: TxCallSocket
    @MockK
    lateinit var webSocketSession: DefaultClientWebSocketSession
    @MockK
    lateinit var audioManager: AudioManager

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, true, true, true)

        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )

        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager
        every { audioManager.isMicrophoneMute = true } just Runs
        every { audioManager.isSpeakerphoneOn = true } just Runs

        every { audioManager.isSpeakerphoneOn}  returns false
        every { audioManager.isMicrophoneMute}  returns false

        client = TelnyxClient(mockContext, socket)
        callSocket = TxCallSocket(webSocketSession)
    }

    @Test
    fun `test call listen doesn't throw exception`() {
        assertDoesNotThrow { val newCall = Call(
            mockContext,
            client,
            callSocket,
            "123",
            audioManager
        ) }
    }

    @Test
    fun `test ending call resets our call options`() {
        val newCall = Call(mockContext, client, callSocket, "123", audioManager)
        newCall.endCall(UUID.randomUUID())
        assertEquals(newCall.getIsMuteStatus().getOrAwaitValue(), false)
        assertEquals(newCall.getIsOnHoldStatus().getOrAwaitValue(), false)
        assertEquals(newCall.getIsOnLoudSpeakerStatus().getOrAwaitValue(), false)
    }

    @Test
    fun `test mute pressed during call`() {
        val newCall = Call(mockContext, client, callSocket, "123", audioManager)
        newCall.endCall(UUID.randomUUID())
        newCall.onMuteUnmutePressed()
        assertEquals(newCall.getIsMuteStatus().getOrAwaitValue(), true)
    }

    @Test
    fun `test hold pressed during call`() {
        val newCall = Call(mockContext, client, callSocket, "123", audioManager)
        newCall.onHoldUnholdPressed(UUID.randomUUID())
        assertEquals(newCall.getIsOnHoldStatus().getOrAwaitValue(), true)
    }

    @Test
    fun `test loudspeaker pressed during call`() {
        val newCall = Call(mockContext, client, callSocket, "123", audioManager)
        newCall.onLoudSpeakerPressed()
        assertEquals(newCall.getIsOnLoudSpeakerStatus().getOrAwaitValue(), true)
    }

}

//Extension function for getOrAwaitValue for unit tests
fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 10,
    timeUnit: TimeUnit = TimeUnit.SECONDS
): T {
    var data: T? = null
    val latch = CountDownLatch(1)
    val observer = object : Observer<T> {
        override fun onChanged(o: T?) {
            data = o
            latch.countDown()
            this@getOrAwaitValue.removeObserver(this)
        }
    }

    this.observeForever(observer)

    // Don't wait indefinitely if the LiveData is not set.
    if (!latch.await(time, timeUnit)) {
        throw TimeoutException("LiveData value was never set.")
    }

    @Suppress("UNCHECKED_CAST")
    return data as T
}