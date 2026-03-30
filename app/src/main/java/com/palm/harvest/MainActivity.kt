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
import com.palm.harvest.data.*
import kotlinx.coroutines.launch
import java.util.*
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private var statusMsg by mutableStateOf("Ready")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "harvest-db").build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            val reports by db.harvestDao().getAllReports().collectAsState(initial = emptyList())
            val summaries by db.harvestDao().getSummaries().collectAsState(initial = emptyList())

            Scaffold(
                topBar = { TopAppBar(title = { Text("Palm Harvest: $statusMsg") }) },
                bottomBar = {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Incoming") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Summary") })
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    if (selectedTab == 0) ReportList(reports) else SummaryList(summaries)
                }
            }
        }

        // Start Python RNS Engine
        thread {
            val py = Python.getInstance()
            py.getModule("rns_engine").callAttr("start_engine", this, filesDir.absolutePath)
        }
    }

    @Composable
    fun ReportList(list: List<HarvestReport>) {
        LazyColumn {
            items(list) { item ->
                Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Harvester: ${item.harvesterId}", style = MaterialTheme.typography.titleMedium)
                        Text("Block: ${item.blockId} | Ripe: ${item.ripeBunches} | Empty: ${item.emptyBunches}")
                        Text("Time: ${Date(item.timestamp * 1000)}")
                    }
                }
            }
        }
    }

    @Composable
    fun SummaryList(list: List<HarvesterSummary>) {
        LazyColumn {
            items(list) { item ->
                ListItem(
                    headlineContent = { Text("Harvester: ${item.harvesterId}") },
                    supportingContent = { Text("Reports: ${item.reportCount} | Total Ripe: ${item.totalRipe}") }
                )
            }
        }
    }

    fun onStatusUpdate(msg: String) { statusMsg = msg }
    fun onHarvestReceived(id: String, hId: String, bId: String, ripe: Int, empty: Int, lat: Double, lon: Double, ts: Long, photo: String) {
        val report = HarvestReport(id, hId, bId, ripe, empty, lat, lon, ts, photo)
        kotlinx.coroutines.GlobalScope.launch { db.harvestDao().insert(report) }
    }
}