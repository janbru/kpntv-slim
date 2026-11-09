package nl.kpn.slim

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val channels: List<Channel>,
    private val logos: LogoLoader,
    private val epg: Epg,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var playingIndex = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = channels[position]
        h.number.text = ch.number.toString()
        h.name.text = ch.name
        h.now.text = epg.nowLine(ch.id).orEmpty()
        h.itemView.isActivated = position == playingIndex
        logos.load(ch.logoUrl, h.logo)
        h.itemView.setOnClickListener { onPick(h.bindingAdapterPosition) }
    }
}
