package com.ravenemu.app.library

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ravenemu.app.R
import com.ravenemu.storage.SnapshotInfo
import com.ravenemu.storage.SnapshotStore

/**
 * Liste des états enregistrés d'un jeu.
 *
 * L'emplacement automatique est nommé par sa fonction plutôt que par son
 * numéro : il n'est pas choisi par le joueur, il sert à reprendre une partie
 * interrompue par le système. Le confondre avec un emplacement manuel
 * conduirait à l'écraser sans le vouloir.
 */
class SnapshotAdapter(
    private val onResume: (SnapshotInfo) -> Unit,
    private val onDelete: (SnapshotInfo) -> Unit,
) : RecyclerView.Adapter<SnapshotAdapter.Holder>() {

    private val items = mutableListOf<SnapshotInfo>()

    @Suppress("NotifyDataSetChanged")
    fun submit(states: List<SnapshotInfo>) {
        items.clear()
        items.addAll(states)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_snapshot, parent, false)
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val slotLabel: TextView = view.findViewById(R.id.slotLabel)
        private val slotDetails: TextView = view.findViewById(R.id.slotDetails)
        private val delete: TextView = view.findViewById(R.id.deleteState)

        fun bind(info: SnapshotInfo) {
            val context = itemView.context
            slotLabel.text = if (info.slot == SnapshotStore.AUTO_SLOT) {
                context.getString(R.string.states_slot_auto)
            } else {
                context.getString(R.string.states_slot, info.slot)
            }
            val date = DateUtils.getRelativeDateTimeString(
                context,
                info.savedAt,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.WEEK_IN_MILLIS,
                0,
            )
            val taille = context.getString(R.string.states_size_kib, info.sizeBytes / 1024)
            slotDetails.text = "$date · $taille"

            itemView.setOnClickListener { onResume(items[bindingAdapterPosition]) }
            delete.setOnClickListener { onDelete(items[bindingAdapterPosition]) }
        }
    }
}
