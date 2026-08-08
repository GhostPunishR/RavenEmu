package com.ravenemu.app.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ravenemu.app.R
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.romlibrary.RomStatus
import kotlinx.coroutines.Job

/**
 * Adaptateur de la bibliothèque, en vue grille ou liste.
 *
 * Les pochettes ne sont plus résolues ni décodées ici : ce travail passait par
 * le fournisseur de documents et par le décodeur d'images, sur le fil
 * d'affichage, à chaque vignette entrant à l'écran. Il revient à
 * [CoverLoader], qui le mémorise et l'exécute en arrière-plan.
 */
class RomAdapter(
    private val onClick: (RomEntry) -> Unit,
    private val onLongClick: (RomEntry) -> Unit,
    private val covers: CoverLoader,
    var showBadges: Boolean = true,
    var gridMode: Boolean = true,
) : RecyclerView.Adapter<RomAdapter.Holder>() {

    private val items = mutableListOf<RomEntry>()

    private companion object {
        // Étiquettes courtes : le sous-titre d'une vignette reste lisible.
        const val CONSOLE_LABEL_GB = "GB"
        const val CONSOLE_LABEL_GBA = "GBA"

        /**
         * Taille de décodage visée, en pixels.
         *
         * La vignette n'a pas encore été mesurée au moment où on lance le
         * chargement ; viser une taille fixe généreuse évite de retarder
         * l'affichage d'une passe de mise en page, et la même clé de cache
         * sert alors à toutes les vignettes d'un même mode.
         */
        const val TAILLE_GRILLE_PX = 384
        const val TAILLE_LISTE_PX = 128
    }

    /**
     * Remplace le contenu en ne signalant que ce qui a changé.
     *
     * `notifyDataSetChanged` reliait toutes les vignettes visibles à chaque
     * frappe de recherche ou retour sur l'écran, ce qui relançait autant de
     * chargements de pochettes.
     */
    fun submit(entries: List<RomEntry>) {
        val diff = DiffUtil.calculateDiff(Difference(items.toList(), entries))
        items.clear()
        items.addAll(entries)
        diff.dispatchUpdatesTo(this)
    }

    /** Deux entrées désignent le même jeu si elles pointent le même fichier. */
    private class Difference(
        private val avant: List<RomEntry>,
        private val apres: List<RomEntry>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = avant.size

        override fun getNewListSize(): Int = apres.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            avant[oldItemPosition].uri == apres[newItemPosition].uri

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            avant[oldItemPosition] == apres[newItemPosition]
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

    /**
     * Une vignette qui quitte l'écran n'a plus besoin de son image : annuler
     * évite qu'un défilement rapide n'accumule une file de décodages dont les
     * résultats arriveraient trop tard, et dans la mauvaise vignette.
     */
    override fun onViewRecycled(holder: Holder) {
        holder.cancel()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val badge: TextView = view.findViewById(R.id.badge)

        private var job: Job? = null
        private var key: String? = null

        fun cancel() {
            job?.cancel()
            job = null
            key = null
        }

        fun bind(entry: RomEntry) {
            title.text = entry.displayName
            val sizeKib = entry.sizeBytes / 1024
            // La console est toujours indiquée ; les champs MBC et région sont
            // propres à la cartouche Game Boy et n'ont pas d'équivalent GBA.
            val details = if (entry.console == ConsoleType.GAME_BOY_ADVANCE) {
                CONSOLE_LABEL_GBA
            } else {
                // Monochrome ou couleur : c'est l'en-tête de la cartouche qui
                // le dit, pas l'extension du fichier.
                (entry.cartridgeMode?.shortLabel ?: CONSOLE_LABEL_GB) +
                    " · " + entry.mbcType.displayName +
                    " · " + entry.region.displayName
            }
            subtitle.text = itemView.context.getString(
                R.string.library_size_kib,
                sizeKib,
            ) + " · " + details

            bindCover(entry)

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

        private fun bindCover(entry: RomEntry) {
            cancel()
            val taille = if (gridMode) TAILLE_GRILLE_PX else TAILLE_LISTE_PX
            val cle = covers.key(entry, taille, taille)
            key = cle

            val prete = covers.cached(cle)
            if (prete != null) {
                // Chemin courant après le premier défilement : aucune
                // allocation, aucune entrée-sortie, rien d'asynchrone.
                cover.setImageBitmap(prete)
                return
            }

            // La vignette est recyclée : effacer l'image précédente évite
            // qu'un autre jeu ne s'affiche le temps du chargement.
            cover.setImageDrawable(null)
            job = covers.load(entry, cle, taille, taille) { bitmap ->
                // La vignette a pu être réaffectée entre-temps.
                if (key == cle) cover.setImageBitmap(bitmap)
            }
        }
    }
}
