package com.ravenemu.app.settings

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.ravenemu.app.R
import com.ravenemu.deltaskin.DeltaSkinConsole
import com.ravenemu.deltaskin.DeltaSkinInstalledSkin
import com.ravenemu.deltaskin.DeltaSkinRepresentationSelector
import com.ravenemu.input.DeltaSkinPdfRenderer
import com.ravenemu.settings.AppSettings
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ControllerSkinsAdapter(
    private val scope: LifecycleCoroutineScope,
    private val settings: AppSettings,
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    interface Listener {
        fun onSelectClassic(console: DeltaSkinConsole)
        fun onSelectSkin(skin: DeltaSkinInstalledSkin)
        fun onDeleteSkin(skin: DeltaSkinInstalledSkin)
    }

    private sealed interface Row {
        data class Header(val console: DeltaSkinConsole) : Row
        data class Classic(val console: DeltaSkinConsole) : Row
        data class Installed(val skin: DeltaSkinInstalledSkin) : Row
    }

    private var rows: List<Row> = emptyList()
    private var selections: Map<DeltaSkinConsole, String?> = emptyMap()

    fun submit(
        skins: List<DeltaSkinInstalledSkin>,
        selected: Map<DeltaSkinConsole, String?>,
    ) {
        selections = selected
        rows = buildList {
            for (console in DeltaSkinConsole.entries) {
                add(Row.Header(console))
                add(Row.Classic(console))
                skins.filter { it.metadata.console == console }
                    .forEach { add(Row.Installed(it)) }
            }
        }
        notifyDataSetChanged()
    }

    fun updateSelections(selected: Map<DeltaSkinConsole, String?>) {
        selections = selected
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_SKIN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return HeaderHolder(view)
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_controller_skin, parent, false)
        return SkinHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.console)
            is Row.Classic -> (holder as SkinHolder).bindClassic(row.console)
            is Row.Installed -> (holder as SkinHolder).bindSkin(row.skin)
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SkinHolder) holder.previewJob?.cancel()
        super.onViewRecycled(holder)
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(android.R.id.text1)

        fun bind(console: DeltaSkinConsole) {
            title.text = itemView.context.getString(
                if (console == DeltaSkinConsole.GB_GBC) {
                    R.string.delta_skins_group_gb
                } else {
                    R.string.delta_skins_group_gba
                }
            )
            title.setTypeface(title.typeface, Typeface.BOLD)
            val horizontal = (20 * itemView.resources.displayMetrics.density).toInt()
            val vertical = (14 * itemView.resources.displayMetrics.density).toInt()
            title.setPadding(horizontal, vertical, horizontal, vertical / 2)
        }
    }

    private inner class SkinHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val preview: ImageView = view.findViewById(R.id.deltaSkinPreview)
        private val name: TextView = view.findViewById(R.id.deltaSkinName)
        private val identifier: TextView = view.findViewById(R.id.deltaSkinIdentifier)
        private val details: TextView = view.findViewById(R.id.deltaSkinDetails)
        private val select: Button = view.findViewById(R.id.deltaSkinSelect)
        private val delete: Button = view.findViewById(R.id.deltaSkinDelete)
        var previewJob: Job? = null
        private var boundHash: String? = null

        fun bindClassic(console: DeltaSkinConsole) {
            previewJob?.cancel()
            boundHash = null
            preview.visibility = View.GONE
            preview.setImageDrawable(null)
            name.setText(R.string.delta_skins_classic)
            identifier.text = if (console == DeltaSkinConsole.GB_GBC) {
                itemView.context.getString(R.string.delta_skins_group_gb)
            } else {
                itemView.context.getString(R.string.delta_skins_group_gba)
            }
            details.setText(R.string.delta_skins_classic_summary)
            delete.visibility = View.GONE
            val selected = selections[console] == null
            configureSelectButton(selected) { listener.onSelectClassic(console) }
        }

        fun bindSkin(skin: DeltaSkinInstalledSkin) {
            previewJob?.cancel()
            val metadata = skin.metadata
            boundHash = metadata.archiveSha256
            preview.visibility = View.VISIBLE
            preview.setImageDrawable(null)
            name.text = metadata.manifest.name
            identifier.text = metadata.manifest.identifier
            val standard = metadata.standardPortraitAsset != null
            val edge = metadata.edgeToEdgePortraitAsset != null
            val availability = itemView.context.getString(
                R.string.delta_skins_availability,
                yesNo(standard),
                yesNo(edge),
                formatBytes(skin.occupiedBytes),
            )
            details.text = if (metadata.ignoredInputs.isEmpty()) {
                availability
            } else {
                "$availability\n" + itemView.context.getString(
                    R.string.delta_skins_ignored_inputs,
                    metadata.ignoredInputs.joinToString(),
                )
            }
            delete.visibility = View.VISIBLE
            delete.setOnClickListener { listener.onDeleteSkin(skin) }
            val selected =
                selections[metadata.console] == metadata.manifest.identifier
            configureSelectButton(selected) { listener.onSelectSkin(skin) }
            loadPreview(skin)
        }

        private fun loadPreview(skin: DeltaSkinInstalledSkin) {
            val metadata = skin.metadata
            val iphone = metadata.manifest.representations.iphone ?: return
            val kinds = iphone.availablePortraitKinds()
            val kind = DeltaSkinRepresentationSelector.select(
                availableKinds = kinds,
                preference = settings.deltaSkinRepresentationPreference,
                availableWidth = 400.0,
                availableHeight = 800.0,
            ) ?: return
            val representation = iphone.portrait(kind) ?: return
            val file = skin.assetFile(kind) ?: return
            val targetWidth = 320
            val targetHeight =
                (targetWidth * representation.mappingSize.height / representation.mappingSize.width)
                    .toInt()
                    .coerceAtLeast(1)
            val expectedHash = metadata.archiveSha256
            previewJob = scope.launch {
                runCatching {
                    DeltaSkinPdfRenderer.render(
                        file = file,
                        skinSha256 = expectedHash,
                        kind = kind,
                        width = targetWidth,
                        height = targetHeight,
                        densityDpi = itemView.resources.displayMetrics.densityDpi,
                    )
                }.getOrNull()?.let { bitmap ->
                    if (boundHash == expectedHash) preview.setImageBitmap(bitmap)
                }
            }
        }

        private fun configureSelectButton(selected: Boolean, action: () -> Unit) {
            select.isEnabled = !selected
            select.setText(
                if (selected) R.string.delta_skins_selected else R.string.delta_skins_select
            )
            select.setOnClickListener { action() }
        }

        private fun yesNo(value: Boolean): String = itemView.context.getString(
            if (value) R.string.delta_skins_yes else R.string.delta_skins_no
        )
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1.0) {
            String.format(Locale.getDefault(), "%.1f Mio", mib)
        } else {
            String.format(Locale.getDefault(), "%.0f Kio", bytes / 1024.0)
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SKIN = 1
    }
}
