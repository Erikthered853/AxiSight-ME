package com.etrsystems.axisight

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class UsbCameraActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_camera)

        try {
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, com.etrsystems.axisight.ui.UvcFragment())
                    .commit()
            }
        } catch (e: Exception) {
            Log.e("UsbCameraActivity", "Error initializing USB camera fragment", e)
            Toast.makeText(this, "Error initializing USB camera: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Clean up any USB resources
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (fragment != null) {
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commit()
            }
        } catch (e: Exception) {
            Log.e("UsbCameraActivity", "Error cleaning up USB camera", e)
        }
    }
}
