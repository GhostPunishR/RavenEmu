package com.ravenemu.app.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ravenemu.app.R
import com.ravenemu.romlibrary.RomEntry

/**
 * Pages de la bibliothèque, une par console.
 *
 * Chaque page est une grille autonome, avec son propre adaptateur, mais toutes
 * partagent le même chargeur de pochettes : c'est lui qui porte le cache, et le
 * scinder par page ferait recharger les mêmes images en changeant de page.
 *
 * Pas de fragments ici. Une page est une grille et un message ; leur confier un
 * cycle de vie complet ajouterait de la machinerie sans rien apporter, et le
 * `ViewPager2` recycle déjà ces vues comme n'importe quelle liste.
 */
class ConsolePagerAdapter(
    private val covers: CoverLoader,
    private val onClick: (RomEntry) -> Unit,
    private val onLongClick: (RomEntry) -> Unit,
    /** Colonnes de la grille, calculées d'après la largeur de l'écran. */
    private val spanCount: () -> Int,
    /** Entrées d'une page, filtrées et triées par l'écran. */
    private val entriesFor: (String) -> List<RomEntry>,
    /** Message affiché quand la page est vide. */
    private val emptyMessage: (String) -> CharSequence,
) : RecyclerView.Adapter<ConsolePagerAdapter.PageHolder>() {

    /** Filtres des pages affichées, dans l'ordre. */
    private var pages: List<String> = emptyList()

    /**
     * Adaptateurs vivants, par filtre. Le pager ne détruit pas les pages
     * voisines : les retrouver permet de leur pousser un nouveau contenu sans
     * reconstruire la page ouverte, donc sans perdre sa position de défilement.
     */
    private val adaptateurs = HashMap<String, RomAdapter>()

    /** Pages actuellement liées à une vue, seules à pouvoir être rafraîchies. */
    private val liees = HashMap<String, PageHolder>()

    /** Vue liste plutôt que grille, réglage commun à toutes les pages. */
    var gridMode: Boolean = true

    /** Pastilles d'état, réglage commun à toutes les pages. */
    var showBadges: Boolean = true

    /** Filtre de la page à l'index donné, ou `null` hors limites. */
    fun pageAt(index: Int): String? = pages.getOrNull(index)

    /**
     * Fixe les pages. Le contenu n'est pas poussé ici : [refresh] s'en charge,
     * et les deux sont distincts parce que la liste des pages change rarement
     * là où le contenu change à chaque frappe de recherche.
     */
    fun setPages(nouvelles: List<String>) {
        if (nouvelles == pages) return
        pages = nouvelles
        adaptateurs.keys.retainAll(nouvelles.toSet())
        notifyDataSetChanged()
    }

    /** Repousse le contenu des pages actuellement à l'écran ou à côté. */
    fun refresh() {
        for ((filtre, holder) in liees) holder.update(filtre)
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val vue = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_console, parent, false)
        return PageHolder(vue)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val filtre = pages[position]
        liees[filtre] = holder
        holder.update(filtre)
    }

    override fun onViewRecycled(holder: PageHolder) {
        liees.values.remove(holder)
    }

    inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val liste: RecyclerView = view.findViewById(R.id.romList)
        private val vide: TextView = view.findViewById(R.id.emptyView)

        fun update(filtre: String) {
            val adaptateur = adaptateurs.getOrPut(filtre) {
                RomAdapter(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    covers = covers,
                    showBadges = showBadges,
                    gridMode = gridMode,
                )
            }
            adaptateur.gridMode = gridMode
            adaptateur.showBadges = showBadges

            appliquerDisposition()
            if (liste.adapter !== adaptateur) liste.adapter = adaptateur

            val entrees = entriesFor(filtre)
            adaptateur.submit(entrees)
            vide.text = emptyMessage(filtre)
            vide.visibility = if (entrees.isEmpty()) View.VISIBLE else View.GONE
        }

        /**
         * Ne remplace la disposition que si le mode a changé : en poser une
         * neuve à chaque rafraîchissement ramènerait la liste en haut à chaque
         * frappe de recherche.
         *
         * Le test porte sur le type exact, `GridLayoutManager` héritant de
         * `LinearLayoutManager` : un `is LinearLayoutManager` serait vrai pour
         * les deux et la grille ne redeviendrait jamais une liste.
         */
        private fun appliquerDisposition() {
            val actuelle = liste.layoutManager
            val estGrille = actuelle is GridLayoutManager
            if (gridMode) {
                if (actuelle == null || !estGrille) {
                    liste.layoutManager = GridLayoutManager(itemView.context, spanCount())
                } else if ((actuelle as GridLayoutManager).spanCount != spanCount()) {
                    // Rotation ou fenêtre redimensionnée : le nombre de
                    // colonnes suit la largeur, sans reconstruire la liste.
                    actuelle.spanCount = spanCount()
                }
            } else if (actuelle == null || estGrille) {
                liste.layoutManager = LinearLayoutManager(itemView.context)
            }
        }
    }
}
