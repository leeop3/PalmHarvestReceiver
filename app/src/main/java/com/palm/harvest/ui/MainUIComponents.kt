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
import androidx.room.Room
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.palm.harvest.R
import com.palm.harvest.data.*
import com.palm.harvest.network.RNSReceiverService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3
    override fun createFragment(position: Int) = when(position) {
        0 -> IncomingFragment()
        1 -> SummaryFragment()
        else -> NodesFragment()
    }
}

class IncomingAdapter : ListAdapter<HarvestReport, IncomingAdapter.ViewHolder>(DiffCallback()) {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester)
        val subtitle: TextView = v.findViewById(R.id.txtBlock)
        val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val i = getItem(p); val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        h.title.text = "Harvester: ${i.harvesterId}"
        h.subtitle.text = "Block: ${i.blockId}"
        h.stats.text = "Total: ${i.ripeBunches + i.emptyBunches} | Time: ${sdf.format(Date(i.timestamp * 1000))}"
    }
    class DiffCallback : DiffUtil.ItemCallback<HarvestReport>() {
        override fun areItemsTheSame(o: HarvestReport, n: HarvestReport) = o.id == n.id
        override fun areContentsTheSame(o: HarvestReport, n: HarvestReport) = o == n
    }
}

class SummaryAdapter : ListAdapter<BlockSummary, SummaryAdapter.ViewHolder>(SumDiffCallback()) {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester)
        val subtitle: TextView = v.findViewById(R.id.txtBlock)
        val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val i = getItem(p)
        h.title.text = "Block: ${i.blockId}"
        h.subtitle.text = "Total Harvest: ${i.totalBunches}"
        h.stats.text = "Ripe: ${i.totalRipe} | Empty: ${i.totalEmpty}"
    }
    class SumDiffCallback : DiffUtil.ItemCallback<BlockSummary>() {
        override fun areItemsTheSame(o: BlockSummary, n: BlockSummary) = o.blockId == n.blockId
        override fun areContentsTheSame(o: BlockSummary, n: BlockSummary) = o == n
    }
}

class NodeAdapter(private val onLocalClick: (String) -> Unit) : RecyclerView.Adapter<NodeAdapter.NodeViewHolder>() {
    private var localAddr: String = ""
    private var discoveredNodes: List<DiscoveredNode> = emptyList()

    fun setLocalAddress(addr: String) {
        this.localAddr = addr
        notifyItemChanged(0)
    }

    fun setDiscoveredNodes(nodes: List<DiscoveredNode>) {
        this.discoveredNodes = nodes
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_harvest, parent, false)
        return NodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        if (position == 0) {
            holder.title.text = "THIS RECEIVER (Tap for QR)"
            holder.subtitle.text = if (localAddr.isEmpty()) "Waiting for RNS..." else "Address: $localAddr"
            holder.stats.text = "Point harvesters here"
            holder.itemView.setOnClickListener { if (localAddr.isNotEmpty()) onLocalClick(localAddr) }
        } else {
            val node = discoveredNodes[position - 1]
            holder.title.text = "Harvester: ${node.nickname}"
            holder.subtitle.text = "Hash: ${node.hash}"
            holder.stats.text = "Last seen: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(node.lastHeard))}"
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = discoveredNodes.size + 1

    class NodeViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester)
        val subtitle: TextView = v.findViewById(R.id.txtBlock)
        val stats: TextView = v.findViewById(R.id.txtStats)
    }
}

class IncomingFragment : Fragment(R.layout.fragment_incoming) {
    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.recyclerViewIncoming)
        val adapter = IncomingAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext().applicationContext, AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getAllReports().collectLatest { adapter.submitList(it) } }
    }
}

class SummaryFragment : Fragment(R.layout.fragment_summary) {
    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.recyclerViewSummary)
        val adapter = SummaryAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext().applicationContext, AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getBlockSummaries().collectLatest { adapter.submitList(it) } }
    }
}

class NodesFragment : Fragment(R.layout.fragment_nodes) {
    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.recyclerViewNodes)
        val adapter = NodeAdapter { addr -> showQrDialog(addr) }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        
        // Use viewLifecycleOwner to avoid crashes during fragment transition
        viewLifecycleOwner.lifecycleScope.launch {
            RNSReceiverService.localAddress.collectLatest { adapter.setLocalAddress(it) }
        }

        val db = Room.databaseBuilder(requireContext().applicationContext, AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch {
            db.harvestDao().getAllNodes().collectLatest { adapter.setDiscoveredNodes(it) }
        }
    }

    private fun showQrDialog(addr: String) {
        val qrBitmap = generateQr(addr)
        if (qrBitmap != null) {
            val iv = ImageView(context).apply {
                setPadding(40, 40, 40, 40)
                setImageBitmap(qrBitmap)
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Receiver QR")
                .setMessage("Scan this to add destination")
                .setView(iv)
                .setPositiveButton("Close", null)
                .show()
        } else {
            Toast.makeText(context, "Could not generate QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQr(text: String): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) { null }
    }
}