package com.palm.harvest.network

import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
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
import java.io.IOException
import java.net.ServerSocket
import java.util.*
import androidx.room.Room

class RNSReceiverService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var btSocket: BluetoothSocket? = null
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private lateinit var db: AppDatabase

    companion object {
        val serviceStatus = MutableStateFlow("Stopped")
        const val CHANNEL_ID = "RNS_SERVICE_CHANNEL"
        const val ACTION_CONNECT = "com.palm.harvest.CONNECT"
        const val EXTRA_DEVICE = "bt_device"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RNSReceiverService = this@RNSReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONNECT) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)
            }
            device?.let { connectToRNode(it) }
        }
        return START_STICKY
    }

    private fun connectToRNode(device: BluetoothDevice) {
        serviceScope.launch {
            try {
                onStatusUpdate("Connecting to ${device.name}...")
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket?.connect()
                onStatusUpdate("Connected to RNode")
                startTcpBridge()
            } catch (e: IOException) {
                onStatusUpdate("BT Connection Failed")
            }
        }
    }

    private fun startTcpBridge() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(8001)
                val client = serverSocket.accept()
                val btIn = btSocket!!.inputStream
                val btOut = btSocket!!.outputStream
                val tcpIn = client.inputStream
                val tcpOut = client.outputStream

                launch {
                    val buffer = ByteArray(1024)
                    while (isActive) {
                        val len = btIn.read(buffer)
                        if (len > 0) tcpOut.write(buffer, 0, len)
                    }
                }
                val buffer = ByteArray(1024)
                while (isActive) {
                    val len = tcpIn.read(buffer)
                    if (len > 0) btOut.write(buffer, 0, len)
                }
            } catch (e: Exception) {
                onStatusUpdate("Bridge Closed")
            }
        }
    }

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
            val prefs = getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
            
            // Collect Radio Params into a Map for Python
            val radioParams = mutableMapOf<String, Any>()
            radioParams["freq"] = prefs.getInt("freq", 915000000)
            radioParams["bw"] = prefs.getInt("bw", 125000)
            radioParams["tx"] = prefs.getInt("tx", 20)
            radioParams["sf"] = prefs.getInt("sf", 7)
            radioParams["cr"] = prefs.getInt("cr", 5)

            // FIX: Calling with 3 arguments now
            py.getModule("rns_engine").callAttr(
                "start_engine", 
                this@RNSReceiverService, 
                filesDir.absolutePath,
                radioParams
            )
        }
    }

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

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Palm Harvest Receiver")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RNS Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}