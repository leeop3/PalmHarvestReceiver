package com.palm.harvest

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.util.Log
import android.widget.TextView
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI to show status
        statusText = TextView(this).apply { 
            text = "RNS Initializing..." 
            textSize = 18f
            setPadding(40, 40, 40, 40)
        }
        setContentView(statusText)

        // Start Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        // Start the RNS Engine in a background thread
        thread {
            try {
                val py = Python.getInstance()
                val rnsModule = py.getModule("rns_engine")
                // Pass 'this' as the callback object
                rnsModule.callAttr("start_engine", this, filesDir.absolutePath)
            } catch (e: Exception) {
                Log.e("PalmHarvest", "Python Error: ${e.message}")
            }
        }
    }

    // Called by Python to update UI
    fun onStatusUpdate(message: String) {
        runOnUiThread {
            statusText.text = "Status: $message"
        }
    }

    // Called by Python when a Harvest Report arrives
    fun onHarvestReceived(id: String, hId: String, bId: String, ripe: Int, empty: Int, lat: Double, lon: Double, ts: Long) {
        runOnUiThread {
            statusText.append("\n\nNew Report!\nHarvester: $hId\nRipe: $ripe\nBlock: $bId")
        }
    }
}