package com.spendlens.service

import android.content.Intent
import android.service.quicksettings.TileService
import com.spendlens.ui.MainActivity

/**
 * A Quick Settings tile that opens straight onto the payment you just made.
 *
 * The gap it fills: a UPI app lets you type a remark when paying, and that remark
 * never leaves the payment app — it is in neither the notification nor the bank
 * SMS. By the time you next open a tracker you have forgotten what the ₹40 was.
 *
 * Pulling down the shade and tapping once, seconds after paying, is about as
 * close to capturing that thought as is possible without reading other apps'
 * screens — which this project has deliberately ruled out.
 */
class QuickNoteTile : TileService() {

    // The PendingIntent overload only exists from API 34; minSdk here is 26, so
    // the deprecated form is the only way to launch from a tile on most devices
    // this app runs on. Both branches are present and the modern one is
    // preferred where available.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            action = ACTION_NOTE_LATEST
        }
        // startActivityAndCollapse is required from a tile: launching an activity
        // any other way from the shade is ignored on newer Android.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val ACTION_NOTE_LATEST = "com.spendlens.action.NOTE_LATEST"
    }
}
