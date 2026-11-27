package com.laserdroid

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.jobs.JobSnapshot
import com.laserdroid.core.jobs.JobStartResult
import com.laserdroid.core.jobs.RecoveryAssessment
import com.laserdroid.core.jobs.RecoveryMode
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.machine.MachineOperationResult
import com.laserdroid.core.machine.MachineSnapshot
import com.laserdroid.core.remote.RemoteMode
import com.laserdroid.core.remote.RemoteStatus
import com.laserdroid.service.AppSettings
import com.laserdroid.service.JobHistoryEntry
import com.laserdroid.service.LogLevel
import com.laserdroid.service.MachineService
import com.laserdroid.service.MachineServiceConnection
import com.laserdroid.ui.common.ChoiceChipsView
import com.laserdroid.ui.job.JobPanelView
import com.laserdroid.ui.jog.JogDirection
import com.laserdroid.ui.jog.JogPanelView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Main screen: status bar (connection, state, position), three pages
 * (JOG, JOB, CONSOLE) and a navigation bar (bottom in portrait,
 * rail in landscape).
 *
 * The Activity only displays and forwards commands: the machine
 * backend lives in [MachineService] and survives rotations.
 */
class MainActivity : Activity() {

    companion object {

        private const val ACTION_USB_PERMISSION =
            "com.laserdroid.USB_PERMISSION"

        private const val CONTINUOUS_JOG_INTERVAL_MS = 150L

        private const val SAVED_JOG_SPEED = "laserdroid.saved.jog.speed"
        private const val SAVED_JOG_STEP = "laserdroid.saved.jog.step"
        private const val SAVED_PAGE = "laserdroid.saved.page"
        private const val SAVED_LASER_OFF = "laserdroid.saved.laser.off"

        private const val MAX_LOG_CHARACTERS = 60_000

        private const val REQUEST_OPEN_GCODE = 4201

        private const val JOB_DISPLAY_REFRESH_MS = 1_000L

        private const val PAGE_JOG = 0
        private const val PAGE_JOB = 1
        private const val PAGE_SERVER = 2
        private const val PAGE_CONSOLE = 3
        private const val PAGE_SETTINGS = 4

        /* Screen kept on during a job, dimmed after this inactivity. */
        private const val DIM_AFTER_MILLIS = 60_000L
        private const val DIM_BRIGHTNESS = 0.05f

        private val LOW_POWER_VALUES = listOf(1.0, 2.0, 5.0, 10.0)
        private val FRAMING_FEED_VALUES = listOf(1_000.0, 2_000.0, 3_000.0, 6_000.0)
        private val LOG_LEVEL_VALUES = listOf(1.0, 2.0, 3.0, 4.0, 5.0)

        /*
         * Sculpfun S10: CH340, VID 0x1A86 (6790) / PID 0x7523 (29987).
         */
        private const val SCULPFUN_VENDOR_ID = 6790
        private const val SCULPFUN_PRODUCT_ID = 29987
    }

    private val activityScope = MainScope()

    /* Views */
    private lateinit var connectButton: ImageView
    private lateinit var machineStateText: TextView
    private lateinit var positionText: TextView
    private lateinit var jobBadgeText: TextView
    private lateinit var jogPanel: JogPanelView
    private lateinit var jobPanelView: JobPanelView
    private lateinit var consolePanel: View
    private lateinit var settingsPanel: View
    private lateinit var lowPowerChips: ChoiceChipsView
    private lateinit var serverPanel: View
    private lateinit var zAxisSwitch: CompoundButton
    private lateinit var startOnBootSwitch: CompoundButton
    private lateinit var homeAppSwitch: CompoundButton
    private lateinit var bootHelpText: TextView
    private lateinit var framingFeedChips: ChoiceChipsView
    private lateinit var logLevelChips: ChoiceChipsView
    private lateinit var soundsSwitch: CompoundButton
    private lateinit var aboutText: TextView
    private lateinit var remoteSwitch: CompoundButton
    private lateinit var remoteModeChips: ChoiceChipsView
    private lateinit var remoteModeHelp: TextView
    private lateinit var remoteWebSocketSwitch: CompoundButton
    private lateinit var remoteRelaySwitch: CompoundButton
    private lateinit var remotePauseSwitch: CompoundButton
    private lateinit var remoteInfoText: TextView
    private lateinit var remoteBadgeText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var tabs: List<TextView>

    private lateinit var usbManager: UsbManager

    private var pendingUsbDevice: UsbDevice? = null
    private var machineService: MachineService? = null
    private var serviceBound = false
    private lateinit var machineServiceConnection: MachineServiceConnection

    private var currentPage = PAGE_JOG

    /* Optional Z axis (SETTINGS). */
    private var zAxisEnabled = false

    /* Screen policy: kept on during a job, dimmed when inactive. */
    private val screenHandler = Handler(Looper.getMainLooper())
    private var keepScreenOn = false
    private var dimmed = false
    private val dimRunnable = Runnable { setScreenDimmed(true) }
    private var lastMachineSnapshot: MachineSnapshot = MachineSnapshot.initial()

    /* Coroutines bound to the Activity */
    private var serviceObserverJob: Job? = null
    private var jobObserverJob: Job? = null
    private var jobTickerJob: Job? = null
    private var managerSettingsJob: Job? = null
    private var continuousJogJob: Job? = null
    private var logJob: Job? = null
    private var settingsJob: Job? = null
    private var framingStateJob: Job? = null
    private var remoteJob: Job? = null

    /* Suppresses listeners while settings are displayed. */
    private var displayingSettings = false

    /*
     * ----------------------------------------------------
     * USB PERMISSION RECEIVER
     * ----------------------------------------------------
     */

    private val usbPermissionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(context: Context, intent: Intent) {

                if (intent.action != ACTION_USB_PERMISSION) {
                    return
                }

                val device = getUsbDeviceFromIntent(intent) ?: pendingUsbDevice
                pendingUsbDevice = null

                /*
                 * Check the real permission too: some ROMs do not
                 * fill EXTRA_PERMISSION_GRANTED reliably.
                 */
                val granted =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                            (device != null && usbManager.hasPermission(device))

                if (!granted || device == null) {
                    appendLog("USB permission denied.")
                    return
                }

                appendLog("USB permission granted.")
                connectAuthorizedDevice(device)
            }
        }

    /*
     * ----------------------------------------------------
     * ACTIVITY LIFECYCLE
     * ----------------------------------------------------
     */

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        /*
         * Normal screen timeout when nothing runs; kept on (and dimmed
         * when inactive) during a job: see updateScreenPolicy().
         */

        setContentView(R.layout.activity_main)

        bindViews()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbPermissionReceiver()

        configureStatusBar()
        configureTabs()
        configureConsole()
        configureJogPanel()
        configureJobPanel()
        configureSettings()

        restoreUiState(savedInstanceState)

        renderMachine(MachineSnapshot.initial())

        initializeMachineService()

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        renderBootHelp()
    }

    override fun onPause() {
        // Safety: the LASER button never stays on in background.
        jogPanel.releaseLaser()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullScreen()
        }
    }

    /*
     * Immersive mode: system bars hidden, a swipe from the
     * edge shows them temporarily.
     */
    private fun enterFullScreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /*
     * ----------------------------------------------------
     * VIEWS
     * ----------------------------------------------------
     */

    private fun bindViews() {
        connectButton = findViewById(R.id.buttonConnect)
        machineStateText = findViewById(R.id.textMachineState)
        positionText = findViewById(R.id.textPosition)
        jobBadgeText = findViewById(R.id.textJobBadge)
        jogPanel = findViewById(R.id.jogPanel)
        jobPanelView = findViewById(R.id.jobPanelView)
        consolePanel = findViewById(R.id.consolePanel)
        settingsPanel = findViewById(R.id.settingsPanel)
        lowPowerChips = findViewById(R.id.chipsLowPower)
        serverPanel = findViewById(R.id.serverPanel)
        zAxisSwitch = findViewById(R.id.switchZAxis)
        startOnBootSwitch = findViewById(R.id.switchStartOnBoot)
        homeAppSwitch = findViewById(R.id.switchHomeApp)
        bootHelpText = findViewById(R.id.textBootHelp)
        framingFeedChips = findViewById(R.id.chipsFramingFeed)
        logLevelChips = findViewById(R.id.chipsLogLevel)
        soundsSwitch = findViewById(R.id.switchSounds)
        aboutText = findViewById(R.id.textAbout)
        remoteSwitch = findViewById(R.id.switchRemote)
        remoteModeChips = findViewById(R.id.chipsRemoteMode)
        remoteModeHelp = findViewById(R.id.textRemoteModeHelp)
        remoteWebSocketSwitch = findViewById(R.id.switchRemoteWebSocket)
        remoteRelaySwitch = findViewById(R.id.switchRemoteRelay)
        remotePauseSwitch = findViewById(R.id.switchRemotePause)
        remoteInfoText = findViewById(R.id.textRemoteInfo)
        remoteBadgeText = findViewById(R.id.textRemoteBadge)
        logText = findViewById(R.id.textLog)
        logScrollView = findViewById(R.id.logScrollView)
        commandInput = findViewById(R.id.editCommand)
        tabs = listOf(
            findViewById(R.id.tabJog),
            findViewById(R.id.tabJob),
            findViewById(R.id.tabServer),
            findViewById(R.id.tabConsole),
            findViewById(R.id.tabSettings)
        )
    }

    private fun configureStatusBar() {

        connectButton.setOnClickListener {
            if (lastMachineSnapshot.isConnected) {
                confirmDisconnect()
            } else {
                connectUsbSerial()
            }
        }

        /* Tap on ALARM: unlock ($X). */
        machineStateText.setOnClickListener {
            if (lastMachineSnapshot.machineState == MachineState.Alarm) {
                unlockMachine()
            }
        }

    }

    private fun configureTabs() {
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { showPage(index) }
        }
    }

    private fun showPage(page: Int) {
        currentPage = page
        jogPanel.visibility = if (page == PAGE_JOG) View.VISIBLE else View.GONE
        jobPanelView.visibility = if (page == PAGE_JOB) View.VISIBLE else View.GONE
        consolePanel.visibility = if (page == PAGE_CONSOLE) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.GONE
        serverPanel.visibility = if (page == PAGE_SERVER) View.VISIBLE else View.GONE
        if (page != PAGE_JOG) {
            jogPanel.releaseLaser()
        }
        tabs.forEachIndexed { index, tab -> tab.isSelected = index == page }

        if (page != PAGE_CONSOLE) {
            hideKeyboard()
        } else {
            scrollLogToBottom()
        }
    }

    private fun configureConsole() {

        findViewById<View>(R.id.buttonDetectUsb).setOnClickListener { detectUsbDevices() }
        findViewById<View>(R.id.buttonStatus).setOnClickListener { requestGrblStatus() }
        findViewById<View>(R.id.buttonSettings).setOnClickListener { requestGrblSettings() }
        findViewById<View>(R.id.buttonBuildInfo).setOnClickListener { requestGrblBuildInfo() }
        findViewById<View>(R.id.buttonUnlock).setOnClickListener { unlockMachine() }
        findViewById<View>(R.id.buttonReset).setOnClickListener { confirmSoftReset() }
        findViewById<View>(R.id.buttonClearLog).setOnClickListener { clearLog() }
        findViewById<View>(R.id.buttonSendCommand).setOnClickListener { sendConsoleCommand() }

        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendConsoleCommand()
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard() {
        val inputManager =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(commandInput.windowToken, 0)
    }

    /*
     * ----------------------------------------------------
     * MACHINE DISPLAY
     * ----------------------------------------------------
     */

    private fun renderMachine(snapshot: MachineSnapshot) {

        lastMachineSnapshot = snapshot
        val state = snapshot.machineState

        val (label, colorRes) = when (state) {
            MachineState.Disconnected -> "OFFLINE" to R.color.ld_state_offline
            MachineState.Connecting -> "CONNECT…" to R.color.ld_state_hold
            MachineState.Idle -> "IDLE" to R.color.ld_state_idle
            MachineState.Running -> "RUN" to R.color.ld_state_run
            MachineState.Jog -> "JOG" to R.color.ld_state_run
            MachineState.Hold -> "HOLD" to R.color.ld_state_hold
            MachineState.Alarm -> "ALARM" to R.color.ld_state_alarm
            is MachineState.Error -> "ERROR" to R.color.ld_state_alarm
        }
        machineStateText.text = label
        machineStateText.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

        val connectColor = when {
            snapshot.isConnected -> R.color.ld_state_idle
            state == MachineState.Connecting -> R.color.ld_state_hold
            else -> R.color.ld_text_secondary
        }
        connectButton.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, connectColor))

        val offline = state == MachineState.Disconnected || state == MachineState.Connecting
        val offset = machineService?.workCoordinateOffset ?: MachinePosition.ZERO
        /* Work coordinates (origin set with SET ZERO). */
        positionText.text = when {
            offline && zAxisEnabled -> "X ---.--- Y ---.--- Z --.---"
            offline -> "X  ---.---  Y  ---.---"
            zAxisEnabled -> String.format(
                Locale.US,
                "X%8.3f Y%8.3f Z%7.3f",
                snapshot.position.x - offset.x,
                snapshot.position.y - offset.y,
                snapshot.position.z - offset.z
            )
            else -> String.format(
                Locale.US,
                "X %8.3f  Y %8.3f",
                snapshot.position.x - offset.x,
                snapshot.position.y - offset.y
            )
        }

        jogPanel.updateMachineState(state)
        jobPanelView.updateMachineState(state)

        if (offline) {
            jobPanelView.updateHeadPosition(null)
        } else {
            jobPanelView.updateHeadPosition(
                MachinePosition(
                    x = snapshot.position.x - offset.x,
                    y = snapshot.position.y - offset.y,
                    z = snapshot.position.z - offset.z
                )
            )
        }
    }

    /*
     * ----------------------------------------------------
     * MACHINE SERVICE
     * ----------------------------------------------------
     */

    private fun initializeMachineService() {

        machineServiceConnection =
            MachineServiceConnection(
                onConnected = { service ->
                    machineService = service
                    serviceBound = true
                    observeMachineService(service)
                },
                onDisconnected = {
                    serviceBound = false
                    machineService = null
                    cancelBackendObservers()
                    renderMachine(MachineSnapshot.initial())
                    appendLog("Machine service disconnected.")
                }
            )

        val serviceIntent = Intent(this, MachineService::class.java)

        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, machineServiceConnection, BIND_AUTO_CREATE)
    }

    private fun observeMachineService(service: MachineService) {

        serviceObserverJob?.cancel()
        serviceObserverJob =
            activityScope.launch {
                service.snapshot.collect { snapshot ->
                    renderMachine(snapshot)
                }
            }

        observeJob(service)
        observeServiceLog(service)
        observeSettings(service)
        observeRemote(service)

        /*
         * After a rotation the backend already exists:
         * attach to it immediately.
         */
        service.manager?.let { manager ->
            observeMachineManager(manager)
        }
    }

    private fun observeMachineManager(manager: MachineManager) {

        managerSettingsJob?.cancel()
        managerSettingsJob =
            activityScope.launch {
                manager.settings.collect { settings ->
                    jobPanelView.setMachineTravel(
                        settings[130]?.toDoubleOrNull(),
                        settings[131]?.toDoubleOrNull()
                    )
                }
            }

        framingStateJob?.cancel()
        framingStateJob =
            activityScope.launch {
                manager.framingActive.collect { active ->
                    jobPanelView.setFramingActive(active)
                    updateScreenPolicy(
                        active || machineService?.jobSnapshot?.value?.state?.isActive == true
                    )
                }
            }
    }

    private fun cancelBackendObservers() {
        serviceObserverJob?.cancel()
        serviceObserverJob = null
        managerSettingsJob?.cancel()
        managerSettingsJob = null
        jobObserverJob?.cancel()
        jobObserverJob = null
        jobTickerJob?.cancel()
        jobTickerJob = null
        logJob?.cancel()
        logJob = null
        settingsJob?.cancel()
        settingsJob = null
        framingStateJob?.cancel()
        framingStateJob = null
        remoteJob?.cancel()
        remoteJob = null
    }

    private fun requireMachineManager(): MachineManager? {
        val manager = machineService?.manager
        if (manager == null) {
            appendLog("Machine is not connected.")
        }
        return manager
    }

    /*
     * ----------------------------------------------------
     * USB: DETECTION, PERMISSION, CONNECTION
     * ----------------------------------------------------
     */

    private fun registerUsbPermissionReceiver() {
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    /*
     * The Activity declares USB_DEVICE_ATTACHED (device_filter.xml).
     * When the user accepts "use by default" in the system dialog,
     * Android keeps the USB permission for this device: no more
     * permission request at each launch.
     *
     * No automatic connection: the user still presses Connect.
     */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return
        }
        val device = getUsbDeviceFromIntent(intent) ?: return
        appendLog(
            "USB device attached: ${device.vendorId}:${device.productId} " +
                    if (usbManager.hasPermission(device)) "(permission granted)"
                    else "(no permission)"
        )
    }

    private fun findSupportedUsbDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.firstOrNull {
            it.vendorId == SCULPFUN_VENDOR_ID && it.productId == SCULPFUN_PRODUCT_ID
        } ?: devices.firstOrNull()
    }

    private fun detectUsbDevices() {

        val devices = usbManager.deviceList.values.toList()

        if (devices.isEmpty()) {
            appendLog("No USB device detected.")
            return
        }

        appendLog("${devices.size} USB device(s) detected")
        devices.forEachIndexed { index, device ->
            val manufacturer = runCatching { device.manufacturerName }.getOrNull() ?: "Unknown"
            val product = runCatching { device.productName }.getOrNull() ?: "Unknown"
            val permission = if (usbManager.hasPermission(device)) "granted" else "not granted"
            appendLog(
                "  ${index + 1}: ${device.deviceName} " +
                        "VID=${device.vendorId} PID=${device.productId} " +
                        "$manufacturer / $product, permission $permission"
            )
        }
    }

    private fun connectUsbSerial() {

        val service = machineService
        if (service == null) {
            appendLog("Machine service is not ready.")
            return
        }

        if (service.isMachineConnected) {
            appendLog("Machine is already connected.")
            return
        }

        val device = findSupportedUsbDevice()
        if (device == null) {
            appendLog("No compatible USB device detected.")
            return
        }

        if (usbManager.hasPermission(device)) {
            connectAuthorizedDevice(device)
            return
        }

        pendingUsbDevice = device

        /*
         * The PendingIntent MUST be mutable: the system adds
         * EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED to it.
         * An immutable one arrives without extras, which made
         * the first Connect fail.
         */
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

        val permissionIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                flags
            )

        appendLog("Requesting USB permission...")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectAuthorizedDevice(device: UsbDevice) {

        val service = machineService
        if (service == null) {
            appendLog("Machine service is not available.")
            return
        }

        activityScope.launch {

            appendLog("Connecting...")

            /* Short stabilization delay for CH34x. */
            delay(200.milliseconds)

            service.connect(device)
                .onSuccess {
                    service.manager?.let { observeMachineManager(it) }
                    if (service.jobSnapshot.value.isRecoverable) {
                        // Let the first status reports arrive (position, WCO).
                        delay(1_500.milliseconds)
                        showPage(PAGE_JOB)
                        showRecoveryDialog()
                    }
                }
                .onFailure { /* Logged by the service. */ }
        }
    }

    private fun confirmDisconnect() {

        val service = machineService ?: return
        val jobActive = service.jobSnapshot.value.state.isActive

        AlertDialog.Builder(this)
            .setTitle("Disconnect")
            .setMessage(
                if (jobActive) {
                    "A job is running. Disconnecting stops streaming " +
                            "and the job will NOT be resumed."
                } else {
                    "Close the USB connection?"
                }
            )
            .setPositiveButton("Disconnect") { _, _ ->
                activityScope.launch {
                    service.disconnect()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /*
     * ----------------------------------------------------
     * CONSOLE COMMANDS
     * ----------------------------------------------------
     */

    private fun requestGrblStatus() {
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching {
                appendLog("TX: ?")
                manager.requestStatus()
            }.onFailure { appendLog("Status error: ${describe(it)}") }
        }
    }

    private fun requestGrblSettings() {
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching {
                appendLog("TX: \$\$")
                appendLog("Settings result: ${manager.requestSettings()}")
            }.onFailure { appendLog("Settings error: ${describe(it)}") }
        }
    }

    private fun requestGrblBuildInfo() {
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching {
                appendLog("TX: \$I")
                appendLog("Identification result: ${manager.requestBuildInfo()}")
            }.onFailure { appendLog("Identification error: ${describe(it)}") }
        }
    }

    private fun unlockMachine() {
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching {
                appendLog("TX: \$X")
                appendLog("Unlock result: ${manager.unlock()}")
            }.onFailure { appendLog("Unlock error: ${describe(it)}") }
        }
    }

    private fun confirmSoftReset() {
        val service = machineService ?: return
        val manager = requireMachineManager() ?: return

        AlertDialog.Builder(this)
            .setTitle("Soft reset (Ctrl-X)")
            .setMessage("Immediate stop of GRBL. A running job is aborted.")
            .setPositiveButton("Reset") { _, _ ->
                activityScope.launch {
                    runCatching {
                        if (service.jobSnapshot.value.state.isActive) {
                            service.jobManager.stop(immediate = true)
                        } else {
                            manager.softReset()
                        }
                        appendLog("TX: Ctrl-X (soft reset)")
                    }.onFailure { appendLog("Reset error: ${describe(it)}") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendConsoleCommand() {
        val command = commandInput.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return
        val manager = requireMachineManager() ?: return

        commandInput.setText("")
        activityScope.launch {
            runCatching {
                appendLog("TX: $command")
                val result = manager.sendCommand(command)
                if (result != CommandResult.Acknowledged) {
                    appendLog("Result: $result")
                } else {
                    appendLog("ok")
                }
            }.onFailure { appendLog("Command error: ${describe(it)}") }
        }
    }

    /*
     * ----------------------------------------------------
     * JOG
     * ----------------------------------------------------
     */

    private fun configureJogPanel() {
        jogPanel.onJogClick = { direction, step, speed ->
            executeDirectionalJog(direction, step, speed)
        }
        jogPanel.onContinuousJogStart = { direction, step, speed ->
            startContinuousJog(direction, step, speed)
        }
        jogPanel.onContinuousJogStop = { stopContinuousJog() }
        /* Center button: X0 Y0 at the selected speed, step ignored. */
        jogPanel.onHomeRequested = { speed -> executeJogHome(speed) }
        jogPanel.onCancelRequested = { cancelJog() }
        jogPanel.onSetZeroRequested = { confirmSetZero() }
        jogPanel.onLaserPointer = { on -> machineService?.laserPointer(on) }
    }

    private fun confirmSetZero() {
        val service = machineService ?: return
        AlertDialog.Builder(this)
            .setTitle("Set work zero")
            .setMessage(
                "The current head position becomes the work origin " +
                        (if (zAxisEnabled) "X0 Y0 Z0" else "X0 Y0") +
                        " (G10 L20 P1, stored by GRBL)."
            )
            .setPositiveButton("Set zero") { _, _ ->
                activityScope.launch { service.setWorkZero(includeZ = zAxisEnabled) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createJogRequest(
        direction: JogDirection,
        step: Double,
        speed: Double
    ): JogRequest {
        return when (direction) {
            JogDirection.NORTH -> JogRequest(y = step, feedRate = speed)
            JogDirection.NORTH_EAST -> JogRequest(x = step, y = step, feedRate = speed)
            JogDirection.EAST -> JogRequest(x = step, feedRate = speed)
            JogDirection.SOUTH_EAST -> JogRequest(x = step, y = -step, feedRate = speed)
            JogDirection.SOUTH -> JogRequest(y = -step, feedRate = speed)
            JogDirection.SOUTH_WEST -> JogRequest(x = -step, y = -step, feedRate = speed)
            JogDirection.WEST -> JogRequest(x = -step, feedRate = speed)
            JogDirection.NORTH_WEST -> JogRequest(x = -step, y = step, feedRate = speed)
            JogDirection.Z_UP -> JogRequest(z = step, feedRate = zFeedRate(speed))
            JogDirection.Z_DOWN -> JogRequest(z = -step, feedRate = zFeedRate(speed))
        }
    }

    /* Z is usually much slower: limited to $112 when known. */
    private fun zFeedRate(speed: Double): Double {
        val maxZ = machineService?.manager?.settings?.value?.get(112)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: return speed
        return minOf(speed, maxZ)
    }

    private fun executeDirectionalJog(direction: JogDirection, step: Double, speed: Double) {
        val manager = requireMachineManager() ?: return
        val request = createJogRequest(direction, step, speed)
        activityScope.launch {
            runCatching {
                logOperationResult("Jog $direction", manager.executeJog(request))
            }.onFailure { appendLog("Jog failed: ${describe(it)}") }
        }
    }

    private fun startContinuousJog(direction: JogDirection, step: Double, speed: Double) {
        val manager = requireMachineManager() ?: return

        continuousJogJob?.cancel()
        appendLog(
            "Continuous jog: $direction step=${formatNumber(step)} mm " +
                    "speed=${formatNumber(speed)} mm/min"
        )

        continuousJogJob =
            activityScope.launch {
                while (isActive) {
                    val result =
                        manager.startContinuousJog(createJogRequest(direction, step, speed))
                    if (result != CommandResult.Acknowledged) {
                        appendLog("Continuous jog stopped: $result")
                        break
                    }
                    delay(CONTINUOUS_JOG_INTERVAL_MS.milliseconds)
                }
            }
    }

    private fun stopContinuousJog() {
        continuousJogJob?.cancel()
        continuousJogJob = null
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching { manager.cancelJog() }
                .onFailure { appendLog("Jog stop error: ${describe(it)}") }
        }
    }

    private fun executeJogHome(speed: Double) {
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching {
                logOperationResult("X0 Y0", manager.goToZero(feedRate = speed))
            }.onFailure { appendLog("X0 Y0 failed: ${describe(it)}") }
        }
    }

    private fun cancelJog() {
        continuousJogJob?.cancel()
        continuousJogJob = null
        val manager = requireMachineManager() ?: return
        activityScope.launch {
            runCatching { manager.cancelJog() }
                .onFailure { appendLog("Jog cancel error: ${describe(it)}") }
        }
    }

    private fun logOperationResult(operationName: String, result: MachineOperationResult) {
        when (result) {
            is MachineOperationResult.Completed -> appendLog(
                "$operationName completed: X=${formatCoordinate(result.finalPosition.x)}, " +
                        "Y=${formatCoordinate(result.finalPosition.y)}"
            )
            is MachineOperationResult.CommandRejected ->
                appendLog("$operationName rejected: ${result.result}")
            is MachineOperationResult.Interrupted ->
                appendLog("$operationName interrupted: ${formatMachineState(result.machineState)}")
            is MachineOperationResult.Timeout -> appendLog(
                "$operationName timeout after ${result.timeoutMillis / 1_000L}s. " +
                        "Last position: X=${formatCoordinate(result.lastPosition.x)}, " +
                        "Y=${formatCoordinate(result.lastPosition.y)}"
            )
            is MachineOperationResult.Failed ->
                appendLog("$operationName failed: ${result.message}")
        }
    }

    /*
     * ----------------------------------------------------
     * JOB
     * ----------------------------------------------------
     */

    private fun configureJobPanel() {
        jobPanelView.onOpenRequested = { showOpenDialog() }
        jobPanelView.onFrameRequested = { startFraming() }
        jobPanelView.onCenterRequested = {
            machineService?.moveToJobCenter()?.let { appendLog("Move to center refused: $it") }
        }
        jobPanelView.onStartRequested = { laserOff ->
            when {
                machineService?.jobSnapshot?.value?.isRecoverable == true -> showRecoveryDialog()
                laserOff -> startJob(laserOff = true)
                else -> confirmLaserOn { startJob(laserOff = false) }
            }
        }
        jobPanelView.onPauseRequested = {
            runJobCommand("Pause") { it.jobManager.pause() }
        }
        jobPanelView.onResumeRequested = {
            runJobCommand("Resume") { it.jobManager.resume() }
        }
        jobPanelView.onStopRequested = {
            val service = machineService
            if (service != null && service.isFraming) {
                service.stopFraming()
            } else {
                runJobCommand("Stop") { it.jobManager.stop() }
            }
        }
    }

    private fun observeJob(service: MachineService) {

        jobObserverJob?.cancel()
        jobObserverJob =
            activityScope.launch {
                service.jobSnapshot.collect { job ->
                    renderJob(job)
                }
            }

        /*
         * Elapsed / remaining time also move while no line is
         * acknowledged (pause, long moves).
         */
        jobTickerJob?.cancel()
        jobTickerJob =
            activityScope.launch {
                while (isActive) {
                    delay(JOB_DISPLAY_REFRESH_MS.milliseconds)
                    val job = service.jobSnapshot.value
                    if (job.state.isActive) {
                        renderJob(job)
                    }
                }
            }
    }

    private fun renderJob(job: JobSnapshot) {
        updateScreenPolicy(job.state.isActive || machineService?.isFraming == true)
        jobPanelView.updateJob(snapshot = job, nowMillis = System.currentTimeMillis())

        if (job.state.isActive) {
            jobBadgeText.visibility = View.VISIBLE
            jobBadgeText.text = String.format(Locale.US, "%.0f%%", job.percent)
        } else {
            jobBadgeText.visibility = View.GONE
        }
    }

    private fun openGCodeFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            /* .nc / .gcode / .tap have no reliable MIME type. */
            type = "*/*"
        }
        runCatching {
            startActivityForResult(intent, REQUEST_OPEN_GCODE)
        }.onFailure {
            appendLog("Cannot open the file picker: ${it.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_OPEN_GCODE || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        val service = machineService
        if (service == null) {
            appendLog("Machine service is not ready.")
            return
        }

        showPage(PAGE_JOB)

        activityScope.launch {
            // Result and warnings are logged by the service.
            service.importAndLoad(uri)
        }
    }

    private fun confirmLaserOn(onConfirmed: () -> Unit) {

        /*
         * $32=0: GRBL is not in laser mode, the laser is not
         * switched off automatically during a feed hold.
         */
        val laserModeOff =
            machineService?.manager?.settings?.value?.get(32)?.trim() == "0"

        val warning =
            if (laserModeOff) "\n\nWARNING: GRBL laser mode is OFF (\$32=0)." else ""

        AlertDialog.Builder(this)
            .setTitle("Laser ON")
            .setMessage(
                "The laser will fire.\n\n" +
                        "Eye protection on, material in place, " +
                        "air assist and fire safety ready?" + warning
            )
            .setPositiveButton("Start with laser") { _, _ -> onConfirmed() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startJob(laserOff: Boolean) {
        val service = machineService
        if (service == null) {
            appendLog("Machine service is not ready.")
            return
        }
        activityScope.launch {
            when (
                val result = service.jobManager.start(
                    laserOff = laserOff,
                    airAssist = jobPanelView.isAirAssistChecked
                )
            ) {
                JobStartResult.Started ->
                    appendLog(if (laserOff) "Job started (LASER OFF)." else "Job started (LASER ON).")
                is JobStartResult.Rejected ->
                    appendLog("Job not started: ${result.reason}")
            }
        }
    }

    private fun runJobCommand(
        name: String,
        command: suspend (MachineService) -> Boolean
    ) {
        val service = machineService
        if (service == null) {
            appendLog("Machine service is not ready.")
            return
        }
        activityScope.launch {
            runCatching { command(service) }
                .onSuccess { accepted ->
                    if (!accepted) {
                        appendLog("$name refused in state ${service.jobSnapshot.value.state.name}")
                    }
                }
                .onFailure { appendLog("$name error: ${describe(it)}") }
        }
    }

    private fun startFraming() {
        val service = machineService ?: return
        val refusal = service.startFraming()
        if (refusal != null) {
            appendLog("Framing refused: $refusal")
        }
    }

    /*
     * ----------------------------------------------------
     * RECOVERY OF AN INTERRUPTED JOB
     * ----------------------------------------------------
     */

    private fun showRecoveryDialog() {
        val service = machineService ?: return
        activityScope.launch {
            val assessment = service.jobManager.assessRecovery()
            if (assessment == null) {
                appendLog("Connect the machine to recover the interrupted job.")
                return@launch
            }
            val laserOff = jobPanelView.isLaserOffChecked

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Interrupted job")
                .setMessage(recoveryMessage(assessment, laserOff))
                .setPositiveButton("Resume") { _, _ ->
                    recoverJob(RecoveryMode.RESUME, laserOff)
                }
                .setNeutralButton("Restart") { _, _ ->
                    recoverJob(RecoveryMode.RESTART, laserOff)
                }
                .setNegativeButton("Cancel job") { _, _ ->
                    if (service.jobManager.cancelRecovery()) {
                        appendLog("Interrupted job cancelled.")
                    }
                }
                .show()
        }
    }

    private fun recoveryMessage(assessment: RecoveryAssessment, laserOff: Boolean): String {
        val percent =
            if (assessment.totalCommands > 0) {
                assessment.commandsDone * 100.0 / assessment.totalCommands
            } else {
                0.0
            }
        return buildString {
            append(
                String.format(
                    Locale.US,
                    "Stopped after line %d (%.1f%%, %d/%d commands).\n\n",
                    assessment.lineNumber,
                    percent,
                    assessment.commandsDone,
                    assessment.totalCommands
                )
            )
            val expected =
                if (assessment.canResynchronize) {
                    String.format(
                        Locale.US,
                        "X %.3f Y %.3f",
                        assessment.expectedX,
                        assessment.expectedY
                    )
                } else {
                    "unknown"
                }
            if (assessment.positionTrusted) {
                append("Position verified: $expected.\n")
            } else {
                append("Position NOT verified: GRBL lost its position (reset).\n")
                append("The current head position will become $expected ")
                append("(last executed point) with G10 L20.\n")
                append("Do not move the head before choosing.\n")
            }
            if (assessment.positionUncertain) {
                append("\nWARNING: the program uses G10/G28/G92: the expected point may be wrong.\n")
            }
            append("\nResume: continue after line ${assessment.lineNumber}.")
            append("\nRestart: run again from the first line, same origin.")
            append("\nCancel job: keep the program, forget the progress.")
            append(if (laserOff) "\n\nLaser OFF (dry run)." else "\n\nLASER ON.")
        }
    }

    private fun recoverJob(mode: RecoveryMode, laserOff: Boolean) {
        val service = machineService ?: return
        val run = {
            activityScope.launch {
                when (
                    val result = service.jobManager.recover(
                        mode,
                        laserOff,
                        airAssist = jobPanelView.isAirAssistChecked
                    )
                ) {
                    JobStartResult.Started -> appendLog("Recovery started: $mode.")
                    is JobStartResult.Rejected -> appendLog("Recovery refused: ${result.reason}")
                }
            }
            Unit
        }
        if (laserOff) run() else confirmLaserOn(run)
    }

    /*
     * ----------------------------------------------------
     * LIBRARY AND HISTORY
     * ----------------------------------------------------
     */

    private fun showOpenDialog() {
        val service = machineService
        if (service == null) {
            openGCodeFile()
            return
        }
        val files = service.libraryFiles()
        val labels = arrayOf("Import from phone…") + files.map { describeFile(it) }

        AlertDialog.Builder(this)
            .setTitle("Open G-code")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    openGCodeFile()
                } else {
                    val file = files[which - 1]
                    activityScope.launch { service.loadLibraryFile(file) }
                    showPage(PAGE_JOB)
                }
            }
            .setNeutralButton("Delete…") { _, _ -> showDeleteDialog(files) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(files: List<File>) {
        val service = machineService ?: return
        if (files.isEmpty()) {
            appendLog("Library is empty.")
            return
        }
        val checked = BooleanArray(files.size)
        AlertDialog.Builder(this)
            .setTitle("Delete from library")
            .setMultiChoiceItems(files.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Delete") { _, _ ->
                service.deleteLibraryFiles(files.filterIndexed { index, _ -> checked[index] })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun describeFile(file: File): String {
        val size = file.length()
        val sizeText = when {
            size < 1_024 -> "$size B"
            size < 1_048_576 -> String.format(Locale.US, "%.1f KB", size / 1_024.0)
            else -> String.format(Locale.US, "%.1f MB", size / 1_048_576.0)
        }
        val date = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(file.lastModified()))
        return "${file.name}\n   $sizeText · $date"
    }

    private fun showHistoryDialog() {
        val service = machineService ?: return
        activityScope.launch {
            val entries = service.loadHistory()
            val labels =
                if (entries.isEmpty()) arrayOf("No job yet.")
                else entries.map { describeHistory(it) }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Job history")
                .setItems(labels, null)
                .setNeutralButton("Clear") { _, _ ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Clear history?")
                        .setPositiveButton("Clear") { _, _ ->
                            activityScope.launch { service.clearHistory() }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun describeHistory(entry: JobHistoryEntry): String {
        val date = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(entry.startedAt))
        val duration = formatDuration(entry.endedAt - entry.startedAt)
        val size =
            if (entry.width != null && entry.height != null) {
                String.format(Locale.US, " · %.0f×%.0f mm", entry.width, entry.height)
            } else {
                ""
            }
        val laser = if (entry.laserOff) " · laser off" else ""
        return "$date  ${entry.name}\n   " +
                String.format(Locale.US, "%s %.0f%% · %s", entry.state, entry.percent, duration) +
                size + laser
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1_000L).coerceAtLeast(0L)
        return if (seconds >= 3_600L) {
            String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600L, (seconds % 3_600L) / 60L, seconds % 60L)
        } else {
            String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
        }
    }

    /*
     * ----------------------------------------------------
     * SCREEN: KEPT ON DURING A JOB, DIMMED WHEN INACTIVE
     * ----------------------------------------------------
     */

    private fun updateScreenPolicy(jobRunning: Boolean) {
        if (jobRunning == keepScreenOn) return
        keepScreenOn = jobRunning
        if (jobRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleDim()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenHandler.removeCallbacks(dimRunnable)
            setScreenDimmed(false)
        }
    }

    private fun scheduleDim() {
        screenHandler.removeCallbacks(dimRunnable)
        if (keepScreenOn) {
            screenHandler.postDelayed(dimRunnable, DIM_AFTER_MILLIS)
        }
    }

    private fun setScreenDimmed(dim: Boolean) {
        if (dim == dimmed) return
        dimmed = dim
        val attributes = window.attributes
        attributes.screenBrightness =
            if (dim) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    /* Any touch restores the brightness and restarts the delay. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        setScreenDimmed(false)
        scheduleDim()
    }

    /*
     * ----------------------------------------------------
     * KIOSK: START ON BOOT, HOME SCREEN
     * ----------------------------------------------------
     */

    private val homeAlias: ComponentName
        get() = ComponentName(this, "com.laserdroid.HomeAlias")

    private fun isHomeAppEnabled(): Boolean =
        packageManager.getComponentEnabledSetting(homeAlias) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setHomeAppEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            homeAlias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        if (enabled) {
            // Lets the user choose LaserDroid as the default home app.
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
        }
    }

    private fun renderBootHelp() {
        bootHelpText.text = when {
            isHomeAppEnabled() ->
                "Home screen mode: choose LaserDroid as default home app. " +
                        "Android then opens it at every boot."
            Settings.canDrawOverlays(this) ->
                "Start on boot ready (display over other apps granted)."
            else ->
                "Start on boot opens the screen only with \"Display over other apps\" " +
                        "(asked when enabled) or in home screen mode. The service always starts."
        }
    }

    /*
     * ----------------------------------------------------
     * SETTINGS
     * ----------------------------------------------------
     */

    private fun configureSettings() {
        val percentLabel: (Double) -> String = { value ->
            if (value == 0.0) "OFF" else formatNumber(value) + "%"
        }
        lowPowerChips.setChoices(LOW_POWER_VALUES, 2.0, percentLabel)
        framingFeedChips.setChoices(FRAMING_FEED_VALUES, 3_000.0) { formatNumber(it) }
        logLevelChips.setChoices(LOG_LEVEL_VALUES, LogLevel.INFO.ordinal.toDouble()) { value ->
            when (value.toInt()) {
                1 -> "ERR"
                2 -> "WARN"
                3 -> "INFO"
                4 -> "DEBUG"
                else -> "TRACE"
            }
        }

        lowPowerChips.onValueSelected = { value ->
            machineService?.updateSettings { it.copy(lowPowerPercent = value) }
        }
        framingFeedChips.onValueSelected = { value ->
            machineService?.updateSettings { it.copy(framingFeedRate = value) }
        }
        zAxisSwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(zAxisEnabled = checked) }
            }
        }
        startOnBootSwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(startOnBoot = checked) }
                if (checked && !Settings.canDrawOverlays(this) && !isHomeAppEnabled()) {
                    requestOverlayPermission()
                }
            }
        }
        homeAppSwitch.isChecked = isHomeAppEnabled()
        homeAppSwitch.setOnCheckedChangeListener { _, checked ->
            setHomeAppEnabled(checked)
            renderBootHelp()
        }
        renderBootHelp()
        logLevelChips.onValueSelected = { value ->
            val level = LogLevel.values()[value.toInt().coerceIn(0, LogLevel.values().lastIndex)]
            machineService?.updateSettings { it.copy(logLevel = level) }
        }
        soundsSwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(soundsEnabled = checked) }
            }
        }
        findViewById<View>(R.id.buttonTestSound).setOnClickListener {
            machineService?.playTestSound()
        }
        remoteModeChips.setChoices(listOf(0.0, 1.0), 0.0) { value ->
            if (value == 0.0) "RECEIVE" else "BRIDGE"
        }
        remoteModeChips.onValueSelected = { value ->
            val mode = if (value == 0.0) RemoteMode.RECEPTION else RemoteMode.BRIDGE
            machineService?.updateSettings { it.copy(remoteMode = mode) }
        }
        remoteSwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(remoteEnabled = checked) }
            }
        }
        remoteWebSocketSwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(remoteWebSocket = checked) }
            }
        }
        remoteRelaySwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(remoteRelay = checked) }
            }
        }
        remotePauseSwitch.setOnCheckedChangeListener { _, checked ->
            if (!displayingSettings) {
                machineService?.updateSettings { it.copy(remotePauseOnLoss = checked) }
            }
        }
        findViewById<View>(R.id.buttonRemoteDisconnect).setOnClickListener {
            machineService?.disconnectRemoteClient()
        }
        remoteBadgeText.setOnClickListener { showPage(PAGE_SERVER) }

        findViewById<View>(R.id.buttonHistory).setOnClickListener { showHistoryDialog() }
        findViewById<View>(R.id.buttonLibrary).setOnClickListener { showOpenDialog() }
    }

    private fun observeSettings(service: MachineService) {
        settingsJob?.cancel()
        settingsJob =
            activityScope.launch {
                service.settings.collect { settings -> renderSettings(service, settings) }
            }
    }

    private fun renderSettings(service: MachineService, settings: AppSettings) {
        displayingSettings = true
        lowPowerChips.selectValue(settings.lowPowerPercent)
        zAxisSwitch.isChecked = settings.zAxisEnabled
        startOnBootSwitch.isChecked = settings.startOnBoot
        framingFeedChips.selectValue(settings.framingFeedRate)
        logLevelChips.selectValue(settings.logLevel.ordinal.toDouble())
        soundsSwitch.isChecked = settings.soundsEnabled
        remoteSwitch.isChecked = settings.remoteEnabled
        remoteModeChips.selectValue(if (settings.remoteMode == RemoteMode.RECEPTION) 0.0 else 1.0)
        remoteWebSocketSwitch.isChecked = settings.remoteWebSocket
        remoteRelaySwitch.isChecked = settings.remoteRelay
        remotePauseSwitch.isChecked = settings.remotePauseOnLoss
        displayingSettings = false

        remoteModeHelp.text =
            if (settings.remoteMode == RemoteMode.RECEPTION) {
                "RECEIVE: LaserGRBL sends the program, LaserDroid stores it (JOB, READY). " +
                        "You start and follow the job on the phone."
            } else {
                "BRIDGE: LaserGRBL drives the machine through the phone. " +
                        "Phone controls are locked while the PC is connected."
            }
        remoteRelaySwitch.isEnabled = settings.remoteMode == RemoteMode.RECEPTION
        remotePauseSwitch.isEnabled = settings.remoteMode == RemoteMode.BRIDGE

        jogPanel.setLaserPointerPercent(settings.lowPowerPercent)
        if (zAxisEnabled != settings.zAxisEnabled) {
            zAxisEnabled = settings.zAxisEnabled
            jogPanel.setZAxisEnabled(zAxisEnabled)
            renderMachine(lastMachineSnapshot)
        }
        aboutText.text = "Log file: ${service.logBook.currentFile.absolutePath}\n" +
                "Library: ${File(filesDir, "library").absolutePath}"
    }

    /*
     * ----------------------------------------------------
     * LASERGRBL OVER WI-FI
     * ----------------------------------------------------
     */

    private var programsReceivedSeen = -1

    private fun observeRemote(service: MachineService) {
        remoteJob?.cancel()
        remoteJob =
            activityScope.launch {
                service.remoteStatus.collect { status ->
                    renderRemote(service, status)
                    // New program from LaserGRBL: show it.
                    if (programsReceivedSeen >= 0 && status.programsReceived > programsReceivedSeen) {
                        showPage(PAGE_JOB)
                    }
                    programsReceivedSeen = status.programsReceived
                }
            }
    }

    private fun renderRemote(service: MachineService, status: RemoteStatus) {
        if (!status.enabled) {
            remoteBadgeText.visibility = View.GONE
            remoteInfoText.text = "Server off."
            return
        }

        val (label, color) = when {
            status.receiving -> "RX ${status.linesReceived}" to R.color.ld_accent
            status.clientConnected -> (if (status.mode == RemoteMode.BRIDGE) "PC BRIDGE" else "PC") to
                    R.color.ld_state_idle
            else -> "PC" to R.color.ld_state_offline
        }
        remoteBadgeText.visibility = View.VISIBLE
        remoteBadgeText.text = label
        remoteBadgeText.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))

        val addresses = service.localIpAddresses().ifEmpty { listOf("(no Wi-Fi)") }
        remoteInfoText.text = buildString {
            append("In LaserGRBL, connection type:\n")
            addresses.forEach { address ->
                status.telnetPort?.let { append("  Telnet          $address:$it\n") }
                status.webSocketPort?.let { append("  LaserWebESP8266 ws://$address:$it/\n") }
            }
            status.error?.let { append("Error: $it\n") }
            append("\n")
            if (status.clientConnected) {
                append("PC: ${status.clientAddress} (${status.clientProtocol})\n")
                append("Received: ${status.bytesReceived / 1024} KB")
                if (status.receiving) append(", ${status.linesReceived} lines in progress")
            } else {
                append("Waiting for LaserGRBL...")
            }
            if (status.programsReceived > 0) {
                append("\nPrograms received: ${status.programsReceived}")
            }
        }
    }

    /*
     * ----------------------------------------------------
     * LOG AND UI STATE
     * ----------------------------------------------------
     */

    private fun restoreUiState(savedInstanceState: Bundle?) {

        if (savedInstanceState == null) {
            setLog("LaserDroid ready.")
            showPage(PAGE_JOG)
            return
        }

        jogPanel.setSelectedSpeed(savedInstanceState.getDouble(SAVED_JOG_SPEED, 1_000.0))
        jogPanel.setSelectedStep(savedInstanceState.getDouble(SAVED_JOG_STEP, 1.0))
        jobPanelView.isLaserOffChecked = savedInstanceState.getBoolean(SAVED_LASER_OFF, true)
        showPage(savedInstanceState.getInt(SAVED_PAGE, PAGE_JOG))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putDouble(SAVED_JOG_SPEED, jogPanel.getSelectedSpeed())
        outState.putDouble(SAVED_JOG_STEP, jogPanel.getSelectedStep())
        outState.putInt(SAVED_PAGE, currentPage)
        outState.putBoolean(SAVED_LASER_OFF, jobPanelView.isLaserOffChecked)
        super.onSaveInstanceState(outState)
    }

    private fun setLog(message: String) {
        runOnUiThread {
            logText.text = message.takeLast(MAX_LOG_CHARACTERS)
            scrollLogToBottom()
        }
    }

    /*
     * The log lives in the service (LogBook): Activity messages go
     * there and come back through observeServiceLog().
     */
    private fun appendLog(message: String) {
        val service = machineService
        if (service != null) {
            service.logBook.info(message)
        } else {
            displayLogLine(message)
        }
    }

    private fun observeServiceLog(service: MachineService) {
        logJob?.cancel()
        logJob =
            activityScope.launch {
                setLog(service.logBook.snapshot().joinToString("\n"))
                service.logBook.entries.collect { line -> displayLogLine(line) }
            }
    }

    private fun displayLogLine(message: String) {
        runOnUiThread {
            val editable = logText.editableText
            if (editable != null) {
                appendToEditable(editable, message)
            } else {
                logText.append("\n$message")
            }
            scrollLogToBottom()
        }
    }

    /*
     * Append in place and drop the oldest characters: no full
     * copy of the log on every line.
     */
    private fun appendToEditable(editable: Editable, message: String) {
        if (editable.isNotEmpty()) {
            editable.append('\n')
        }
        editable.append(message)
        val excess = editable.length - MAX_LOG_CHARACTERS
        if (excess > 0) {
            val cut = editable.indexOf('\n', excess).let { if (it < 0) excess else it + 1 }
            editable.delete(0, cut)
        }
    }

    private fun clearLog() {
        machineService?.logBook?.clearMemory()
        setLog("")
    }

    private fun scrollLogToBottom() {
        if (currentPage != PAGE_CONSOLE) return
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /*
     * ----------------------------------------------------
     * FORMATTING
     * ----------------------------------------------------
     */

    private fun describe(throwable: Throwable): String =
        throwable.message ?: throwable.javaClass.simpleName

    private fun formatMachineState(state: MachineState): String {
        return when (state) {
            MachineState.Disconnected -> "DISCONNECTED"
            MachineState.Connecting -> "CONNECTING"
            MachineState.Idle -> "IDLE"
            MachineState.Running -> "RUNNING"
            MachineState.Jog -> "JOG"
            MachineState.Hold -> "HOLD"
            MachineState.Alarm -> "ALARM"
            is MachineState.Error -> "ERROR: ${state.message}"
        }
    }

    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.3f", value)

    private fun formatNumber(value: Double): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    /*
     * ----------------------------------------------------
     * DESTRUCTION
     * ----------------------------------------------------
     */

    override fun onDestroy() {

        continuousJogJob?.cancel()
        continuousJogJob = null

        cancelBackendObservers()
        activityScope.cancel()
        screenHandler.removeCallbacks(dimRunnable)

        runCatching { unregisterReceiver(usbPermissionReceiver) }

        if (serviceBound) {
            unbindService(machineServiceConnection)
            serviceBound = false
        }
        machineService = null

        /*
         * Do not close USB, do not disconnect the machine:
         * MachineService owns the backend.
         */
        super.onDestroy()
    }
}
