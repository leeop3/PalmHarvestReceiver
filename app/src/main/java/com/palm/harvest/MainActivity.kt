package com.palm.harvest

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.gms.location.LocationServices
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var photoB64: String = ""
    private var lastLocation: Location? = null
    private lateinit var statusText: TextView

    // Camera Launcher
    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageBitmap = result.data?.extras?.get("data") as Bitmap
            // Compress to tiny thumbnail (150x150) for LoRa efficiency
            val scaled = Bitmap.createScaledBitmap(imageBitmap, 150, 150, false)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.WEBP, 50, baos)
            photoB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            Toast.makeText(this, "Photo Captured & Compressed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = ScrollView(this).apply {
            val inner = LinearLayout(context).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
            }
            addView(inner)
        }
        val container = (layout.getChildAt(0) as LinearLayout)

        statusText = TextView(this).apply { text = "RNS Status: Starting..." }
        val editBaseStation = EditText(this).apply { hint = "Base Station Hex Address" }
        val editHarvester = EditText(this).apply { hint = "Harvester ID" }
        val editBlock = EditText(this).apply { hint = "Block ID" }
        val editRipe = EditText(this).apply { hint = "Ripe Bunches"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        
        val btnPhoto = Button(this).apply { text = "Snap Photo" }
        val btnSubmit = Button(this).apply { text = "SUBMIT GRADE" }

        container.addView(statusText)
        container.addView(editBaseStation); container.addView(editHarvester)
        container.addView(editBlock); container.addView(editRipe)
        container.addView(btnPhoto); container.addView(btnSubmit)
        setContentView(layout)

        // Init GPS
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        btnPhoto.setOnClickListener { 
            takePhoto.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation = it }
        }

        btnSubmit.setOnClickListener {
            val csv = "${System.currentTimeMillis()},${editHarvester.text},${editBlock.text},${editRipe.text},0," +
                      "${lastLocation?.latitude ?: 0.0},${lastLocation?.longitude ?: 0.0},${System.currentTimeMillis()/1000},$photoB64"
            
            thread {
                try {
                    val py = Python.getInstance()
                    val rns = py.getModule("rns_engine")
                    val result = rns.callAttr("send_report", editBaseStation.text.toString(), csv)
                    runOnUiThread { Toast.makeText(this, "Sent: $result", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    runOnUiThread { statusText.text = "Error: ${e.message}" }
                }
            }
        }

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        thread { Python.getInstance().getModule("rns_engine").callAttr("start_engine", this, filesDir.absolutePath) }
    }

    fun onStatusUpdate(msg: String) { runOnUiThread { statusText.text = msg } }
}