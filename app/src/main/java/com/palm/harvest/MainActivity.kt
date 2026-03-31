package com.palm.harvest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.palm.harvest.databinding.ActivityMainBinding
import com.palm.harvest.network.RNSReceiverService
import com.palm.harvest.ui.MainPagerAdapter
import com.palm.harvest.ui.RadioSettingsDialog

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val btPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) showDevicePicker()
        else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when(pos) { 0 -> "📡 Incoming"; 1 -> "📊 Summary"; else -> "🔍 Nodes" }
        }.attach()

        startAndBindService()
        
        // Correctly observing LiveData (No Coroutine needed)
        RNSReceiverService.serviceStatus.observe(this) { status ->
            binding.statusText.text = status
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Connect RNode")
        menu.add(0, 2, 1, "Radio Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { requestBtPerms(); true }
            2 -> { RadioSettingsDialog().show(supportFragmentManager, "radio_settings"); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestBtPerms() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)

        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            showDevicePicker()
        else
            btPermissionLauncher.launch(perms)
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val paired = adapter?.bondedDevices?.toList() ?: emptyList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "Pair RNode in Phone Settings first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Select RNode")
            .setItems(paired.map { it.name }.toTypedArray()) { _, i ->
                val intent = Intent(this, RNSReceiverService::class.java).apply {
                    action = RNSReceiverService.ACTION_CONNECT
                    putExtra(RNSReceiverService.EXTRA_DEVICE, paired[i])
                }
                startService(intent)
            }.show()
    }

    private fun startAndBindService() {
        startService(Intent(this, RNSReceiverService::class.java))
    }
}