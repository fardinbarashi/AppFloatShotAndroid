package com.fardin.floatshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var btnOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = findViewById(R.id.overlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { askForCapture() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onResume() {
        super.onResume()
        val allowed = Settings.canDrawOverlays(this)
        overlayStatus.setText(if (allowed) R.string.overlay_ok else R.string.overlay_missing)
        btnOverlay.isEnabled = !allowed
    }

    private fun askForCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.toast_denied, Toast.LENGTH_SHORT).show()
            return
        }

        val start = Intent(this, BubbleService::class.java)
            .putExtra(BubbleService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(BubbleService.EXTRA_DATA, data)
        startForegroundService(start)

        // Step out of the way so the user lands on the app they want to capture.
        moveTaskToBack(true)
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIFY = 2
    }
}
