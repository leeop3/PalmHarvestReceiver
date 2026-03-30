package com.palm.harvest.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.palm.harvest.R
import com.palm.harvest.data.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// 1. 3-TAB PAGER
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3
    override fun createFragment(position: Int): Fragment {
        return when(position) {
            0 -> IncomingFragment()
            1 -> SummaryFragment()
            else -> NodesFragment()
        }
    }
}

// 2. INCOMING ADAPTER (Harvester, Block, Total Bunches, Time)
class IncomingAdapter : ListAdapter<HarvestReport, IncomingAdapter.ViewHolder>(DiffCallback()) {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester)
        val subtitle: TextView = v.findViewById(R.id.txtBlock)
        val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_harvest, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val totalBunches = item.ripeBunches + item.emptyBunches
        holder.title.text = "Harvester: ${item.harvesterId}"
        holder.subtitle.text = "Block: ${item.blockId}"
        holder.stats.text = "Total Bunches: $totalBunches | Time: ${sdf.format(Date(item.timestamp * 1000))}"
    }
    class DiffCallback : DiffUtil.ItemCallback<HarvestReport>() {
        override fun areItemsTheSame(old: HarvestReport, new: HarvestReport) = old.id == new.id
        override fun areContentsTheSame(old: HarvestReport, new: HarvestReport) = old == new
    }
}

// 3. SUMMARY ADAPTER (Sorted by Block: Ripe, Empty, Total)
class SummaryAdapter : ListAdapter<BlockSummary, SummaryAdapter.ViewHolder>(SumDiffCallback()) {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester)
        val subtitle: TextView = v.findViewById(R.id.txtBlock)
        val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_harvest, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = "Block: ${item.blockId}"
        holder.subtitle.text = "Total Harvest: ${item.totalBunches} bunches"
        holder.stats.text = "Ripe: ${item.totalRipe} | Empty: ${item.totalEmpty}"
    }
    class SumDiffCallback : DiffUtil.ItemCallback<BlockSummary>() {
        override fun areItemsTheSame(old: BlockSummary, new: BlockSummary) = old.blockId == new.blockId
        override fun areContentsTheSame(old: BlockSummary, new: BlockSummary) = old == new
    }
}

// 4. NODES ADAPTER (Discovered Harvesters)
class NodeAdapter : ListAdapter<DiscoveredNode, NodeAdapter.ViewHolder>(NodeDiffCallback()) {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester)
        val subtitle: TextView = v.findViewById(R.id.txtBlock)
        val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_harvest, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        holder.title.text = "Node: ${item.nickname}"
        holder.subtitle.text = "Hash: ${item.hash}"
        holder.stats.text = "Last Heard: ${sdf.format(Date(item.lastHeard))}"
    }
    class NodeDiffCallback : DiffUtil.ItemCallback<DiscoveredNode>() {
        override fun areItemsTheSame(old: DiscoveredNode, new: DiscoveredNode) = old.hash == new.hash
        override fun areContentsTheSame(old: DiscoveredNode, new: DiscoveredNode) = old == new
    }
}

// 5. FRAGMENTS
class IncomingFragment : Fragment(R.layout.fragment_incoming) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewIncoming)
        val adapter = IncomingAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext(), AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getAllReports().collectLatest { adapter.submitList(it) } }
    }
}

class SummaryFragment : Fragment(R.layout.fragment_summary) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewSummary)
        val adapter = SummaryAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext(), AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getBlockSummaries().collectLatest { adapter.submitList(it) } }
    }
}

class NodesFragment : Fragment(R.layout.fragment_nodes) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewNodes)
        val adapter = NodeAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext(), AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getAllNodes().collectLatest { adapter.submitList(it) } }
    }
}