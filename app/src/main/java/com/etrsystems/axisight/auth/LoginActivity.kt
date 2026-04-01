package com.etrsystems.axisight.auth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.etrsystems.axisight.MainActivity
import com.etrsystems.axisight.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Exponential back-off after consecutive failures.
    // Delays: 1st=2s, 2nd=4s, 3rd=8s, 4th+=16s (capped).
    private var failureCount = 0
    private var backoffUntilMs = 0L
    private val backoffHandler = Handler(Looper.getMainLooper())
    private var backoffRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (auth.currentUser != null) {
            navigateToMain()
            return
        }

        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnSignIn.setOnClickListener { handleEmailSignIn() }
        b.btnGoogle.setOnClickListener { handleGoogleSignIn() }
        b.btnCreateAccount.setOnClickListener { handleCreateAccount() }
        b.tvForgotPassword.setOnClickListener { handleForgotPassword() }
    }

    private fun handleEmailSignIn() {
        if (!checkBackoff()) return
        val email = b.etEmail.text.toString().trim()
        val password = b.etPassword.text.toString()
        if (!validateInputs(email, password)) return

        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { resetBackoff(); navigateToMain() }
            .addOnFailureListener { e ->
                setLoading(false)
                android.util.Log.w("LoginActivity", "signInWithEmailAndPassword failed", e)
                recordFailure()
                Toast.makeText(this, "Sign in failed. Check your email and password and try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleGoogleSignIn() {
        val webClientId = getString(com.etrsystems.axisight.R.string.default_web_client_id)
        if (webClientId.startsWith("REPLACE_")) {
            Toast.makeText(this, "Google sign-in not configured yet. Enable it in Firebase Console.", Toast.LENGTH_LONG).show()
            return
        }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        setLoading(true)
        @Suppress("DEPRECATION")
        startActivityForResult(client.signInIntent, RC_GOOGLE_SIGN_IN)
    }

    private fun handleCreateAccount() {
        if (!checkBackoff()) return
        val email = b.etEmail.text.toString().trim()
        val password = b.etPassword.text.toString()
        if (!validateInputs(email, password)) return

        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { resetBackoff(); navigateToMain() }
            .addOnFailureListener { e ->
                setLoading(false)
                android.util.Log.w("LoginActivity", "createUserWithEmailAndPassword failed", e)
                recordFailure()
                Toast.makeText(this, "Account creation failed. Check your email and try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleForgotPassword() {
        val email = b.etEmail.text.toString().trim()
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.error = "Enter a valid email to reset password"
            return
        }
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(this, "Password reset email sent.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                android.util.Log.w("LoginActivity", "sendPasswordResetEmail failed", e)
                Toast.makeText(this, "Could not send reset email. Check the address and try again.", Toast.LENGTH_LONG).show()
            }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_GOOGLE_SIGN_IN) return

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { navigateToMain() }
                .addOnFailureListener { e ->
                    setLoading(false)
                    android.util.Log.w("LoginActivity", "signInWithCredential failed", e)
                Toast.makeText(this, "Google sign in failed. Please try again.", Toast.LENGTH_LONG).show()
                }
        } catch (e: ApiException) {
            setLoading(false)
            Toast.makeText(this, "Google sign in cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        var valid = true
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.error = "Enter a valid email"
            valid = false
        } else {
            b.tilEmail.error = null
        }
        if (password.length < 8) {
            b.tilPassword.error = "Password must be at least 8 characters"
            valid = false
        } else {
            b.tilPassword.error = null
        }
        return valid
    }

    /** Returns true if the attempt is allowed, false if still in back-off. */
    private fun checkBackoff(): Boolean {
        val remaining = backoffUntilMs - SystemClock.elapsedRealtime()
        if (remaining > 0) {
            val secs = (remaining / 1000) + 1
            Toast.makeText(this, "Too many attempts. Try again in ${secs}s.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun recordFailure() {
        failureCount++
        val delayMs = (2000L shl (failureCount - 1).coerceAtMost(3)) // 2s, 4s, 8s, 16s cap
        backoffUntilMs = SystemClock.elapsedRealtime() + delayMs
        setButtonsEnabled(false)
        val runnable = Runnable { setButtonsEnabled(true) }
        backoffRunnable = runnable
        backoffHandler.postDelayed(runnable, delayMs)
    }

    private fun resetBackoff() {
        failureCount = 0
        backoffUntilMs = 0L
        backoffRunnable?.let { backoffHandler.removeCallbacks(it) }
        backoffRunnable = null
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        b.btnSignIn.isEnabled = enabled
        b.btnGoogle.isEnabled = enabled
        b.btnCreateAccount.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        backoffRunnable?.let { backoffHandler.removeCallbacks(it) }
    }

    private fun setLoading(loading: Boolean) {
        b.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        b.btnSignIn.isEnabled = !loading
        b.btnGoogle.isEnabled = !loading
        b.btnCreateAccount.isEnabled = !loading
    }

    @OptIn(UnstableApi::class)
    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        private const val RC_GOOGLE_SIGN_IN = 9001
    }
}
