package com.palm.harvest

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.palm.harvest.databinding.ActivityMainBinding
import com.palm.harvest.network.RNSReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var rnsService: RNSReceiverService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RNSReceiverService.LocalBinder
            rnsService = binder.getService()
            isServiceBound = true
            observeServiceStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            rnsService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupViewPager()
        startAndBindService()
    }

    private fun setupViewPager() {
        // We will create PagerAdapter in next step
        // binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = if (pos == 0) "Incoming" else "Summary"
        }.attach()
    }

    private fun startAndBindService() {
        val intent = Intent(this, RNSReceiverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceStatus() {
        CoroutineScope(Dispatchers.Main).launch {
            RNSReceiverService.serviceStatus.collectLatest { status ->
                binding.statusText.text = "Status: $status"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}