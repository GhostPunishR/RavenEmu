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
import com.ravenemu.emulation.api.ConsoleType
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

    private companion object {
        // Étiquettes courtes : le sous-titre d'une vignette reste lisible.
        const val CONSOLE_LABEL_GB = "GB"
        const val CONSOLE_LABEL_GBA = "GBA"
    }

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
            // La console est toujours indiquée ; les champs MBC et région sont
            // propres à la cartouche Game Boy et n'ont pas d'équivalent GBA.
            val details = if (entry.console == ConsoleType.GAME_BOY_ADVANCE) {
                CONSOLE_LABEL_GBA
            } else {
                CONSOLE_LABEL_GB + " · " + entry.mbcType.displayName +
                    " · " + entry.region.displayName
            }
            subtitle.text = itemView.context.getString(
                R.string.library_size_kib,
                sizeKib,
            ) + " · " + details

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
                val (label, color) = when (entry.status) {
                    RomStatus.INTACT -> R.string.status_intact to R.color.badge_intact
                    RomStatus.MODIFIED ->
                        R.string.status_modified to R.color.badge_modified
                    RomStatus.HEADER_ONLY ->
                        R.string.status_header_only to R.color.badge_header_only
                    RomStatus.INVALID_HEADER ->
                        R.string.status_invalid_header to R.color.badge_invalid_header
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
