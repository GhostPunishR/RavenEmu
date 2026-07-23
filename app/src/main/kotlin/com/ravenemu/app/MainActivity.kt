package com.ravenemu.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.ravenemu.app.emulation.EmulationActivity
import com.ravenemu.app.library.RomAdapter
import com.ravenemu.app.settings.SettingsActivity
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.romlibrary.RomIndex
import com.ravenemu.romlibrary.RomStatus
import com.ravenemu.settings.AppSettings
import com.ravenemu.storage.CoverResolver
import com.ravenemu.storage.LibraryRepository
import com.ravenemu.storage.ReferenceDatabaseStore
import kotlinx.coroutines.launch

/**
 * Écran d'accueil : bibliothèque visuelle des jeux détectés dans les dossiers
 * choisis par l'utilisateur (SAF), avec recherche, tri, filtrage, badges de
 * statut et actualisation manuelle.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var repository: LibraryRepository
    private lateinit var referenceStore: ReferenceDatabaseStore
    private lateinit var coverResolver: CoverResolver
    private lateinit var adapter: RomAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar

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
        // Base de références locale : semence embarquée + bases importées par
        // l'utilisateur (No-Intro / dataset JSON), uniquement des empreintes.
        referenceStore = ReferenceDatabaseStore(this)
        repository = LibraryRepository(this, referenceStore.load())
        coverResolver = CoverResolver(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        recycler = findViewById(R.id.romList)
        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)

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
        render()
        // Applique la base de références courante aux entrées déjà indexées
        // (leur statut a pu changer depuis un import).
        lifecycleScope.launch {
            index = repository.reclassify(index)
            render()
        }
        if (index.entries.isEmpty() && settings.romDirectories.isNotEmpty()) {
            refreshLibrary()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.showBadges = settings.showStatusBadges
        render()
        // Une base d'empreintes a pu être importée depuis les paramètres :
        // on la recharge et on reclasse la bibliothèque.
        lifecycleScope.launch {
            repository.setReferenceDatabase(referenceStore.load())
            index = repository.reclassify(index)
            render()
        }
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
            render()
            Toast.makeText(
                this@MainActivity,
                getString(R.string.library_refresh_done, index.entries.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun visibleEntries(): List<RomEntry> {
        var entries = index.entries
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.trim()
            entries = entries.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.fileName.contains(query, ignoreCase = true)
            }
        }
        return when (settings.librarySortOrder) {
            "size" -> entries.sortedByDescending { it.sizeBytes }
            "status" -> entries.sortedBy { it.effectiveStatus.ordinal }
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

    private fun showEntryOptions(entry: RomEntry) {
        val isHomebrew = entry.userStatusOverride == RomStatus.HOMEBREW
        val options = arrayOf(
            getString(R.string.library_rom_details),
            getString(R.string.library_choose_cover),
            getString(
                if (isHomebrew) R.string.library_unmark_homebrew
                else R.string.library_mark_homebrew
            ),
        )
        AlertDialog.Builder(this)
            .setTitle(entry.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDetails(entry)
                    1 -> {
                        coverTarget = entry
                        pickCover.launch(arrayOf("image/*"))
                    }
                    2 -> lifecycleScope.launch {
                        val override =
                            if (isHomebrew) null else RomStatus.HOMEBREW
                        index = repository.update(
                            index,
                            entry.copy(userStatusOverride = override),
                        )
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDetails(entry: RomEntry) {
        val details = buildString {
            appendLine(entry.fileName)
            appendLine("Console : ${entry.console.displayName}")
            appendLine("${entry.sizeBytes / 1024} Kio")
            if (entry.console == com.ravenemu.emulation.api.ConsoleType.GAME_BOY_ADVANCE) {
                if (entry.gameCode.isNotBlank()) appendLine("Code jeu : ${entry.gameCode}")
            } else {
                appendLine("Région : ${entry.region.displayName}")
                appendLine("MBC : ${entry.mbcType.displayName}")
                appendLine("Type cartouche : 0x%02X".format(entry.cartridgeTypeCode))
                appendLine("RAM : ${entry.ramSizeBytes} octets")
                appendLine("Pile : ${if (entry.hasBattery) "oui" else "non"}")
            }
            appendLine("Statut : ${entry.effectiveStatus.displayName}")
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
