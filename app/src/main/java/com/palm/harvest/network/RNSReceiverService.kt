package com.palm.harvest.network

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.palm.harvest.data.AppDatabase
import com.palm.harvest.data.HarvestReport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.*
import androidx.room.Room
import org.json.JSONObject

class RNSReceiverService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
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
            device?.let { startBridge(it) }
        }
        return START_STICKY
    }

    private fun startBridge(device: BluetoothDevice) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                onStatusUpdate("Connecting BT...")
                btSocket?.close()
                tcpServer?.close()
                
                // Use the 'Insecure' method from rnshello for better compatibility
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(device, 1) as BluetoothSocket
                btSocket?.connect()
                
                // Adopt Port 7633 from rnshello
                tcpServer = ServerSocket()
                tcpServer?.reuseAddress = true
                tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                
                onStatusUpdate("Bridge 7633 Ready")
                
                // Trigger Python RNS to link to this port
                injectPythonInterface()

                val client = tcpServer?.accept() ?: return@launch
                client.tcpNoDelay = true
                val btIn = btSocket!!.inputStream
                val btOut = btSocket!!.outputStream
                val tcpIn = client.inputStream
                val tcpOut = client.outputStream

                // BT -> TCP Pipe
                launch {
                    val buf = ByteArray(1024)
                    try {
                        var r = 0 // FIX: Explicitly initialized
                        while (isActive && btIn.read(buf).also { r = it } != -1) {
                            if (r > 0) {
                                tcpOut.write(buf, 0, r)
                                tcpOut.flush()
                            }
                        }
                    } catch (e: Exception) { Log.e("RNS-BRIDGE", "BT Read Error") }
                }

                // TCP -> BT Pipe
                launch {
                    val buf = ByteArray(1024)
                    try {
                        var r = 0 // FIX: Explicitly initialized
                        while (isActive && tcpIn.read(buf).also { r = it } != -1) {
                            if (r > 0) {
                                btOut.write(buf, 0, r)
                                btOut.flush()
                            }
                        }
                    } catch (e: Exception) { Log.e("RNS-BRIDGE", "TCP Read Error") }
                }

                onStatusUpdate("Mesh Link Established")

            } catch (e: Exception) {
                onStatusUpdate("Bridge Error")
                Log.e("PalmHarvest", "BT Bridge fail", e)
            }
        }
    }

    private fun injectPythonInterface() {
        serviceScope.launch {
            val py = Python.getInstance()
            val prefs = getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
            val json = JSONObject()
            json.put("freq", prefs.getInt("freq", 915000000))
            json.put("bw", prefs.getInt("bw", 125000))
            json.put("tx", prefs.getInt("tx", 20))
            json.put("sf", prefs.getInt("sf", 7))
            json.put("cr", prefs.getInt("cr", 5))
            
            // Trigger Python to inject the interface pointing to 7633
            py.getModule("rns_engine").callAttr("inject_rnode", json.toString())
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
            py.getModule("rns_engine").callAttr("start_engine", this@RNSReceiverService, filesDir.absolutePath)
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

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Palm Harvest Receiver")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RNS Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}