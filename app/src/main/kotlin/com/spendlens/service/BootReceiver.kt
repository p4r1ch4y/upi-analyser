package com.spendlens.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the foreground service and rebinds the notification listener on boot.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted - restarting transaction capture service")
            TransactionCaptureService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
