package com.ravenemu.app.library

import android.graphics.drawable.GradientDrawable
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
 *
 * La grille ne montre plus que la jaquette et le titre. Les détails techniques
 * — taille, MBC, région — n'ont pas disparu : ils sont sur l'appui long, là où
 * on les cherche, et non sous chaque vignette où ils encombraient la page.
 */
class RomAdapter(
    private val onClick: (RomEntry) -> Unit,
    private val onLongClick: (RomEntry) -> Unit,
    private val covers: CoverLoader,
    showBadges: Boolean = true,
    gridMode: Boolean = true,
) : RecyclerView.Adapter<RomAdapter.Holder>() {

    private val items = mutableListOf<RomEntry>()

    /**
     * Grille ou liste. Le changement passe par un rebâtissage complet parce
     * qu'il change le type de vue de chaque élément : un simple `submit` ne
     * verrait aucune différence dans les données et laisserait à l'écran les
     * vues de l'ancien mode.
     */
    var gridMode: Boolean = gridMode
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Pastilles d'état. Leur bascule ne change que la liaison des vues. */
    var showBadges: Boolean = showBadges
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, items.size)
        }

    private companion object {
        // Étiquettes courtes : le sous-titre d'une ligne reste lisible.
        const val CONSOLE_LABEL_GB = "GB"
        const val CONSOLE_LABEL_GBA = "GBA"
        const val CONSOLE_LABEL_NDS = "NDS"

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
        private val statusDot: View = view.findViewById(R.id.statusDot)

        /** Absent de la grille, qui ne montre que la jaquette et le titre. */
        private val subtitle: TextView? = view.findViewById(R.id.subtitle)

        private var job: Job? = null
        private var key: String? = null

        fun cancel() {
            job?.cancel()
            job = null
            key = null
        }

        fun bind(entry: RomEntry) {
            title.text = entry.displayName
            subtitle?.text = detailsDe(entry)
            bindCover(entry)
            bindStatus(entry)

            itemView.setOnClickListener { onClick(items[bindingAdapterPosition]) }
            itemView.setOnLongClickListener {
                onLongClick(items[bindingAdapterPosition])
                true
            }
        }

        private fun detailsDe(entry: RomEntry): String {
            val sizeKib = entry.sizeBytes / 1024
            // La console est toujours indiquée ; les champs MBC et région sont
            // propres à la cartouche Game Boy et n'ont d'équivalent ni sur Game
            // Boy Advance ni sur Nintendo DS.
            val details = when (entry.console) {
                ConsoleType.GAME_BOY_ADVANCE -> CONSOLE_LABEL_GBA
                ConsoleType.NINTENDO_DS -> CONSOLE_LABEL_NDS
                ConsoleType.GAME_BOY ->
                    // Monochrome ou couleur : c'est l'en-tête de la cartouche
                    // qui le dit, pas l'extension du fichier.
                    (entry.cartridgeMode?.shortLabel ?: CONSOLE_LABEL_GB) +
                        " · " + entry.mbcType.displayName +
                        " · " + entry.region.displayName
            }
            return itemView.context.getString(R.string.library_size_kib, sizeKib) + " · " + details
        }

        /**
         * L'état ne se signale que lorsqu'il y a quelque chose à signaler.
         * Décorer chaque cartouche conforme d'une étiquette n'apprendrait rien
         * et couvrirait les jaquettes ; l'anomalie, elle, mérite un point.
         */
        private fun bindStatus(entry: RomEntry) {
            val couleur = when (entry.status) {
                RomStatus.INTACT -> null
                RomStatus.MODIFIED -> R.color.badge_modified
                RomStatus.HEADER_ONLY -> R.color.badge_header_only
                RomStatus.INVALID_HEADER -> R.color.badge_invalid_header
            }
            if (!showBadges || couleur == null) {
                statusDot.visibility = View.GONE
                return
            }
            statusDot.visibility = View.VISIBLE
            // La teinte ne passe pas par `backgroundTintList` : elle
            // recouvrirait le liseré sombre qui détache la pastille d'une
            // jaquette claire. Seul le remplissage change.
            (statusDot.background?.mutate() as? GradientDrawable)
                ?.setColor(itemView.context.getColor(couleur))
            statusDot.contentDescription = entry.status.displayName
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
                appliquer(prete)
                return
            }

            // La vignette est recyclée : effacer l'image précédente évite
            // qu'un autre jeu ne s'affiche le temps du chargement, et remettre
            // la couleur neutre évite qu'un titre garde la teinte du précédent.
            cover.setImageDrawable(null)
            // Le temps du chargement, la case reprend sa teinte d'attente : une
            // grille de trous noirs se lirait comme un défaut d'affichage.
            cover.setBackgroundResource(R.color.raven_surface)
            title.setTextColor(if (gridMode) CoverAccent.DEFAUT else neutre())
            job = covers.load(entry, cle, taille, taille) { cover ->
                // La vignette a pu être réaffectée entre-temps.
                if (key == cle) appliquer(cover)
            }
        }

        /**
         * Le titre emprunte sa couleur à la jaquette : sur fond noir, c'est ce
         * qui rattache le texte à l'image qu'il nomme. La liste garde en
         * revanche un titre neutre — elle sert à comparer des entrées, et une
         * colonne de couleurs différentes s'y lit mal.
         */
        private fun appliquer(cover: CoverLoader.Cover) {
            this.cover.setImageBitmap(cover.bitmap)
            // La case est au ratio 6:7, les jaquettes ne le sont pas : montrer
            // l'image entière laisse forcément du vide sur deux côtés. Ce vide
            // ne doit pas se voir. La teinte d'attente est donc retirée dès que
            // l'image est posée, et le fond de la page passe au travers : la
            // jaquette paraît alors seule, sans cadre ni débordement.
            this.cover.background = null
            title.setTextColor(if (gridMode) cover.accent else neutre())
        }

        private fun neutre(): Int = itemView.context.getColor(R.color.raven_text_primary)
    }
}
