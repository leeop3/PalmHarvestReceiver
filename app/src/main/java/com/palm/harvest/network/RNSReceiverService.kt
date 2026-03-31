package com.palm.harvest.network

import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.palm.harvest.data.AppDatabase
import com.palm.harvest.data.DiscoveredNode
import com.palm.harvest.data.HarvestReport
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.json.JSONObject

class RNSReceiverService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private lateinit var db: AppDatabase

    companion object {
        // Explicitly defining LiveData variables
        val serviceStatus = MutableLiveData("Stopped")
        val localAddress = MutableLiveData("Waiting for RNS...")
        const val CHANNEL_ID = "RNS_SERVICE_CHANNEL"
        const val ACTION_CONNECT = "com.palm.harvest.CONNECT"
        const val EXTRA_DEVICE = "bt_device"
    }

    inner class LocalBinder : Binder() { fun getService(): RNSReceiverService = this@RNSReceiverService }
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
                
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(device, 1) as BluetoothSocket
                btSocket?.connect()
                
                tcpServer = ServerSocket()
                tcpServer?.reuseAddress = true
                tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                
                onStatusUpdate("Bridge 7633 Listening")
                launch { handleTcpClients() }
                delay(500)
                injectPythonInterface()
            } catch (e: Exception) {
                onStatusUpdate("Bridge Error")
            }
        }
    }

    private suspend fun handleTcpClients() {
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    val client = tcpServer?.accept() ?: break
                    client.tcpNoDelay = true
                    onStatusUpdate("Mesh Link Active")

                    val btIn = btSocket!!.inputStream
                    val btOut = btSocket!!.outputStream
                    val tcpIn = client.inputStream
                    val tcpOut = client.outputStream

                    launch {
                        val buf = ByteArray(2048)
                        var r = 0
                        while (isActive && btIn.read(buf).also { r = it } != -1) {
                            if (r > 0) { tcpOut.write(buf, 0, r); tcpOut.flush() }
                        }
                    }
                    launch {
                        val buf = ByteArray(2048)
                        var r = 0
                        while (isActive && tcpIn.read(buf).also { r = it } != -1) {
                            if (r > 0) { btOut.write(buf, 0, r); btOut.flush() }
                        }
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun injectPythonInterface() {
        serviceScope.launch {
            try {
                val py = Python.getInstance()
                val prefs = getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
                val json = JSONObject()
                json.put("freq", prefs.getInt("freq", 915000000))
                json.put("bw", prefs.getInt("bw", 125000))
                json.put("tx", prefs.getInt("tx", 20))
                json.put("sf", prefs.getInt("sf", 7))
                json.put("cr", prefs.getInt("cr", 5))
                py.getModule("rns_engine").callAttr("inject_rnode", json.toString())
            } catch (e: Exception) {
                Log.e("RNS", "Python error", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification("Starting RNS..."))
        db = AppDatabase.getDatabase(applicationContext)
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
        serviceStatus.postValue(msg)
        updateNotification(msg)
    }

    fun updateLocalAddress(addr: String) {
        localAddress.postValue(addr)
    }

    fun onNodeDiscovered(hash: String, nickname: String) {
        serviceScope.launch {
            try { db.harvestDao().trackNode(hash, nickname, System.currentTimeMillis()) } catch (e: Exception) {}
        }
    }

    fun onHarvestReceived(id: String, hId: String, bId: String, ripe: Int, empty: Int, lat: Double, lon: Double, ts: Long, photo: String) {
        serviceScope.launch {
            try { db.harvestDao().insertReport(HarvestReport(id, hId, bId, ripe, empty, lat, lon, ts, photo)) } catch (e: Exception) {}
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(1, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Palm Harvest Receiver")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RNS Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}