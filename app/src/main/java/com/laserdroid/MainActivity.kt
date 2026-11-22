package com.laserdroid

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.jobs.JobSnapshot
import com.laserdroid.core.jobs.JobStartResult
import com.laserdroid.core.jobs.JobState
import com.laserdroid.core.machine.MachineEvent
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.machine.MachineOperationResult
import com.laserdroid.core.machine.MachineSnapshot
import com.laserdroid.service.MachineService
import com.laserdroid.service.MachineServiceConnection
import com.laserdroid.ui.job.JobPanelView
import com.laserdroid.ui.jog.JogDirection
import com.laserdroid.ui.jog.JogPanelView
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

        private const val SAVED_LOG = "laserdroid.saved.log"
        private const val SAVED_JOG_SPEED = "laserdroid.saved.jog.speed"
        private const val SAVED_JOG_STEP = "laserdroid.saved.jog.step"
        private const val SAVED_PAGE = "laserdroid.saved.page"
        private const val SAVED_LASER_OFF = "laserdroid.saved.laser.off"

        private const val MAX_LOG_CHARACTERS = 60_000

        private const val REQUEST_OPEN_GCODE = 4201

        private const val JOB_DISPLAY_REFRESH_MS = 1_000L

        private const val PAGE_JOG = 0
        private const val PAGE_JOB = 1
        private const val PAGE_CONSOLE = 2

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
    private lateinit var lastMessageText: TextView
    private lateinit var jogPanel: JogPanelView
    private lateinit var jobPanelView: JobPanelView
    private lateinit var consolePanel: View
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
    private var lastMachineSnapshot: MachineSnapshot = MachineSnapshot.initial()
    private var lastJobState: JobState? = null

    /* Coroutines bound to the Activity */
    private var serviceObserverJob: Job? = null
    private var jobObserverJob: Job? = null
    private var jobTickerJob: Job? = null
    private var managerEventsJob: Job? = null
    private var controllerEventsJob: Job? = null
    private var managerSettingsJob: Job? = null
    private var continuousJogJob: Job? = null

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

        /* Keep the display on while this Activity is visible. */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        bindViews()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbPermissionReceiver()

        configureStatusBar()
        configureTabs()
        configureConsole()
        configureJogPanel()
        configureJobPanel()

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
        lastMessageText = findViewById(R.id.textLastMessage)
        jogPanel = findViewById(R.id.jogPanel)
        jobPanelView = findViewById(R.id.jobPanelView)
        consolePanel = findViewById(R.id.consolePanel)
        logText = findViewById(R.id.textLog)
        logScrollView = findViewById(R.id.logScrollView)
        commandInput = findViewById(R.id.editCommand)
        tabs = listOf(
            findViewById(R.id.tabJog),
            findViewById(R.id.tabJob),
            findViewById(R.id.tabConsole)
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

        lastMessageText.setOnClickListener {
            showPage(PAGE_CONSOLE)
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
        lastMessageText.visibility = if (page == PAGE_CONSOLE) View.GONE else View.VISIBLE
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
        positionText.text = if (offline) {
            "X  ---.---  Y  ---.---"
        } else {
            String.format(
                Locale.US,
                "X %8.3f  Y %8.3f",
                snapshot.position.x,
                snapshot.position.y
            )
        }

        jogPanel.updateMachineState(state)
        jobPanelView.updateMachineState(state)

        val service = machineService
        if (offline || service == null) {
            jobPanelView.updateHeadPosition(null)
        } else {
            val offset = service.workCoordinateOffset
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

        /*
         * After a rotation the backend already exists:
         * attach to it immediately.
         */
        service.manager?.let { manager ->
            observeMachineManager(manager)
        }
    }

    private fun observeMachineManager(manager: MachineManager) {

        managerEventsJob?.cancel()
        controllerEventsJob?.cancel()
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

        managerEventsJob =
            activityScope.launch {
                manager.events.collect { event ->
                    when (event) {
                        MachineEvent.Connected -> appendLog("Machine connected.")
                        MachineEvent.Disconnected -> appendLog("Machine disconnected.")
                        is MachineEvent.JogStarted -> appendLog(event.description)
                        /* Position is shown by the status bar. */
                        is MachineEvent.PositionUpdated -> Unit
                        is MachineEvent.JogCompleted -> appendLog(
                            "Jog completed: X=${formatCoordinate(event.position.x)}, " +
                                    "Y=${formatCoordinate(event.position.y)}"
                        )
                        MachineEvent.JogCancelled -> appendLog("Jog cancelled.")
                        is MachineEvent.Error -> appendLog("Machine error: ${event.message}")
                    }
                }
            }

        /*
         * Detailed GRBL output: $0=10, [VER:...], error:..., ALARM:...
         */
        controllerEventsJob =
            activityScope.launch {
                manager.controllerEvents.collect { event ->
                    when (event) {
                        is ControllerEvent.SettingReceived ->
                            appendLog("\$${event.number}=${event.value}")
                        is ControllerEvent.MessageReceived ->
                            appendLog(event.message)
                        is ControllerEvent.ErrorReceived ->
                            appendLog("GRBL ERROR: ${event.code} ${event.message}")
                        is ControllerEvent.AlarmReceived ->
                            appendLog("GRBL ALARM: ${event.code} ${event.message}")
                        ControllerEvent.Connected,
                        ControllerEvent.Disconnected -> Unit
                    }
                }
            }
    }

    private fun cancelBackendObservers() {
        serviceObserverJob?.cancel()
        serviceObserverJob = null
        managerEventsJob?.cancel()
        managerEventsJob = null
        controllerEventsJob?.cancel()
        controllerEventsJob = null
        managerSettingsJob?.cancel()
        managerSettingsJob = null
        jobObserverJob?.cancel()
        jobObserverJob = null
        jobTickerJob?.cancel()
        jobTickerJob = null
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
                    appendLog("Machine backend connected.")
                    service.manager?.let { observeMachineManager(it) }
                }
                .onFailure { exception ->
                    appendLog(
                        "Connection failed: " +
                                (exception.message ?: exception.javaClass.simpleName)
                    )
                }
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
                    appendLog("Disconnected.")
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
        }
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
        jobPanelView.onOpenRequested = { openGCodeFile() }
        jobPanelView.onStartRequested = { laserOff ->
            if (laserOff) startJob(laserOff = true) else confirmLaserOnStart()
        }
        jobPanelView.onPauseRequested = {
            runJobCommand("Pause") { it.jobManager.pause() }
        }
        jobPanelView.onResumeRequested = {
            runJobCommand("Resume") { it.jobManager.resume() }
        }
        jobPanelView.onStopRequested = {
            runJobCommand("Stop") { it.jobManager.stop() }
        }
    }

    private fun observeJob(service: MachineService) {

        jobObserverJob?.cancel()
        jobObserverJob =
            activityScope.launch {
                service.jobSnapshot.collect { job ->
                    renderJob(job)
                    logJobTransition(job)
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
        jobPanelView.updateJob(snapshot = job, nowMillis = System.currentTimeMillis())

        if (job.state.isActive) {
            jobBadgeText.visibility = View.VISIBLE
            jobBadgeText.text = String.format(Locale.US, "%.0f%%", job.percent)
        } else {
            jobBadgeText.visibility = View.GONE
        }
    }

    private fun logJobTransition(job: JobSnapshot) {
        if (job.state == lastJobState) return
        val previous = lastJobState
        lastJobState = job.state

        /* After a rotation, the first value is not a transition. */
        if (previous == null) return

        val message = job.message?.takeIf { !job.state.isActive }
        appendLog("JOB ${job.state.name}" + if (message != null) ": $message" else "")
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
            appendLog("Loading G-code...")
            service.importAndLoad(uri)
                .onSuccess { analysis ->
                    appendLog(
                        "Loaded ${analysis.name}: ${analysis.executableCommands} commands, " +
                                "errors ${analysis.errorCount}, warnings ${analysis.warningCount}"
                    )
                    analysis.warnings.take(10).forEach { warning ->
                        appendLog("  ${warning.severity} L${warning.lineNumber}: ${warning.message}")
                    }
                }
                .onFailure { appendLog("Load failed: ${describe(it)}") }
        }
    }

    private fun confirmLaserOnStart() {

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
            .setPositiveButton("Start with laser") { _, _ -> startJob(laserOff = false) }
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
            when (val result = service.jobManager.start(laserOff = laserOff)) {
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

        setLog(savedInstanceState.getString(SAVED_LOG)?.takeIf { it.isNotBlank() } ?: "LaserDroid ready.")
        jogPanel.setSelectedSpeed(savedInstanceState.getDouble(SAVED_JOG_SPEED, 1_000.0))
        jogPanel.setSelectedStep(savedInstanceState.getDouble(SAVED_JOG_STEP, 1.0))
        jobPanelView.isLaserOffChecked = savedInstanceState.getBoolean(SAVED_LASER_OFF, true)
        showPage(savedInstanceState.getInt(SAVED_PAGE, PAGE_JOG))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_LOG, logText.text?.toString().orEmpty())
        outState.putDouble(SAVED_JOG_SPEED, jogPanel.getSelectedSpeed())
        outState.putDouble(SAVED_JOG_STEP, jogPanel.getSelectedStep())
        outState.putInt(SAVED_PAGE, currentPage)
        outState.putBoolean(SAVED_LASER_OFF, jobPanelView.isLaserOffChecked)
        super.onSaveInstanceState(outState)
    }

    private fun setLog(message: String) {
        runOnUiThread {
            logText.text = message.takeLast(MAX_LOG_CHARACTERS)
            lastMessageText.text = message.substringAfterLast('\n')
            scrollLogToBottom()
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val editable = logText.editableText
            if (editable != null) {
                appendToEditable(editable, message)
            } else {
                logText.append("\n$message")
            }
            lastMessageText.text = message
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
        setLog("Log cleared.")
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
