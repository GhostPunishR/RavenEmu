package com.ravenemu.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.ravenemu.app.emulation.EmulationActivity
import com.ravenemu.app.library.RomAdapter
import com.ravenemu.app.library.SnapshotsActivity
import com.ravenemu.app.settings.SettingsActivity
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.romlibrary.LibraryFilter
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.romlibrary.RomIndex
import com.ravenemu.settings.AppSettings
import com.ravenemu.storage.CoverResolver
import com.ravenemu.app.emulation.RavenConsoles
import com.ravenemu.storage.LibraryRepository
import kotlinx.coroutines.launch

/**
 * Écran d'accueil : bibliothèque visuelle des jeux détectés dans les dossiers
 * choisis par l'utilisateur (SAF), avec recherche, tri, filtrage, badges de
 * statut et actualisation manuelle.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var repository: LibraryRepository
    private lateinit var coverResolver: CoverResolver
    private lateinit var adapter: RomAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var consoleTabs: TabLayout

    /**
     * Écouteur conservé en champ pour pouvoir être **retiré** pendant la
     * reconstruction de la barre : une sélection programmatique ne doit pas
     * passer pour un choix de l'utilisateur.
     */
    private val tabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            settings.libraryConsoleFilter = tab.tag as? String ?: LibraryFilter.ALL
            render()
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = Unit

        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    private var index: RomIndex = RomIndex()
    private var searchQuery: String = ""

    private val openRomFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                settings.romDirectories = settings.romDirectories + uri
                refreshLibrary()
            }
        }

    private var coverTarget: RomEntry? = null
    private val pickCover =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = coverTarget
            if (uri != null && target != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                lifecycleScope.launch {
                    index = repository.update(index, target.copy(coverUri = uri.toString()))
                    render()
                }
            }
            coverTarget = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)
        repository = LibraryRepository(this, RavenConsoles.romAnalyzers())
        coverResolver = CoverResolver(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        recycler = findViewById(R.id.romList)
        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)
        consoleTabs = findViewById(R.id.consoleTabs)

        adapter = RomAdapter(
            onClick = ::launchGame,
            onLongClick = ::showEntryOptions,
            coverUriProvider = { entry ->
                coverResolver.resolve(entry, null, settings.coversDirectory)
            },
            showBadges = settings.showStatusBadges,
            gridMode = settings.libraryViewMode == "grid",
        )
        recycler.adapter = adapter
        applyLayoutManager()

        index = repository.loadIndex()
        setupConsoleTabs()
        render()
        if (index.entries.isEmpty() && settings.romDirectories.isNotEmpty()) {
            refreshLibrary()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.showBadges = settings.showStatusBadges
        render()
    }

    /** Un onglet du sélecteur de console : le filtre qu'il applique, son nom. */
    private data class ConsoleTab(val filter: String, val labelRes: Int)

    /**
     * Onglets à afficher, **déduits du contenu réel de la bibliothèque**.
     *
     * Une barre figée montrerait « Game Boy Advance » à qui n'a que des jeux
     * Game Boy, et un onglet toujours vide invite à croire que le filtre est
     * cassé. « Tout » reste toujours présent : c'est le repli quand la
     * bibliothèque est vide.
     *
     * Aucune entrée ne peut échapper aux onglets : une cartouche Game Boy dont
     * le mode est inconnu n'est pas couleur, elle tombe donc dans l'onglet
     * monochrome.
     */
    private fun availableTabs(): List<ConsoleTab> {
        val candidats = listOf(
            ConsoleTab(LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES, R.string.library_tab_gb),
            ConsoleTab(LibraryFilter.GAME_BOY_COLOR_CARTRIDGES, R.string.library_tab_gbc),
            ConsoleTab(ConsoleType.GAME_BOY_ADVANCE.name, R.string.library_tab_gba),
        )
        return buildList {
            add(ConsoleTab(LibraryFilter.ALL, R.string.library_tab_all))
            addAll(candidats.filter { LibraryFilter.apply(index.entries, it.filter).isNotEmpty() })
        }
    }

    /**
     * (Re)construit la barre d'onglets et y restaure le filtre enregistré.
     *
     * L'écouteur est retiré le temps de la reconstruction : sans cela, la
     * sélection programmatique déclencherait un changement de filtre et
     * écraserait le réglage qu'on est en train de restaurer.
     */
    private fun setupConsoleTabs() {
        val tabs = availableTabs()
        consoleTabs.removeOnTabSelectedListener(tabListener)
        consoleTabs.removeAllTabs()
        tabs.forEach { onglet ->
            consoleTabs.addTab(
                consoleTabs.newTab().setText(onglet.labelRes).setTag(onglet.filter)
            )
        }
        val actif = LibraryFilter.normalize(settings.libraryConsoleFilter)
        val position = tabs.indexOfFirst { it.filter == actif }.takeIf { it >= 0 } ?: 0
        // Un filtre enregistré dont l'onglet a disparu — dernier jeu d'une
        // console retiré — retombe sur « Tout » plutôt que sur une liste vide.
        settings.libraryConsoleFilter = tabs[position].filter
        consoleTabs.getTabAt(position)?.select()
        consoleTabs.addOnTabSelectedListener(tabListener)
        // Un seul onglet ne sert à rien : la barre s'efface d'elle-même.
        consoleTabs.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
    }

    private fun applyLayoutManager() {
        recycler.layoutManager = if (adapter.gridMode) {
            GridLayoutManager(this, gridSpanCount())
        } else {
            LinearLayoutManager(this)
        }
    }

    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 140).coerceIn(2, 8)
    }

    private fun persistTreePermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private fun refreshLibrary() {
        val dirs = settings.romDirectories
        if (dirs.isEmpty()) {
            render()
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            index = repository.refresh(dirs)
            progress.visibility = View.GONE
            setupConsoleTabs()
            render()
            Toast.makeText(
                this@MainActivity,
                getString(R.string.library_refresh_done, index.entries.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun visibleEntries(): List<RomEntry> {
        var entries = LibraryFilter.apply(index.entries, settings.libraryConsoleFilter)
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.trim()
            entries = entries.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.fileName.contains(query, ignoreCase = true)
            }
        }
        return when (settings.librarySortOrder) {
            "size" -> entries.sortedByDescending { it.sizeBytes }
            "status" -> entries.sortedBy { it.status.ordinal }
            else -> entries.sortedBy { it.displayName.lowercase() }
        }
    }

    private fun render() {
        val entries = visibleEntries()
        adapter.submit(entries)
        emptyView.visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launchGame(entry: RomEntry) {
        startActivity(EmulationActivity.intent(this, entry))
    }

    /**
     * Actions d'un jeu, en feuille venant du bas.
     *
     * La liste d'items d'une boîte de dialogue ne rappelait pas de quel jeu il
     * s'agissait et mettait l'action destructrice au même rang que les autres.
     * La feuille nomme le jeu, sépare « retirer » du reste, et masque le type
     * de sauvegarde là où il n'a pas de sens.
     */
    private fun showEntryOptions(entry: RomEntry) {
        val vue = layoutInflater.inflate(R.layout.sheet_game_actions, null)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(vue)

        vue.findViewById<TextView>(R.id.sheetTitle).text = entry.displayName
        vue.findViewById<TextView>(R.id.sheetSubtitle).text = entry.platformLabel

        fun action(id: Int, visible: Boolean = true, onClick: () -> Unit) {
            val ligne = vue.findViewById<TextView>(id)
            if (!visible) {
                ligne.visibility = View.GONE
                return
            }
            ligne.setOnClickListener {
                sheet.dismiss()
                onClick()
            }
        }

        action(R.id.actionPlay) { launchGame(entry) }
        action(R.id.actionStates) { startActivity(SnapshotsActivity.intent(this, entry)) }
        action(R.id.actionRename) { showRenameDialog(entry) }
        action(R.id.actionCover) {
            coverTarget = entry
            pickCover.launch(arrayOf("image/*"))
        }
        // Le type de sauvegarde n'a de sens que pour la Game Boy Advance.
        action(
            R.id.actionSaveType,
            visible = entry.console == ConsoleType.GAME_BOY_ADVANCE,
        ) { showSaveTypeDialog(entry) }
        action(R.id.actionDetails) { showDetails(entry) }
        action(R.id.actionRemove) { confirmRemove(entry) }

        sheet.show()
    }

    /**
     * Renommage d'une entrée.
     *
     * Le titre lu dans la cartouche est souvent tronqué et en majuscules. Le
     * renommage ne touche jamais au fichier de ROM : il n'écrit que l'index.
     * Vider le champ rend le titre d'origine plutôt que d'afficher une ligne
     * vide.
     */
    private fun showRenameDialog(entry: RomEntry) {
        val champ = EditText(this).apply {
            setText(entry.customTitle ?: entry.displayName)
            hint = getString(R.string.library_rename_hint)
            setSingleLine()
            setSelection(text.length)
        }
        val conteneur = FrameLayout(this).apply {
            val marge = (24 * resources.displayMetrics.density).toInt()
            setPadding(marge, marge / 2, marge, 0)
            addView(champ)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_rename_title)
            .setView(conteneur)
            .setPositiveButton(R.string.ok) { _, _ ->
                applyRename(entry, champ.text.toString().trim())
            }
            .setNeutralButton(R.string.library_rename_reset) { _, _ ->
                applyRename(entry, "")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyRename(entry: RomEntry, nom: String) {
        lifecycleScope.launch {
            index = repository.update(
                index,
                entry.copy(customTitle = nom.ifBlank { null }),
            )
            render()
        }
    }

    /**
     * Retrait d'une entrée : l'index seul est modifié. Ni la ROM, ni les
     * sauvegardes de cartouche, ni les états ne sont touchés — c'est ce que
     * dit le message, parce que « retirer » se confond aisément avec
     * « supprimer ».
     */
    private fun confirmRemove(entry: RomEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.library_remove_title, entry.displayName))
            .setMessage(R.string.library_remove_message)
            .setPositiveButton(R.string.library_remove_confirm) { _, _ ->
                lifecycleScope.launch {
                    index = repository.remove(index, entry.uri)
                    setupConsoleTabs()
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Libellé du type de sauvegarde : choix utilisateur ou détection. */
    private fun saveTypeLabel(entry: RomEntry): String {
        val forced = settings.forcedSaveType(entry.fingerprints.sha256)
        val effective = forced ?: entry.saveType.ifBlank { GbaSaveType.NONE.name }
        val label = runCatching { GbaSaveType.valueOf(effective) }
            .getOrDefault(GbaSaveType.NONE)
            .displayName
        return if (forced != null) getString(R.string.library_save_type_forced, label) else label
    }

    /**
     * Choix du type de mémoire de sauvegarde d'un jeu Game Boy Advance : la
     * détection automatique ne peut pas trancher tous les cas.
     */
    private fun showSaveTypeDialog(entry: RomEntry) {
        val types = GbaSaveType.entries
        val labels = buildList {
            add(getString(R.string.library_save_type_auto))
            types.forEach { add(it.displayName) }
        }.toTypedArray()
        val current = settings.forcedSaveType(entry.fingerprints.sha256)
        val checked = if (current == null) 0 else types.indexOfFirst { it.name == current } + 1

        AlertDialog.Builder(this)
            .setTitle(R.string.library_save_type)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.setForcedSaveType(
                    entry.fingerprints.sha256,
                    if (which == 0) null else types[which - 1].name,
                )
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDetails(entry: RomEntry) {
        val details = buildString {
            appendLine(entry.fileName)
            appendLine("Console : ${entry.platformLabel}")
            appendLine("${entry.sizeBytes / 1024} Kio")
            if (entry.console == ConsoleType.GAME_BOY_ADVANCE) {
                if (entry.gameCode.isNotBlank()) appendLine("Code jeu : ${entry.gameCode}")
                appendLine("Sauvegarde : ${saveTypeLabel(entry)}")
                appendLine(
                    "En-tête : ${if (entry.headerChecksumValid) "valide" else "somme incorrecte"}"
                )
            } else {
                appendLine("Région : ${entry.region.displayName}")
                appendLine("MBC : ${entry.mbcType.displayName}")
                appendLine("Type cartouche : 0x%02X".format(entry.cartridgeTypeCode))
                appendLine("RAM : ${entry.ramSizeBytes} octets")
                appendLine("Pile : ${if (entry.hasBattery) "oui" else "non"}")
            }
            appendLine("Statut : ${entry.status.displayName}")
            appendLine("CRC32 : ${entry.fingerprints.crc32}")
            appendLine("SHA-1 : ${entry.fingerprints.sha1}")
            appendLine("SHA-256 : ${entry.fingerprints.sha256}")
        }
        AlertDialog.Builder(this)
            .setTitle(entry.displayName)
            .setMessage(details)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---- Menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.action_search)
        (searchItem.actionView as? SearchView)?.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText.orEmpty()
                    render()
                    return true
                }
            }
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refreshLibrary()
            R.id.action_add_folder -> openRomFolder.launch(null)
            R.id.action_toggle_view -> {
                adapter.gridMode = !adapter.gridMode
                settings.libraryViewMode = if (adapter.gridMode) "grid" else "list"
                applyLayoutManager()
                render()
            }
            R.id.action_sort_title -> {
                settings.librarySortOrder = "title"
                render()
            }
            R.id.action_sort_size -> {
                settings.librarySortOrder = "size"
                render()
            }
            R.id.action_sort_status -> {
                settings.librarySortOrder = "status"
                render()
            }
            R.id.action_settings ->
                startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
