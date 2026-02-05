package com.lorawan.app.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.lorawan.app.databinding.ActivityAlertBinding
import com.lorawan.app.utils.AlertManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private lateinit var alertManager: AlertManager
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake up and show on lock screen
        setupWakeAndUnlock()

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alertManager = AlertManager.getInstance(applicationContext)

        // Get message from intent
        val message = intent.getStringExtra("message") ?: "Alerte reçue"
        binding.alertMessage.text = message
        binding.alertTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // Setup dismiss button
        binding.dismissButton.setOnClickListener {
            stopAlertAndFinish()
        }

        // Start pulse animation
        startPulseAnimation()
    }

    private fun setupWakeAndUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)

            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startPulseAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.3f, 0f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.pulseView, scaleX, scaleY, alpha).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopAlertAndFinish() {
        alertManager.stopAlert()
        pulseAnimator?.cancel()
        finish()
    }

    override fun onBackPressed() {
        // Don't allow back button to dismiss - must use the button
        // This ensures the user acknowledges the alert
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}
