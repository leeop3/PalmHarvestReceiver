package com.palm.harvest.network

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.palm.harvest.data.AppDatabase
import com.palm.harvest.data.HarvestReport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import androidx.room.Room

class RNSReceiverService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase

    companion object {
        val serviceStatus = MutableStateFlow("Stopped")
        const val CHANNEL_ID = "RNS_SERVICE_CHANNEL"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RNSReceiverService = this@RNSReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification("Starting RNS..."))
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "harvest-db").build()
        
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        startRnsEngine()
    }

    private fun startRnsEngine() {
        serviceScope.launch {
            val py = Python.getInstance()
            py.getModule("rns_engine").callAttr("start_engine", this@RNSReceiverService, filesDir.absolutePath)
        }
    }

    // Called by Python
    fun onStatusUpdate(msg: String) {
        serviceStatus.value = msg
        updateNotification(msg)
    }

    fun onHarvestReceived(id: String, hId: String, bId: String, ripe: Int, empty: Int, lat: Double, lon: Double, ts: Long, photo: String) {
        serviceScope.launch {
            val report = HarvestReport(id, hId, bId, ripe, empty, lat, lon, ts, photo)
            db.harvestDao().insert(report)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Palm Harvest Receiver")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "RNS Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }
}