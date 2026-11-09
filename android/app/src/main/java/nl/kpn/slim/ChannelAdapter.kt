package nl.kpn.slim

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Channel list with a "Settings" entry as the first row. Adapter position = channel index + 1;
 * everything exposed to callers ([playingIndex], [onPick]) uses plain channel indices.
 */
class ChannelAdapter(
    private val channels: List<Channel>,
    private val logos: LogoLoader,
    private val epg: Epg,
    private val showLogos: () -> Boolean,
    private val onSettings: () -> Unit,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var playingIndex = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old + HEADER)
            if (value >= 0) notifyItemChanged(value + HEADER)
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemViewType(position: Int) = if (position < HEADER) TYPE_HEADER else TYPE_CHANNEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_settings, parent, false))
        } else {
            VH(inflater.inflate(R.layout.item_channel, parent, false))
        }
    }

    override fun getItemCount() = channels.size + HEADER

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderVH) {
            holder.itemView.setOnClickListener { onSettings() }
            return
        }
        val h = holder as VH
        val index = position - HEADER
        val ch = channels[index]
        h.number.text = ch.number.toString()
        h.name.text = ch.name
        h.now.text = epg.nowLine(ch.id).orEmpty()
        h.itemView.isActivated = index == playingIndex
        logos.load(ch.logoUrl.takeIf { showLogos() }, h.logo)
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition - HEADER
            if (i >= 0) onPick(i)
        }
    }

    companion object {
        const val HEADER = 1
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }
}
