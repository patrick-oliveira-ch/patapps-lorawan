package com.lorawan.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lorawan.app.R
import com.lorawan.app.databinding.ActivityMainBinding
import com.lorawan.app.serial.UsbSerialManager
import com.lorawan.app.service.UsbMonitorService
import com.lorawan.app.utils.AlertManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbSerialManager
    private lateinit var alertManager: AlertManager

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val consoleBuffer = StringBuilder()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appendConsole("Permission notifications accordée")
        } else {
            appendConsole("⚠️ Permission notifications refusée - les alertes ne fonctionneront pas correctement")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = UsbSerialManager(this)
        alertManager = AlertManager.getInstance(this)

        setupUI()
        observeState()
        requestNotificationPermission()

        // Start foreground service
        startMonitorService()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, UsbMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun setupUI() {
        binding.sendButton.setOnClickListener {
            sendMessage()
        }

        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        binding.clearButton.setOnClickListener {
            consoleBuffer.clear()
            binding.consoleText.text = ""
        }

        binding.helpButton.setOnClickListener {
            binding.helpCard.visibility = if (binding.helpCard.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            usbManager.connectionState.collectLatest { state ->
                updateConnectionUI(state)
            }
        }

        lifecycleScope.launch {
            usbManager.receivedData.collectLatest { data ->
                processReceivedData(data)
            }
        }

        lifecycleScope.launch {
            usbManager.logs.collectLatest { log ->
                appendConsole(log)
            }
        }
    }

    private fun processReceivedData(data: String) {
        val trimmedData = data.trim()
        if (trimmedData.isEmpty()) return

        appendConsole("RX: $trimmedData")

        // Detect alert level and trigger appropriate alert
        val level = alertManager.detectAlertLevel(trimmedData)
        val levelIcon = when (level) {
            AlertManager.AlertLevel.INFO -> "🟢"
            AlertManager.AlertLevel.WARNING -> "🟡"
            AlertManager.AlertLevel.CRITICAL -> "🔴"
        }
        appendConsole("$levelIcon Niveau détecté: ${level.name}")

        // Trigger the alert
        alertManager.triggerAlert(trimmedData, level)
    }

    private fun updateConnectionUI(state: UsbSerialManager.ConnectionState) {
        runOnUiThread {
            when (state) {
                UsbSerialManager.ConnectionState.DISCONNECTED -> {
                    binding.statusIndicator.setBackgroundColor(Color.parseColor("#F44336"))
                    binding.statusText.text = getString(R.string.status_disconnected)
                    binding.sendButton.isEnabled = false
                }
                UsbSerialManager.ConnectionState.CONNECTING -> {
                    binding.statusIndicator.setBackgroundColor(Color.parseColor("#FF9800"))
                    binding.statusText.text = "Connexion..."
                    binding.sendButton.isEnabled = false
                }
                UsbSerialManager.ConnectionState.CONNECTED -> {
                    binding.statusIndicator.setBackgroundColor(Color.parseColor("#4CAF50"))
                    binding.statusText.text = getString(R.string.status_connected)
                    binding.sendButton.isEnabled = true
                }
                UsbSerialManager.ConnectionState.ERROR -> {
                    binding.statusIndicator.setBackgroundColor(Color.parseColor("#F44336"))
                    binding.statusText.text = "Erreur - reconnexion..."
                    binding.sendButton.isEnabled = false
                }
            }
        }
    }

    private fun sendMessage() {
        val message = binding.messageInput.text?.toString()?.trim() ?: return
        if (message.isEmpty()) return

        lifecycleScope.launch {
            if (usbManager.send(message)) {
                binding.messageInput.text?.clear()
            }
        }
    }

    private fun appendConsole(text: String) {
        runOnUiThread {
            val timestamp = dateFormat.format(Date())
            consoleBuffer.append("[$timestamp] $text\n")
            binding.consoleText.text = consoleBuffer.toString()

            // Auto-scroll to bottom
            binding.consoleScroll.post {
                binding.consoleScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.cleanup()
    }
}
