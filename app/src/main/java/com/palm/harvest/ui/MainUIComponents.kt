package com.palm.harvest.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.palm.harvest.R
import com.palm.harvest.data.*
import com.palm.harvest.network.RNSReceiverService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainPagerAdapter(activity: FragmentActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3
    override fun createFragment(position: Int) = when(position) {
        0 -> IncomingFragment()
        1 -> SummaryFragment()
        else -> NodesFragment()
    }
}

class IncomingAdapter : ListAdapter<HarvestReport, IncomingAdapter.VH>(Diff()) {
    private val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView = v.findViewById(R.id.txtHarvester)
        val b: TextView = v.findViewById(R.id.txtBlock)
        val s: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: VH, p: Int) {
        val i = getItem(p)
        h.t.text = "Harvester: ${i.harvesterId}"
        h.b.text = "Block: ${i.blockId}"
        h.s.text = "Total: ${i.ripeBunches + i.emptyBunches} | ${df.format(Date(i.timestamp * 1000))}"
    }
    class Diff : DiffUtil.ItemCallback<HarvestReport>() {
        override fun areItemsTheSame(o: HarvestReport, n: HarvestReport) = o.id == n.id
        override fun areContentsTheSame(o: HarvestReport, n: HarvestReport) = o == n
    }
}

class SummaryAdapter : ListAdapter<BlockSummary, SummaryAdapter.VH>(Diff()) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView = v.findViewById(R.id.txtHarvester)
        val b: TextView = v.findViewById(R.id.txtBlock)
        val s: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: VH, p: Int) {
        val i = getItem(p)
        h.t.text = "Block: ${i.blockId}"
        h.b.text = "Total Harvest: ${i.totalBunches}"
        h.s.text = "Ripe: ${i.totalRipe} | Empty: ${i.totalEmpty}"
    }
    class Diff : DiffUtil.ItemCallback<BlockSummary>() {
        override fun areItemsTheSame(o: BlockSummary, n: BlockSummary) = o.blockId == n.blockId
        override fun areContentsTheSame(o: BlockSummary, n: BlockSummary) = o == n
    }
}

class NodeAdapter(private val onLocalClick: (String) -> Unit) : RecyclerView.Adapter<NodeAdapter.VH>() {
    private var localAddr: String = ""
    private var nodes: List<DiscoveredNode> = emptyList()
    private val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setLocalAddress(addr: String) {
        this.localAddr = addr
        notifyItemChanged(0)
    }

    fun setDiscoveredNodes(newList: List<DiscoveredNode>) {
        this.nodes = newList
        notifyDataSetChanged()
    }

    override fun getItemCount() = nodes.size + 1
    override fun getItemViewType(p: Int) = if (p == 0) 0 else 1

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: VH, p: Int) {
        if (p == 0) {
            h.t.text = "THIS RECEIVER (Tap for QR)"
            h.b.text = "Address: $localAddr"
            h.s.text = "Config Harvesters to this address"
            h.itemView.setOnClickListener { if (localAddr.isNotEmpty() && localAddr != "Stopped") onLocalClick(localAddr) }
        } else {
            val i = nodes[p - 1]
            h.t.text = "Harvester: ${i.nickname}"
            h.b.text = "Hash: ${i.hash}"
            h.s.text = "Last Heard: ${df.format(Date(i.lastHeard))}"
            h.itemView.setOnClickListener(null)
        }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView = v.findViewById(R.id.txtHarvester)
        val b: TextView = v.findViewById(R.id.txtBlock)
        val s: TextView = v.findViewById(R.id.txtStats)
    }
}

class IncomingFragment : Fragment(R.layout.fragment_incoming) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // FIX: Super call added
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewIncoming)
        val adp = IncomingAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext()) // FIX: Safe context
        rv.adapter = adp
        val db = AppDatabase.getDatabase(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch { 
            db.harvestDao().getAllReports().collectLatest { adp.submitList(it) } 
        }
    }
}

class SummaryFragment : Fragment(R.layout.fragment_summary) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // FIX: Super call added
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewSummary)
        val adp = SummaryAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext()) // FIX: Safe context
        rv.adapter = adp
        val db = AppDatabase.getDatabase(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch { 
            db.harvestDao().getBlockSummaries().collectLatest { adp.submitList(it) } 
        }
    }
}

class NodesFragment : Fragment(R.layout.fragment_nodes) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // FIX: Super call added
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewNodes)
        val adp = NodeAdapter { addr -> showQr(addr) }
        rv.layoutManager = LinearLayoutManager(requireContext()) // FIX: Safe context
        rv.adapter = adp
        val db = AppDatabase.getDatabase(requireContext().applicationContext)
        
        // FIX: Independent coroutines so they never block each other
        viewLifecycleOwner.lifecycleScope.launch {
            RNSReceiverService.localAddress.collectLatest { addr ->
                adp.setLocalAddress(addr)
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            db.harvestDao().getAllNodes().collectLatest { list ->
                adp.setDiscoveredNodes(list)
            }
        }
    }

    private fun showQr(text: String) {
        try {
            val bm = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val img = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) for (y in 0 until 512) img.setPixel(x, y, if (bm.get(x, y)) Color.BLACK else Color.WHITE)
            val iv = ImageView(context).apply { setPadding(40, 40, 40, 40); setImageBitmap(img) }
            AlertDialog.Builder(requireContext()).setTitle("Receiver Address").setView(iv).setPositiveButton("Close", null).show()
        } catch (e: Exception) { Toast.makeText(context, "QR Error", Toast.LENGTH_SHORT).show() }
    }
}