package com.lorawan.app.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

class UsbSerialManager(private val context: Context) {

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var currentDriver: UsbSerialDriver? = null
    private var isConnecting = false

    // Buffer pour reconstituer les lignes complètes
    private val lineBuffer = StringBuilder()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _receivedData = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val receivedData: SharedFlow<String> = _receivedData

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs: SharedFlow<String> = _logs

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.lorawan.app.USB_PERMISSION"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (_connectionState.value != ConnectionState.CONNECTED && !isConnecting) {
                        log("Module USB détecté - connexion automatique...")
                        scope.launch { autoConnect() }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("Module USB déconnecté physiquement")
                    disconnectInternal(scheduleReconnect = false)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        scope.launch { autoConnect() }
                    } else {
                        log("Permission USB refusée")
                        _connectionState.value = ConnectionState.ERROR
                    }
                }
            }
        }
    }

    init {
        registerReceivers()
        // Auto-connect at startup
        scope.launch { autoConnect() }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun findDevices(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    suspend fun autoConnect(): Boolean {
        // Prevent multiple simultaneous connection attempts
        if (isConnecting || _connectionState.value == ConnectionState.CONNECTED) {
            return _connectionState.value == ConnectionState.CONNECTED
        }

        val drivers = findDevices()
        if (drivers.isEmpty()) {
            if (_connectionState.value != ConnectionState.DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            scheduleReconnect()
            return false
        }

        val driver = drivers[0]

        if (!usbManager.hasPermission(driver.device)) {
            log("Demande de permission USB...")
            requestPermission(driver.device)
            return false
        }

        return connect(driver)
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    suspend fun connect(
        driver: UsbSerialDriver,
        baudRate: Int = 9600,
        dataBits: Int = 8,
        stopBits: Int = UsbSerialPort.STOPBITS_1,
        parity: Int = UsbSerialPort.PARITY_NONE
    ): Boolean = withContext(Dispatchers.IO) {
        // Prevent multiple connection attempts
        if (isConnecting) return@withContext false
        isConnecting = true

        try {
            cancelReconnect()

            _connectionState.value = ConnectionState.CONNECTING
            log("Connexion au module USB...")

            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                log("Erreur: Impossible d'ouvrir le périphérique")
                _connectionState.value = ConnectionState.ERROR
                isConnecting = false
                scheduleReconnect()
                return@withContext false
            }

            currentDriver = driver
            port = driver.ports[0].apply {
                open(connection)
                setParameters(baudRate, dataBits, stopBits, parity)
                dtr = true
                rts = true
            }

            // Start reading data
            ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val text = String(data, Charsets.UTF_8)
                    processIncomingData(text)
                }

                override fun onRunError(e: Exception) {
                    log("Erreur de lecture: ${e.message}")
                    scope.launch { handleDisconnection() }
                }
            }).also {
                Executors.newSingleThreadExecutor().submit(it)
            }

            _connectionState.value = ConnectionState.CONNECTED
            log("Connecté! Baudrate: $baudRate")
            isConnecting = false
            true
        } catch (e: Exception) {
            log("Erreur de connexion: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            isConnecting = false
            scheduleReconnect()
            false
        }
    }

    @Synchronized
    private fun processIncomingData(text: String) {
        lineBuffer.append(text)

        // Chercher les lignes complètes (terminées par \n ou \r\n)
        var newlineIndex = lineBuffer.indexOf('\n')
        while (newlineIndex != -1) {
            var line = lineBuffer.substring(0, newlineIndex)
            // Retirer le \r si présent
            if (line.endsWith('\r')) {
                line = line.dropLast(1)
            }
            // Émettre la ligne complète si non vide
            if (line.isNotBlank()) {
                _receivedData.tryEmit(line)
            }
            // Retirer la ligne du buffer
            lineBuffer.delete(0, newlineIndex + 1)
            newlineIndex = lineBuffer.indexOf('\n')
        }
    }

    private fun handleDisconnection() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            disconnectInternal(scheduleReconnect = true)
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (_connectionState.value != ConnectionState.CONNECTED && !isConnecting) {
                scope.launch { autoConnect() }
            }
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun disconnectInternal(scheduleReconnect: Boolean) {
        try {
            ioManager?.listener = null
            ioManager?.stop()
            ioManager = null

            port?.close()
            port = null
            currentDriver = null

            // Vider le buffer de ligne
            synchronized(lineBuffer) {
                lineBuffer.clear()
            }

            _connectionState.value = ConnectionState.DISCONNECTED
            log("Déconnecté")

            if (scheduleReconnect) {
                scheduleReconnect()
            }
        } catch (e: Exception) {
            log("Erreur lors de la déconnexion: ${e.message}")
        }
    }

    fun disconnect() {
        cancelReconnect()
        disconnectInternal(scheduleReconnect = false)
    }

    suspend fun send(data: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = (data + "\r\n").toByteArray(Charsets.UTF_8)
            port?.write(bytes, 1000)
            log("TX: $data")
            true
        } catch (e: IOException) {
            log("Erreur d'envoi: ${e.message}")
            false
        }
    }

    suspend fun sendAT(command: String): Boolean {
        return send("AT$command")
    }

    private fun log(message: String) {
        _logs.tryEmit(message)
    }

    fun cleanup() {
        cancelReconnect()
        disconnectInternal(scheduleReconnect = false)
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}
