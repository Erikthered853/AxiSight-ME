package com.etrsystems.axisight.auth

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

object AuthManager {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    val isSignedIn: Boolean get() = auth.currentUser != null

    fun signOut(context: Context) {
        auth.signOut()
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    fun requireAuth(activity: AppCompatActivity) {
        if (!isSignedIn) {
            redirectToLogin(activity)
            return
        }
        // Force-refresh the token to catch disabled/deleted accounts before the SDK's
        // background refresh runs. Silently signs out if the token is no longer valid.
        auth.currentUser?.getIdToken(true)?.addOnFailureListener {
            android.util.Log.w("AuthManager", "Token refresh failed — signing out", it)
            signOut(activity)
        }
    }

    private fun redirectToLogin(activity: AppCompatActivity) {
        val intent = Intent(activity, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }

    fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
        auth.addAuthStateListener(listener)
    }

    fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
        auth.removeAuthStateListener(listener)
    }
}
