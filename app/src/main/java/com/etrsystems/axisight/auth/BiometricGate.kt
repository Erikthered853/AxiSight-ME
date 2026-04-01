package com.etrsystems.axisight.auth

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class BiometricGate(
    private val activity: FragmentActivity,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val onAuthFailed: () -> Unit = {}
) {
    private var lastActiveMs: Long = SystemClock.elapsedRealtime()
    private var isPromptShowing = false

    fun onActivityResumed() {
        val elapsed = SystemClock.elapsedRealtime() - lastActiveMs
        if (elapsed >= idleTimeoutMs && !isPromptShowing && canAuthenticate()) {
            showPrompt()
        }
    }

    fun onActivityPaused() {
        lastActiveMs = SystemClock.elapsedRealtime()
    }

    private fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showPrompt() {
        if (isPromptShowing) return
        isPromptShowing = true

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isPromptShowing = false
                lastActiveMs = SystemClock.elapsedRealtime()
            }

            override fun onAuthenticationFailed() {
                // Wrong biometric — prompt stays visible
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                isPromptShowing = false
                // Any error that closes the prompt is treated as auth failure.
                // Silently ignoring transient errors (e.g. ERROR_TIMEOUT, ERROR_HW_UNAVAILABLE)
                // would leave the activity visible and interactable without re-auth.
                onAuthFailed()
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AxiSight")
            .setSubtitle("Confirm your identity to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
}
