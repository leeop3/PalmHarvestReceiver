package com.palm.harvest

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.IOException
import java.net.ServerSocket
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var btSocket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            setPadding(50, 50, 50, 50)
        }
        
        statusText = TextView(this).apply { text = "Initializing..." ; textSize = 18f }
        val connectBtn = Button(this).apply { text = "Connect RNode (Bluetooth)" }
        
        layout.addView(statusText)
        layout.addView(connectBtn)
        setContentView(layout)

        connectBtn.setOnClickListener { checkPermissionsAndShowDevices() }

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        
        thread {
            try {
                val py = Python.getInstance()
                val rnsModule = py.getModule("rns_engine")
                rnsModule.callAttr("start_engine", this, filesDir.absolutePath)
            } catch (e: Exception) { Log.e("PalmHarvest", "Python: ${e.message}") }
        }
    }

    private fun checkPermissionsAndShowDevices() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        } else {
            showPairedDevices()
        }
    }

    private fun showPairedDevices() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val pairedDevices = btManager.adapter.bondedDevices
        val deviceNames = pairedDevices.map { it.name ?: it.address }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select RNode")
            .setItems(deviceNames) { _, which ->
                val device = pairedDevices.elementAt(which)
                startBluetoothBridge(device)
            }.show()
    }

    private fun startBluetoothBridge(device: BluetoothDevice) {
        thread {
            try {
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket?.connect()
                onStatusUpdate("BT Connected to ${device.name}")
                
                val serverSocket = ServerSocket(8001)
                onStatusUpdate("Bridge Port 8001 Open")
                
                val client = serverSocket.accept()
                
                val btIn = btSocket!!.inputStream
                val btOut = btSocket!!.outputStream
                val tcpIn = client.inputStream
                val tcpOut = client.outputStream

                // Thread 1: Bluetooth -> TCP
                thread {
                    val buf = ByteArray(1024)
                    while (true) {
                        val len = btIn.read(buf)
                        if (len > 0) tcpOut.write(buf, 0, len)
                    }
                }
                
                // Thread 2: TCP -> Bluetooth
                val buf = ByteArray(1024)
                while (true) {
                    val len = tcpIn.read(buf)
                    if (len > 0) btOut.write(buf, 0, len)
                }

            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "BT Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun onStatusUpdate(msg: String) { runOnUiThread { statusText.text = "Status: $msg" } }
    fun onHarvestReceived(id: String, hId: String, bId: String, ripe: Int, empty: Int, lat: Double, lon: Double, ts: Long) {
        runOnUiThread { statusText.append("\n\nReport: $hId | Ripe: $ripe") }
    }
}