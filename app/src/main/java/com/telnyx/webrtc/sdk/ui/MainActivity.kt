/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.Manifest
import android.Manifest.permission.INTERNET
import android.Manifest.permission.RECORD_AUDIO
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.telnyx.webrtc.sdk.*
import com.telnyx.webrtc.sdk.data.ClientRequest
import com.telnyx.webrtc.sdk.data.toCredentialConfig
import com.telnyx.webrtc.sdk.manager.AppDataStore
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.notification.ActiveCallService
import com.telnyx.webrtc.sdk.ui.wsmessages.WsMessageFragment
import com.telnyx.webrtc.sdk.utility.MyFirebaseMessagingService
import com.telnyx.webrtc.sdk.utility.parseObject
import com.telnyx.webrtc.sdk.utility.showIf
import com.telnyx.webrtc.sdk.verto.receive.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.include_call_control_section.*
import kotlinx.android.synthetic.main.include_incoming_call_section.*
import kotlinx.android.synthetic.main.include_login_credential_section.*
import kotlinx.android.synthetic.main.include_login_section.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.*
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var appDataStore: AppDataStore

    private var invitationSent: Boolean = false
    private lateinit var mainViewModel: MainViewModel
    private var fcmToken: String? = null
    private var isDev = false
    private var isAutomaticLogin = false
    private var wsMessageList: ArrayList<String>? = null

    // Notification handling
    private var notificationAcceptHandling: Boolean? = null
    private val clients = BuildConfig.client_credentials.parseObject<List<ClientRequest>>()

    private var countDownTimer: Handler? = null
    private var runnable: Runnable? = null
    private val TIMER_DELAY = 1000
    private var callDuration = 0;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar_id))

        lifecycleScope.launch {
            appDataStore.changeEndCallStatus(false)
        }




        Timber.e("${clients?.size}")


        // Add environment text
        isDev = userManager.isDev
        updateEnvText(isDev)

        FirebaseApp.initializeApp(this)

        mainViewModel = ViewModelProvider(this@MainActivity).get(MainViewModel::class.java)

        checkPermissions()
        initViews()
        setClients()
    }

    private fun setClients() {
        val items = clients?.map {
            it.ringBackTone = R.raw.ringback_tone
            it.incomingCallRing = R.raw.incoming_call
            it.sipCallerIdName } ?: emptyList()
        var destinationItems = clients?.filter { it.sipCallerIdName != items.getOrNull(mainViewModel.selectedClientIndex.value) } ?: emptyList()
        val clientsAdapter = ArrayAdapter(this, R.layout.cleints_item, items)
        val callerNumbersAdapter = ArrayAdapter(this, R.layout.cleints_item, destinationItems.map {
            it.ringBackTone = R.raw.ringback_tone
            it.incomingCallRing = R.raw.incoming_call
            it.sipCallerIdName }.toMutableList().apply {
            add("Enter Test Number")
        })
        (clientsDropDown.editText as? AutoCompleteTextView)?.setAdapter(clientsAdapter)
        (callersDropdown.editText as? AutoCompleteTextView)?.setAdapter(callerNumbersAdapter)
        autoComplete.onItemClickListener =
            OnItemClickListener { _, _, position, _ -> mainViewModel.setSelectedIndex(position) }

        destAutoComplete.onItemClickListener = OnItemClickListener { _, _, position, _ ->
            mainViewModel.selectedDestination = destinationItems.getOrNull(position)?.sipUserName ?: ""
            customDestination.showIf {
                // Only Empty if last item is selected
                mainViewModel.selectedDestination.isEmpty() }
        }
        lifecycleScope.launchWhenStarted {
            mainViewModel.selectedClientIndex.collectLatest {index ->
                callerNumbersAdapter.clear()
                destinationItems = clients?.filter { it.sipCallerIdName != items.getOrNull(mainViewModel.selectedClientIndex.value) } ?: emptyList()
                callerNumbersAdapter.addAll(destinationItems.map { it.sipCallerIdName }.toMutableList().apply {
                    add("Enter Test Number")
                })
            }
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.actionbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_disconnect -> {
            if (userManager.isUserLogin) {
                disconnectPressed()
                isAutomaticLogin = false
            } else {
                Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            }
            true
        }

        R.id.action_change_audio_output -> {
            val dialog = createAudioOutputSelectionDialog()
            dialog.show()
            true
        }

        R.id.action_wsmessages -> {
            if (wsMessageList == null) {
                wsMessageList = ArrayList()
            }
            val instanceFragment = WsMessageFragment.newInstance(wsMessageList)
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, instanceFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    private fun createAudioOutputSelectionDialog(): Dialog {
        return this.let {
            val audioOutputList = arrayOf("Phone", "Bluetooth", "Loud Speaker")
            val builder = AlertDialog.Builder(this)
            // Set default to phone
            mainViewModel.changeAudioOutput(AudioDevice.PHONE_EARPIECE)
            builder.setTitle("Select Audio Output")
            builder.setSingleChoiceItems(
                audioOutputList, 0
            ) { _, which ->
                when (which) {
                    0 -> {
                        mainViewModel.changeAudioOutput(AudioDevice.PHONE_EARPIECE)
                    }

                    1 -> {
                        mainViewModel.changeAudioOutput(AudioDevice.BLUETOOTH)
                    }

                    2 -> {
                        mainViewModel.changeAudioOutput(AudioDevice.LOUDSPEAKER)
                    }
                }
            }
                // Set the action buttons
                .setNeutralButton(
                    "ok"
                ) { dialog, _ ->
                    dialog.dismiss()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun connectToSocketAndObserve() {
        if (!isDev) {
            mainViewModel.initConnection(applicationContext, null)
        } else {
            mainViewModel.initConnection(
                applicationContext,
                TxServerConfiguration(host = "rtcdev.telnyx.com")
            )
        }
        observeSocketResponses()
    }

    private fun observeSocketResponses() {
        mainViewModel.getSocketResponse()
            ?.observe(
                this,
                object : SocketObserver<ReceivedMessageBody>() {
                    override fun onConnectionEstablished() {
                        doLogin(isAutomaticLogin)
                    }

                    override fun onMessageReceived(data: ReceivedMessageBody?) {
                        Timber.d("onMessageReceived from SDK [%s]", data?.method)
                        when (data?.method) {
                            SocketMethod.CLIENT_READY.methodName -> {
                                Timber.d("You are ready to make calls.")
                            }

                            SocketMethod.LOGIN.methodName -> {
                                progress_indicator_id.visibility = View.INVISIBLE
                                val sessionId = (data.result as LoginResponse).sessid
                                Timber.d("Current Session: $sessionId")
                                onLoginSuccessfullyViews()
                            }

                            SocketMethod.INVITE.methodName -> {
                                val inviteResponse = data.result as InviteResponse
                                onReceiveCallView(
                                    inviteResponse.callId,
                                    inviteResponse.callerIdName,
                                    inviteResponse.callerIdNumber
                                )
                            }

                            SocketMethod.ANSWER.methodName -> {
                                val callId = (data.result as AnswerResponse).callId
                                launchCallInstance(callId)
                                call_button_id.visibility = View.VISIBLE
                                cancel_call_button_id.visibility = View.GONE
                                invitationSent = false
                            }

                            SocketMethod.BYE.methodName -> {
                                onByeReceivedViews()
                                mainViewModel.stopActiveCallService(applicationContext)
                                call_state_text_value.text = "-"
                            }
                            SocketMethod.RINGING.methodName -> {
                                Timber.e("Ringing Melody")
                            }
                        }
                    }

                    override fun onLoading() {
                        Timber.i("Loading...")
                    }

                    override fun onError(message: String?) {
                        Toast.makeText(
                            this@MainActivity,
                            message ?: "Socket Connection Error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onSocketDisconnect() {
                        Toast.makeText(
                            this@MainActivity,
                            "Socket is disconnected",
                            Toast.LENGTH_SHORT
                        ).show()

                        progress_indicator_id.visibility = View.INVISIBLE
                        incoming_call_section_id.visibility = View.GONE
                        call_control_section_id.visibility = View.GONE
                        login_section_id.visibility = View.VISIBLE

                        socket_text_value.text = getString(R.string.disconnected)
                        call_state_text_value.text = "-"
                    }
                }
            )
    }



    private fun observeWsMessage() {

        mainViewModel.getWsMessageResponse()?.observe(this) {
            it?.let { wsMesssage ->
                wsMessageList?.add(wsMesssage.toString())
            }
        }
    }

    private fun updateEnvText(isDevEnvironment: Boolean) {
        if (isDevEnvironment) {
            environment_text.text = "Dev"
        } else {
            environment_text.text = "Prod"
        }
    }

    private fun initViews() {

        mockInputs()
        handleUserLoginState()
        getFCMToken()
        observeWsMessage()
        setupTimer()

        //Handle call option observers
        mainViewModel.getCallState()?.observe(this) { value ->
            call_state_text_value.text = value.name
        }
        connect_button_id.setOnClickListener {
            if (!hasLoginEmptyFields()) {
                connectButtonPressed()
            }else {
                Toast.makeText(this,getString(R.string.select_client_msg),Toast.LENGTH_LONG).show()
            }
        }
        call_button_id.setOnClickListener {


            val number = mainViewModel.selectedDestination.ifEmpty {
                customDestinationTxt.text.toString()
            }

            if (mainViewModel.selectedDestination.isEmpty() && number.isEmpty()){
                Toast.makeText(this,getString(R.string.select_destination_msg),Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            Timber.e("Number: $number")


            mainViewModel.sendInvite(
                userManager.callerIdName,
                userManager.callerIdNumber,
                number,
                "Sample Client State"
            )
            call_button_id.visibility = View.GONE
            cancel_call_button_id.visibility = View.VISIBLE
        }
        cancel_call_button_id.setOnClickListener {
            mainViewModel.endCall()
            call_button_id.visibility = View.VISIBLE
            cancel_call_button_id.visibility = View.GONE
        }
        telnyx_image_id.setOnLongClickListener {
            onCreateSecretMenuDialog().show()
            true
        }
    }

    private fun setupTimer(){
        countDownTimer = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                countDownTimer!!.postDelayed(this, TIMER_DELAY.toLong())
                callDuration  =+ 1

            }
        }
    }

    private fun onCreateSecretMenuDialog(): Dialog {
        return this.let {
            val secretOptionList = arrayOf(
                "Development Environment",
                "Production Environment",
                "Copy Firebase Instance Token"
            )
            val builder = AlertDialog.Builder(it)
            builder.setTitle("Select Secret Setting")
                .setItems(
                    secretOptionList
                ) { _, which ->
                    when (which) {
                        0 -> {
                            // Switch to Dev
                            isDev = true
                            userManager.isDev = true
                            updateEnvText(isDev)
                            Toast.makeText(this, "Switched to DEV environment", Toast.LENGTH_LONG)
                                .show()
                        }

                        1 -> {
                            // Switch to Prod
                            isDev = false
                            userManager.isDev = false
                            updateEnvText(isDev)
                            Toast.makeText(this, "Switched to PROD environment", Toast.LENGTH_LONG)
                                .show()
                        }

                        2 -> {
                            val clipboardManager =
                                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = ClipData.newPlainText("text", fcmToken)
                            clipboardManager.setPrimaryClip(clipData)
                            Toast.makeText(this, "FCM Token copied to clipboard", Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun hasLoginEmptyFields(): Boolean {
        Timber.e("Index ${mainViewModel.selectedClientIndex.value}")
        return mainViewModel.selectedClientIndex.value == Int.MAX_VALUE
    }

    private fun showEmptyFieldsToast() {
        Toast.makeText(this, getString(R.string.empty_msg_toast), Toast.LENGTH_LONG).show()
    }

    private fun mockInputs() {
        sip_username_id.setText(BuildConfig.MOCK_USERNAME)
        sip_password_id.setText(BuildConfig.MOCK_PASSWORD)
        caller_id_name_id.setText(MOCK_CALLER_NAME)
        caller_id_number_id.setText(MOCK_CALLER_NUMBER)
        call_input_id.setText(MOCK_DESTINATION_NUMBER)
    }

    private fun handleUserLoginState() {
        listenLoginTypeSwitch()
        if (!userManager.isUserLogin) {
            login_section_id.visibility = View.VISIBLE
            incoming_call_section_id.visibility = View.GONE
            call_control_section_id.visibility = View.GONE
        } else {
            isAutomaticLogin = true
            //connectButtonPressed()
        }
    }

    private fun listenLoginTypeSwitch() {
        token_login_switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                login_credential_id.visibility = View.GONE
                login_token_id.visibility = View.VISIBLE
            } else {
                login_credential_id.visibility = View.VISIBLE
                login_token_id.visibility = View.GONE
            }
        }
    }

    private fun connectButtonPressed() {
        progress_indicator_id.visibility = View.VISIBLE
        connectToSocketAndObserve()
    }

    private fun doLogin(isAuto: Boolean) {
        if (isAuto) {
            val loginConfig = clients?.get(mainViewModel.selectedClientIndex.value)?.toCredentialConfig(fcmToken)
            loginConfig ?: return
            mainViewModel.doLoginWithCredentials(loginConfig)
        } else {
            if (token_login_switch.isChecked) {
                 /*val sipToken = sip_token_id.text.toString()
                 val sipCallerName = token_caller_id_name_id.text.toString()
                 val sipCallerNumber = token_caller_id_number_id.text.toString()

                 val loginConfig = TokenConfig(
                     sipToken,
                     sipCallerName,
                     sipCallerNumber,
                     fcmToken,
                     ringtone,
                     ringBackTone,
                     LogLevel.ALL
                 )
                 mainViewModel.doLoginWithToken(loginConfig)*/
             }
             else {
                 val sipUsername = sip_username_id.text.toString()
                 val password = sip_password_id.text.toString()
                 val sipCallerName = caller_id_name_id.text.toString()
                 val sipCallerNumber = caller_id_number_id.text.toString()

                val loginConfig = clients?.get(mainViewModel.selectedClientIndex.value)?.toCredentialConfig(fcmToken)
                loginConfig ?: return
                mainViewModel.doLoginWithCredentials(loginConfig)
             }
        }
    }

    private fun getFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.d("Fetching FCM registration token failed")
                fcmToken = null
                Toast.makeText(baseContext, "Fetching FCM registration token failed", Toast.LENGTH_SHORT).show()
                return@OnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            fcmToken = token
            // Log and toast
            Timber.e("Token-T $fcmToken")
            Toast.makeText(baseContext, token, Toast.LENGTH_SHORT).show()
        })

    }

    private fun disconnectPressed() {
        mainViewModel.disconnect()
    }

    private fun onLoginSuccessfullyViews() {
        socket_text_value.text = getString(R.string.connected,clients?.getOrNull( mainViewModel.selectedClientIndex.value ?: 0)?.sipCallerIdName ?: "")
        login_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE

        // Don't store login details if logged in via a token
        if (!token_login_switch.isChecked) {
            // Set Shared Preferences now that user has logged in - storing the session:
            mainViewModel.saveUserData(
                sip_username_id.text.toString(),
                sip_password_id.text.toString(),
                fcmToken,
                caller_id_name_id.text.toString(),
                caller_id_number_id.text.toString(),
                isDev
            )
        }
    }

    private fun launchCallInstance(callId: UUID) {
        mainViewModel.setCurrentCall(callId)
        val callInstanceFragment = CallInstanceFragment.newInstance(callId.toString())
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_call_instance, callInstanceFragment)
            .commit()
       launchActiveCallService(callId)
    }

    private fun launchActiveCallService(callId: UUID){
        Timber.d("Launching ActiveCallService")
        val mainIntent = Intent(this, ActiveCallService::class.java).apply {
            putExtra(ActiveCallService.CALL_ID_KEY,callId.toString())

        } // Build the intent for the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.startForegroundService(mainIntent)
        }else {
            this.startService(mainIntent)
        }
    }



    private fun onByeReceivedViews() {
        invitationSent = false
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE
        call_button_id.visibility = View.VISIBLE
        cancel_call_button_id.visibility = View.GONE
    }

    private fun onReceiveCallView(callId: UUID, callerIdName: String, callerIdNumber: String) {
        mainViewModel.setCurrentCall(callId)
        when (notificationAcceptHandling) {
            true -> {
                Thread.sleep(1000)
                onAcceptCall(callId, callerIdNumber)
                notificationAcceptHandling = null
            }

            false -> {
                onRejectCall(callId)
                notificationAcceptHandling = null
            }

            else -> {
                call_control_section_id.visibility = View.GONE
                incoming_call_section_id.visibility = View.VISIBLE
                incoming_call_section_id.bringToFront()

                answer_call_id.setOnClickListener {
                    onAcceptCall(callId, callerIdNumber)
                }
                reject_call_id.setOnClickListener {
                    onRejectCall(callId)
                }
            }
        }
    }

    private fun onAcceptCall(callId: UUID, destinationNumber: String) {
        incoming_call_section_id.visibility = View.GONE
        // Visible but underneath fragment
        call_control_section_id.visibility = View.VISIBLE

        mainViewModel.acceptCall(callId, destinationNumber)
        launchCallInstance(callId)
    }

    private fun onRejectCall(callId: UUID) {
        // Reject call and make call control section visible
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE

        mainViewModel.endCall(callId)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dexter.withContext(this)
                .withPermissions(
                    RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()) {
                            connect_button_id.isClickable = true
                        } else if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "permissions are required to continue",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permission: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                }).check()
        } else {
            Dexter.withContext(this)
                .withPermissions(
                    RECORD_AUDIO,
                    INTERNET
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()) {
                            connect_button_id.isClickable = true
                        } else if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "permissions are required to continue",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permission: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                }).check()
        }
    }

    private fun handleCallNotification() {
        val action = intent.extras?.get(MyFirebaseMessagingService.EXT_KEY_DO_ACTION) as String?

        action?.let {
            if (action == MyFirebaseMessagingService.ACT_ANSWER_CALL) {
                // Handle Answer
                notificationAcceptHandling = true
            } else if (action == MyFirebaseMessagingService.ACT_REJECT_CALL) {
                // Handle Reject
                notificationAcceptHandling = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleCallNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e("Tag","Destroyed")
    }

    override fun onStop() {
        super.onStop()
        Log.e("Tag","onStop")
    }
}
