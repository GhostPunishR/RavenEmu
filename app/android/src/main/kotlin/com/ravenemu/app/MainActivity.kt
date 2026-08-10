package com.ravenemu.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ravenemu.app.emulation.EmulationActivity
import com.ravenemu.app.library.ConsolePagerAdapter
import com.ravenemu.app.library.CoverLoader
import com.ravenemu.app.library.PageIndicator
import com.ravenemu.app.library.SnapshotsActivity
import com.ravenemu.app.settings.SettingsActivity
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.romlibrary.LibraryFilter
import com.ravenemu.romlibrary.LibraryPages
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.romlibrary.RomIndex
import com.ravenemu.settings.AppSettings
import com.ravenemu.storage.CoverResolver
import com.ravenemu.app.emulation.RavenConsoles
import com.ravenemu.storage.LibraryRepository
import kotlinx.coroutines.launch

/**
 * Écran d'accueil : bibliothèque des jeux détectés dans les dossiers choisis
 * par l'utilisateur (SAF).
 *
 * La console n'est plus un filtre caché dans un menu mais la page que l'on
 * feuillette, et son nom est le titre de l'écran. Le reste de la surface
 * revient aux jaquettes : la recherche a son champ, les deux gestes courants
 * leurs coins de barre, et tout ce qui s'utilise rarement le menu débordant.
 */
class MainActivity : RavenActivity() {

    // Le header distribue lui-même le cutout pour occuper le bord supérieur.
    override val applyDisplayCutoutInsetsToContent: Boolean = false

    private lateinit var settings: AppSettings
    private lateinit var repository: LibraryRepository
    private lateinit var coverResolver: CoverResolver
    private lateinit var covers: CoverLoader
    private lateinit var pagerAdapter: ConsolePagerAdapter
    private lateinit var pager: ViewPager2
    private lateinit var indicator: PageIndicator
    private lateinit var searchField: EditText
    private lateinit var progress: ProgressBar

    private var index: RomIndex = RomIndex()
    private var searchQuery: String = ""

    /** Filtres des pages affichées, dans l'ordre où on les feuillette. */
    private var pages: List<String> = emptyList()

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
                    // La pochette a changé : la recherche précédente, y compris
                    // son résultat négatif, ne vaut plus.
                    covers.invalidate(target)
                    render()
                }
            }
            coverTarget = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLibraryCutoutLayout()
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)
        repository = LibraryRepository(this, RavenConsoles.romAnalyzers())
        coverResolver = CoverResolver(this)
        covers = CoverLoader(
            context = this,
            resolver = coverResolver,
            scope = lifecycleScope,
            coversDirectory = { settings.coversDirectory },
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        applyLibraryInsets(toolbar)
        setSupportActionBar(toolbar)
        // Les réglages quittent le menu débordant pour le coin gauche : c'est
        // l'autre destination fréquente de cet écran, à égalité avec l'ajout
        // d'un dossier qui occupe le coin droit.
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_preferences)
        toolbar.setNavigationContentDescription(R.string.settings_title)
        toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        progress = findViewById(R.id.progress)
        pager = findViewById(R.id.consolePager)
        indicator = findViewById(R.id.pageIndicator)
        searchField = findViewById(R.id.searchField)

        pagerAdapter = ConsolePagerAdapter(
            covers = covers,
            onClick = ::launchGame,
            onLongClick = ::showEntryOptions,
            spanCount = ::gridSpanCount,
            entriesFor = ::visibleEntries,
            emptyMessage = ::emptyMessage,
        )
        pager.adapter = pagerAdapter
        searchField.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty()
            pagerAdapter.refresh()
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                indicator.setCurrent(position)
                // La page ouverte est retrouvée au prochain démarrage : c'est
                // le même réglage qu'utilisait le filtre par console.
                pagerAdapter.pageAt(position)?.let { settings.libraryConsoleFilter = it }
                updateTitle()
            }
        })

        index = repository.loadIndex()
        render()
        if (index.entries.isEmpty() && settings.romDirectories.isNotEmpty()) {
            refreshLibrary()
        }
    }

    /** Étend le fond du header dans les cutouts des bords courts. */
    private fun enableLibraryCutoutLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * La rangée de la toolbar reste compacte ; seule la zone physique du
     * cutout est ajoutée au-dessus. Les barres système, masquées, ne participent
     * jamais à ce calcul.
     */
    private fun applyLibraryInsets(toolbar: MaterialToolbar) {
        val root = findViewById<View>(R.id.libraryRoot)
        val content = findViewById<View>(R.id.libraryContent)
        val toolbarHeight = toolbar.layoutParams.height
        val toolbarPaddingLeft = toolbar.paddingLeft
        val toolbarPaddingTop = toolbar.paddingTop
        val toolbarPaddingRight = toolbar.paddingRight
        val toolbarPaddingBottom = toolbar.paddingBottom
        val contentPaddingLeft = content.paddingLeft
        val contentPaddingTop = content.paddingTop
        val contentPaddingRight = content.paddingRight
        val contentPaddingBottom = content.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            toolbar.setPadding(
                toolbarPaddingLeft + cutout.left,
                toolbarPaddingTop + cutout.top,
                toolbarPaddingRight + cutout.right,
                toolbarPaddingBottom,
            )
            val expectedToolbarHeight = toolbarHeight + cutout.top
            if (toolbar.layoutParams.height != expectedToolbarHeight) {
                toolbar.layoutParams = toolbar.layoutParams.apply {
                    height = expectedToolbarHeight
                }
            }
            content.setPadding(
                contentPaddingLeft + cutout.left,
                contentPaddingTop,
                contentPaddingRight + cutout.right,
                contentPaddingBottom + cutout.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /**
     * Colonnes de la grille. Une vignette vise environ 100 dp : c'est la
     * largeur à laquelle une jaquette reste reconnaissable et son titre
     * lisible sur trois lignes.
     */
    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 100).coerceIn(3, 8)
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
            render()
            Toast.makeText(
                this@MainActivity,
                getString(R.string.library_refresh_done, index.entries.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Contenu d'une page : les entrées de sa console, cherchées et triées. */
    private fun visibleEntries(filter: String): List<RomEntry> {
        var entries = LibraryFilter.apply(index.entries, filter)
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

    /**
     * Message d'une page vide. « Aucun jeu », « aucun jeu de cette console » et
     * « aucun résultat » appellent trois gestes différents : les confondre
     * laisserait l'utilisateur devant une page blanche sans savoir quoi faire.
     */
    private fun emptyMessage(filter: String): CharSequence = when {
        index.entries.isEmpty() -> getString(R.string.library_empty)
        searchQuery.isNotBlank() -> getString(R.string.library_empty_search)
        else -> getString(R.string.library_empty_console, pageLabel(filter))
    }

    private fun render() {
        pagerAdapter.gridMode = settings.libraryViewMode == "grid"
        pagerAdapter.showBadges = settings.showStatusBadges

        val nouvelles = LibraryPages.forEntries(index.entries)
        if (nouvelles != pages) {
            // Une console apparaît ou disparaît : les pages sont rebâties, et
            // l'écran revient sur celle que l'utilisateur regardait — ou sur la
            // première si sa console n'a plus de jeux.
            pages = nouvelles
            pagerAdapter.setPages(nouvelles)
            val cible = LibraryPages.indexOf(nouvelles, settings.libraryConsoleFilter)
            pager.setCurrentItem(cible, false)
            indicator.setPages(nouvelles.size, cible)
        } else {
            pagerAdapter.refresh()
        }
        updateTitle()
    }

    /** Le titre de l'écran est le nom de la console feuilletée. */
    private fun updateTitle() {
        val filtre = pagerAdapter.pageAt(pager.currentItem) ?: LibraryFilter.ALL
        supportActionBar?.title = pageLabel(filtre)
    }

    private fun pageLabel(filter: String): String = when (filter) {
        LibraryFilter.ALL -> getString(R.string.library_page_all)
        LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES -> getString(R.string.library_page_gb)
        LibraryFilter.GAME_BOY_COLOR_CARTRIDGES -> getString(R.string.library_page_gbc)
        else -> getString(R.string.library_page_gba)
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
        // L'horloge de cartouche non plus : elle est propre au Game Boy Advance.
        action(
            R.id.actionRtc,
            visible = entry.console == ConsoleType.GAME_BOY_ADVANCE,
        ) { showRtcDialog(entry) }
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
            // Le nom affiché entre dans la clé du cache de pochettes : une
            // entrée renommée dont la jaquette est générée doit en obtenir une
            // nouvelle, portant le nouveau nom.
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
                    // Retirer le dernier jeu d'une console retire sa page :
                    // `render` recalcule les pages avant de pousser le contenu.
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

    /** Libellé de l'horloge de cartouche : choix utilisateur ou détection. */
    private fun rtcLabel(entry: RomEntry): String {
        val forced = settings.forcedRtc(entry.fingerprints.sha256)
            ?: return getString(R.string.library_rtc_auto)
        val state = getString(
            if (forced) R.string.library_rtc_present else R.string.library_rtc_absent
        )
        return getString(R.string.library_rtc_forced, state)
    }

    /**
     * Choix de la présence de l'horloge de cartouche d'un jeu Game Boy Advance.
     *
     * La détection cherche la bibliothèque Seiko des cartouches d'origine et ne
     * peut rien affirmer d'une ROM modifiée : certaines pilotent l'horloge sans
     * porter cette signature, et annoncent alors que l'heure est illisible.
     */
    private fun showRtcDialog(entry: RomEntry) {
        val choices = listOf(null, true, false)
        val labels = arrayOf(
            getString(R.string.library_rtc_auto),
            getString(R.string.library_rtc_on),
            getString(R.string.library_rtc_off),
        )
        val checked = choices.indexOf(settings.forcedRtc(entry.fingerprints.sha256))

        AlertDialog.Builder(this)
            .setTitle(R.string.library_rtc)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.setForcedRtc(entry.fingerprints.sha256, choices[which])
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
                appendLine("Horloge : ${rtcLabel(entry)}")
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refreshLibrary()
            R.id.action_add_folder -> openRomFolder.launch(null)
            R.id.action_toggle_view -> {
                val grille = settings.libraryViewMode != "grid"
                settings.libraryViewMode = if (grille) "grid" else "list"
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
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
