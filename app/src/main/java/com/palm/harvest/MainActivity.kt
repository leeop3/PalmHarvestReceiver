package com.palm.harvest

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.util.Log

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        val py = Python.getInstance()
        val rnsModule = py.getModule("rns_engine")
        
        // Pass 'this' as the callback object to Python
        rnsModule.callAttr("start_engine", this)
        
        Log.d("PalmHarvest", "RNS Engine Started Successfully")
    }

    // Callback called by Python rns_engine.py
    fun onAnnounceReceived(hash: String, name: String) {
        Log.d("PalmHarvest", "Announce received: $name ($hash)")
    }
}