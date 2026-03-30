package com.palm.harvest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.palm.harvest.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private var statusMsg = mutableStateOf("Ready")
    private val scope = CoroutineScope(Dispatchers.Main)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Room Database
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "harvest-db").build()

        setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            val reports by db.harvestDao().getAllReports().collectAsState(initial = emptyList())
            val summaries by db.harvestDao().getSummaries().collectAsState(initial = emptyList())
            val currentStatus by statusMsg

            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Palm Harvest: $currentStatus") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary,
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Incoming") },
                                icon = { /* Icon placeholder */ }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("Summary") },
                                icon = { /* Icon placeholder */ }
                            )
                        }
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding)) {
                        if (selectedTab == 0) ReportList(reports) else SummaryList(summaries)
                    }
                }
            }
        }

        // Initialize Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Start Python RNS Engine in background
        thread {
            try {
                val py = Python.getInstance()
                py.getModule("rns_engine").callAttr("start_engine", this, filesDir.absolutePath)
            } catch (e: Exception) {
                onStatusUpdate("Python Error: ${e.message}")
            }
        }
    }

    @Composable
    fun ReportList(list: List<HarvestReport>) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { item ->
                Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Harvester: ${item.harvesterId}", style = MaterialTheme.typography.titleMedium)
                        Text("Block: ${item.blockId}", style = MaterialTheme.typography.bodyMedium)
                        Text("Ripe: ${item.ripeBunches} | Empty: ${item.emptyBunches}", style = MaterialTheme.typography.bodySmall)
                        Text("Time: ${Date(item.timestamp * 1000)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    @Composable
    fun SummaryList(list: List<HarvesterSummary>) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(list) { item ->
                ListItem(
                    headlineContent = { Text("Harvester: ${item.harvesterId}") },
                    supportingContent = { Text("Total Ripe: ${item.totalRipe} | Reports: ${item.reportCount}") },
                    trailingContent = { Text("Daily Total") }
                )
            }
        }
    }

    // Callbacks from Python
    fun onStatusUpdate(msg: String) {
        scope.launch { statusMsg.value = msg }
    }

    fun onHarvestReceived(id: String, hId: String, bId: String, ripe: Int, empty: Int, lat: Double, lon: Double, ts: Long, photo: String) {
        val report = HarvestReport(id, hId, bId, ripe, empty, lat, lon, ts, photo)
        CoroutineScope(Dispatchers.IO).launch {
            db.harvestDao().insert(report)
        }
    }
}