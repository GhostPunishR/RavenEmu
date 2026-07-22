package com.ravenemu.app.library

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import com.ravenemu.app.R
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.romlibrary.RomStatus

/**
 * Adaptateur de la bibliothèque, en vue grille ou liste. Les pochettes sont
 * résolues localement en amont ([coverUriProvider]) et décodées à la volée ;
 * à défaut, une jaquette est générée à partir du titre.
 */
class RomAdapter(
    private val onClick: (RomEntry) -> Unit,
    private val onLongClick: (RomEntry) -> Unit,
    private val coverUriProvider: (RomEntry) -> Uri?,
    var showBadges: Boolean = true,
    var gridMode: Boolean = true,
) : RecyclerView.Adapter<RomAdapter.Holder>() {

    private val items = mutableListOf<RomEntry>()

    @Suppress("NotifyDataSetChanged")
    fun submit(entries: List<RomEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (gridMode) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == 0) R.layout.item_rom_grid else R.layout.item_rom_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val badge: TextView = view.findViewById(R.id.badge)

        fun bind(entry: RomEntry) {
            title.text = entry.displayName
            val sizeKib = entry.sizeBytes / 1024
            subtitle.text = itemView.context.getString(
                R.string.library_size_kib,
                sizeKib,
            ) + " · " + entry.mbcType.displayName + " · " + entry.region.displayName

            val coverUri = coverUriProvider(entry)
            var loaded = false
            if (coverUri != null) {
                try {
                    itemView.context.contentResolver.openInputStream(coverUri)?.use {
                        val bitmap = BitmapFactory.decodeStream(it)
                        if (bitmap != null) {
                            cover.setImageBitmap(bitmap)
                            loaded = true
                        }
                    }
                } catch (_: Exception) {
                    loaded = false
                }
            }
            if (!loaded) {
                cover.setImageDrawable(
                    CoverArtGenerator.generate(entry.displayName)
                        .toDrawable(itemView.resources)
                )
            }

            if (showBadges) {
                badge.visibility = View.VISIBLE
                val (label, color) = when (entry.effectiveStatus) {
                    RomStatus.VERIFIED_OFFICIAL ->
                        R.string.status_verified to R.color.badge_verified
                    RomStatus.KNOWN_HACK -> R.string.status_hack to R.color.badge_hack
                    RomStatus.MODIFIED_OR_UNRECOGNIZED ->
                        R.string.status_modified to R.color.badge_modified
                    RomStatus.UNKNOWN -> R.string.status_unknown to R.color.badge_unknown
                    RomStatus.HOMEBREW -> R.string.status_homebrew to R.color.badge_homebrew
                }
                badge.setText(label)
                badge.setBackgroundColor(itemView.context.getColor(color))
            } else {
                badge.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(items[bindingAdapterPosition]) }
            itemView.setOnLongClickListener {
                onLongClick(items[bindingAdapterPosition])
                true
            }
        }
    }
}
