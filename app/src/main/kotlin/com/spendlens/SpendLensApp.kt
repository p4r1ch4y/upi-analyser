package com.spendlens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SpendLensApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Foreground service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows your spending total for today"
                setShowBadge(false)
            }
            
            // Transaction nudge channel
            val nudgeChannel = NotificationChannel(
                CHANNEL_NUDGE,
                "Payment Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows spending updates when you make a payment"
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannels(listOf(serviceChannel, nudgeChannel))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "transaction_service"
        const val CHANNEL_NUDGE = "transaction_nudge"
    }
}
