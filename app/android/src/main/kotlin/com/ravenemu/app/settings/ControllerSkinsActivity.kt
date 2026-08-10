package com.ravenemu.app.settings

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ravenemu.app.BuildConfig
import com.ravenemu.app.R
import com.ravenemu.app.RavenActivity
import com.ravenemu.deltaskin.DeltaSkinConsole
import com.ravenemu.deltaskin.DeltaSkinErrorCode
import com.ravenemu.deltaskin.DeltaSkinException
import com.ravenemu.deltaskin.DeltaSkinInstallResult
import com.ravenemu.deltaskin.DeltaSkinInstalledSkin
import com.ravenemu.deltaskin.DeltaSkinPreparedImport
import com.ravenemu.deltaskin.DeltaSkinRepresentationPreference
import com.ravenemu.input.AndroidDeltaSkinPdfInspector
import com.ravenemu.input.DeltaSkinPdfRenderer
import com.ravenemu.settings.AppSettings
import com.ravenemu.storage.AndroidDeltaSkinArchiveImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ControllerSkinsActivity : RavenActivity(), ControllerSkinsAdapter.Listener {
    private lateinit var settings: AppSettings
    private lateinit var store: AndroidDeltaSkinArchiveImporter
    private lateinit var adapter: ControllerSkinsAdapter
    private lateinit var importButton: Button
    private lateinit var progress: ProgressBar
    private var pendingReplacement: DeltaSkinPreparedImport? = null

    private val openSkin =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSkin(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller_skins)
        settings = AppSettings(this)
        store = AndroidDeltaSkinArchiveImporter(this, AndroidDeltaSkinPdfInspector)
        importButton = findViewById(R.id.deltaSkinImport)
        progress = findViewById(R.id.deltaSkinProgress)

        adapter = ControllerSkinsAdapter(
            scope = lifecycleScope,
            settings = settings,
            listener = this,
        )
        findViewById<RecyclerView>(R.id.deltaSkinList).apply {
            layoutManager = LinearLayoutManager(this@ControllerSkinsActivity)
            adapter = this@ControllerSkinsActivity.adapter
        }
        configureModeSpinner(findViewById(R.id.deltaSkinMode))
        importButton.setOnClickListener { openSkin.launch(arrayOf("*/*")) }
        refresh()
    }

    override fun onDestroy() {
        pendingReplacement?.let { prepared ->
            runCatching { store.repository.discard(prepared) }
        }
        pendingReplacement = null
        super.onDestroy()
    }

    override fun onSelectClassic(console: DeltaSkinConsole) {
        settings.setDeltaSkinIdentifier(console, null)
        adapter.updateSelections(currentSelections())
    }

    override fun onSelectSkin(skin: DeltaSkinInstalledSkin) {
        selectImported(skin)
        adapter.updateSelections(currentSelections())
    }

    override fun onDeleteSkin(skin: DeltaSkinInstalledSkin) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delta_skins_delete_title)
            .setMessage(
                getString(
                    R.string.delta_skins_delete_message,
                    skin.metadata.manifest.name,
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delta_skins_delete) { _, _ ->
                deleteSkin(skin)
            }
            .show()
    }

    private fun configureModeSpinner(spinner: Spinner) {
        val labels = listOf(
            getString(R.string.delta_skins_mode_automatic),
            getString(R.string.delta_skins_mode_standard),
            getString(R.string.delta_skins_mode_edge_to_edge),
        )
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        spinner.setSelection(
            when (settings.deltaSkinRepresentationPreference) {
                DeltaSkinRepresentationPreference.AUTOMATIC -> 0
                DeltaSkinRepresentationPreference.STANDARD -> 1
                DeltaSkinRepresentationPreference.EDGE_TO_EDGE -> 2
            },
            false,
        )
        spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            settings.deltaSkinRepresentationPreference = when (position) {
                1 -> DeltaSkinRepresentationPreference.STANDARD
                2 -> DeltaSkinRepresentationPreference.EDGE_TO_EDGE
                else -> DeltaSkinRepresentationPreference.AUTOMATIC
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun importSkin(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            var prepared: DeltaSkinPreparedImport? = null
            try {
                val candidate = withContext(Dispatchers.IO) { store.prepare(uri) }
                prepared = candidate
                when (
                    val result = withContext(Dispatchers.IO) {
                        store.repository.install(candidate, replaceExisting = false)
                    }
                ) {
                    is DeltaSkinInstallResult.Installed -> {
                        selectImported(result.skin)
                        toast(R.string.delta_skins_imported)
                        refresh()
                    }
                    is DeltaSkinInstallResult.Duplicate -> {
                        selectImported(result.skin)
                        toast(R.string.delta_skins_duplicate)
                        refresh()
                    }
                    is DeltaSkinInstallResult.Conflict -> {
                        pendingReplacement = candidate
                        showReplacementConfirmation(candidate, result.existing)
                    }
                    is DeltaSkinInstallResult.Replaced -> Unit
                }
            } catch (error: CancellationException) {
                prepared?.let { runCatching { store.repository.discard(it) } }
                throw error
            } catch (error: DeltaSkinException) {
                prepared?.takeIf { it !== pendingReplacement }
                    ?.let { runCatching { store.repository.discard(it) } }
                showError(error)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showReplacementConfirmation(
        prepared: DeltaSkinPreparedImport,
        existing: DeltaSkinInstalledSkin?,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delta_skins_replace_title)
            .setMessage(
                getString(
                    R.string.delta_skins_replace_message,
                    existing?.metadata?.manifest?.identifier
                        ?: prepared.metadata.manifest.identifier,
                )
            )
            .setNegativeButton(R.string.cancel) { _, _ ->
                discardPending(prepared)
            }
            .setOnCancelListener { discardPending(prepared) }
            .setPositiveButton(R.string.ok) { _, _ ->
                replaceSkin(prepared)
            }
            .show()
    }

    private fun replaceSkin(prepared: DeltaSkinPreparedImport) {
        pendingReplacement = null
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    store.repository.install(prepared, replaceExisting = true)
                }
                val replacement = result as? DeltaSkinInstallResult.Replaced
                    ?: throw DeltaSkinException(
                        DeltaSkinErrorCode.STORAGE_ERROR,
                        "Résultat de remplacement inattendu",
                    )
                DeltaSkinPdfRenderer.invalidate(replacement.previousArchiveSha256)
                DeltaSkinPdfRenderer.invalidate(replacement.skin.metadata.archiveSha256)
                selectImported(replacement.skin)
                toast(R.string.delta_skins_replaced)
                refresh()
            } catch (error: DeltaSkinException) {
                runCatching { store.repository.discard(prepared) }
                showError(error)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun deleteSkin(skin: DeltaSkinInstalledSkin) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.repository.delete(skin.metadata.manifest.identifier)
                }
                clearSelectionsForIdentifier(skin.metadata.manifest.identifier)
                DeltaSkinPdfRenderer.invalidate(skin.metadata.archiveSha256)
                toast(R.string.delta_skins_deleted)
                refresh()
            } catch (error: DeltaSkinException) {
                showError(error)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun discardPending(prepared: DeltaSkinPreparedImport) {
        if (pendingReplacement === prepared) pendingReplacement = null
        setBusy(false)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.repository.discard(prepared) }
        }
    }

    private fun selectImported(skin: DeltaSkinInstalledSkin) {
        clearSelectionsForIdentifier(
            identifier = skin.metadata.manifest.identifier,
            except = skin.metadata.console,
        )
        settings.setDeltaSkinIdentifier(
            skin.metadata.console,
            skin.metadata.manifest.identifier,
        )
    }

    private fun clearSelectionsForIdentifier(
        identifier: String,
        except: DeltaSkinConsole? = null,
    ) {
        DeltaSkinConsole.entries
            .filter { it != except && settings.deltaSkinIdentifier(it) == identifier }
            .forEach { settings.setDeltaSkinIdentifier(it, null) }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val skins = withContext(Dispatchers.IO) { store.repository.list() }
            DeltaSkinConsole.entries.forEach { console ->
                val selected = settings.deltaSkinIdentifier(console) ?: return@forEach
                if (
                    skins.none {
                        it.metadata.console == console &&
                            it.metadata.manifest.identifier == selected
                    }
                ) {
                    settings.setDeltaSkinIdentifier(console, null)
                }
            }
            adapter.submit(skins, currentSelections())
        }
    }

    private fun currentSelections(): Map<DeltaSkinConsole, String?> =
        DeltaSkinConsole.entries.associateWith(settings::deltaSkinIdentifier)

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy && pendingReplacement == null
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(error: DeltaSkinException) {
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Échec DeltaSkin code=${error.code}, cause=${error.cause?.javaClass?.simpleName}",
            )
        }
        Toast.makeText(this, errorMessage(error.code), Toast.LENGTH_LONG).show()
    }

    private fun errorMessage(code: DeltaSkinErrorCode): Int = when (code) {
        DeltaSkinErrorCode.MANIFEST_MISSING ->
            R.string.delta_skins_error_manifest_missing
        DeltaSkinErrorCode.MANIFEST_DUPLICATE ->
            R.string.delta_skins_error_manifest_duplicate
        DeltaSkinErrorCode.UNSUPPORTED_CONSOLE ->
            R.string.delta_skins_error_console
        DeltaSkinErrorCode.PORTRAIT_REPRESENTATION_MISSING ->
            R.string.delta_skins_error_portrait
        DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING ->
            R.string.delta_skins_error_asset
        DeltaSkinErrorCode.PDF_INVALID,
        DeltaSkinErrorCode.PDF_MULTIPLE_PAGES ->
            R.string.delta_skins_error_pdf
        DeltaSkinErrorCode.ARCHIVE_TOO_LARGE,
        DeltaSkinErrorCode.TOO_MANY_ENTRIES,
        DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE,
        DeltaSkinErrorCode.ENTRY_TOO_LARGE,
        DeltaSkinErrorCode.MANIFEST_TOO_LARGE ->
            R.string.delta_skins_error_size
        DeltaSkinErrorCode.IDENTIFIER_ALREADY_INSTALLED ->
            R.string.delta_skins_error_identifier
        DeltaSkinErrorCode.STORAGE_INSUFFICIENT,
        DeltaSkinErrorCode.STORAGE_ERROR ->
            R.string.delta_skins_error_storage
        DeltaSkinErrorCode.INVALID_EXTENSION,
        DeltaSkinErrorCode.NOT_A_ZIP,
        DeltaSkinErrorCode.INVALID_ARCHIVE,
        DeltaSkinErrorCode.UNSAFE_ARCHIVE_PATH,
        DeltaSkinErrorCode.SYMBOLIC_LINK,
        DeltaSkinErrorCode.NESTED_ARCHIVE,
        DeltaSkinErrorCode.MALFORMED_JSON,
        DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY,
        DeltaSkinErrorCode.INVALID_MAPPING_SIZE,
        DeltaSkinErrorCode.INVALID_FRAME,
        DeltaSkinErrorCode.INVALID_NUMBER ->
            R.string.delta_skins_error_archive
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "RavenEmuDeltaSkin"
    }
}
