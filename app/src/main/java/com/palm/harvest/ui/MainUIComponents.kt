package com.palm.harvest.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import androidx.room.Room
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
        val title: TextView = v.findViewById(R.id.txtHarvester); val subtitle: TextView = v.findViewById(R.id.txtBlock); val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val i = getItem(p); val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        h.title.text = "Harvester: ${i.harvesterId}"; h.subtitle.text = "Block: ${i.blockId}"; h.stats.text = "Total: ${i.ripeBunches + i.emptyBunches} | Time: ${sdf.format(Date(i.timestamp * 1000))}"
    }
    class DiffCallback : DiffUtil.ItemCallback<HarvestReport>() {
        override fun areItemsTheSame(o: HarvestReport, n: HarvestReport) = o.id == n.id
        override fun areContentsTheSame(o: HarvestReport, n: HarvestReport) = o == n
    }
}

class SummaryAdapter : ListAdapter<BlockSummary, SummaryAdapter.ViewHolder>(SumDiffCallback()) {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester); val subtitle: TextView = v.findViewById(R.id.txtBlock); val stats: TextView = v.findViewById(R.id.txtStats)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val i = getItem(p); h.title.text = "Block: ${i.blockId}"; h.subtitle.text = "Total Harvest: ${i.totalBunches}"; h.stats.text = "Ripe: ${i.totalRipe} | Empty: ${i.totalEmpty}"
    }
    class SumDiffCallback : DiffUtil.ItemCallback<BlockSummary>() {
        override fun areItemsTheSame(o: BlockSummary, n: BlockSummary) = o.blockId == n.blockId
        override fun areContentsTheSame(o: BlockSummary, n: BlockSummary) = o == n
    }
}

class NodeAdapter(private val onLocalClick: (String) -> Unit) : ListAdapter<DiscoveredNode, RecyclerView.ViewHolder>(NodeDiffCallback()) {
    private var localAddr: String = ""
    fun setLocalAddress(addr: String) { localAddr = addr; notifyItemChanged(0) }

    override fun getItemViewType(p: Int) = if (p == 0) 0 else 1
    override fun getItemCount() = super.getItemCount() + 1

    override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false)
        return if (t == 0) LocalViewHolder(v) else DiscoveredViewHolder(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, p: Int) {
        if (holder is LocalViewHolder) {
            holder.title.text = "THIS RECEIVER (Tap for QR)"
            holder.subtitle.text = "Address: $localAddr"
            holder.stats.text = "Config Harvesters to this address"
            holder.itemView.setOnClickListener { onLocalClick(localAddr) }
        } else {
            val i = getItem(p - 1)
            val h = holder as DiscoveredViewHolder
            h.title.text = "Harvester: ${i.nickname}"
            h.subtitle.text = "Hash: ${i.hash}"
            h.stats.text = "Last Heard: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(i.lastHeard))}"
        }
    }

    class LocalViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester); val subtitle: TextView = v.findViewById(R.id.txtBlock); val stats: TextView = v.findViewById(R.id.txtStats)
    }
    class DiscoveredViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtHarvester); val subtitle: TextView = v.findViewById(R.id.txtBlock); val stats: TextView = v.findViewById(R.id.txtStats)
    }
    class NodeDiffCallback : DiffUtil.ItemCallback<DiscoveredNode>() {
        override fun areItemsTheSame(o: DiscoveredNode, n: DiscoveredNode) = o.hash == n.hash
        override fun areContentsTheSame(o: DiscoveredNode, n: DiscoveredNode) = o == n
    }
}

class IncomingFragment : Fragment(R.layout.fragment_incoming) {
    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.recyclerViewIncoming); val adapter = IncomingAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext(), AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getAllReports().collectLatest { adapter.submitList(it) } }
    }
}

class SummaryFragment : Fragment(R.layout.fragment_summary) {
    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.recyclerViewSummary); val adapter = SummaryAdapter()
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        val db = Room.databaseBuilder(requireContext(), AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getBlockSummaries().collectLatest { adapter.submitList(it) } }
    }
}

class NodesFragment : Fragment(R.layout.fragment_nodes) {
    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.recyclerViewNodes)
        val adapter = NodeAdapter { addr -> showQrDialog(addr) }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        
        lifecycleScope.launch { RNSReceiverService.localAddress.collectLatest { adapter.setLocalAddress(it) } }
        val db = Room.databaseBuilder(requireContext(), AppDatabase::class.java, "harvest-db").fallbackToDestructiveMigration().build()
        viewLifecycleOwner.lifecycleScope.launch { db.harvestDao().getAllNodes().collectLatest { adapter.submitList(it) } }
    }

    private fun showQrDialog(addr: String) {
        if (addr.isEmpty()) return
        val iv = ImageView(context).apply { 
            setPadding(50, 50, 50, 50)
            setImageBitmap(generateQr(addr))
        }
        AlertDialog.Builder(requireContext()).setTitle("Scan Receiver Address").setView(iv).setPositiveButton("Close", null).show()
    }

    private fun generateQr(text: String): Bitmap {
        val w = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) for (y in 0 until 512) b.setPixel(x, y, if (w[x, y]) Color.BLACK else Color.WHITE)
        return b
    }
}