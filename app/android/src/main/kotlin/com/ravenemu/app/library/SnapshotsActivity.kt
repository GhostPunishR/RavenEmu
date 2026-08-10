package com.ravenemu.app.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.ravenemu.app.R
import com.ravenemu.app.RavenActivity
import com.ravenemu.app.emulation.EmulationActivity
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.storage.SnapshotInfo
import com.ravenemu.storage.SnapshotStore

/**
 * États enregistrés d'un jeu : reprendre depuis un emplacement, ou en
 * supprimer un.
 *
 * Le moteur et [SnapshotStore] savaient déjà écrire et relire ces états, mais
 * rien ne les montrait : le joueur ne pouvait ni savoir ce qu'il avait
 * sauvegardé, ni choisir où reprendre.
 *
 * L'écran ne transporte que les champs dont [EmulationActivity] a besoin :
 * une entrée d'index n'est pas sérialisable dans un `Intent`, et la recopier
 * n'apporterait rien.
 */
class SnapshotsActivity : RavenActivity() {

    private lateinit var store: SnapshotStore
    private lateinit var adapter: SnapshotAdapter
    private lateinit var emptyView: TextView

    private lateinit var romUri: String
    private lateinit var romFileName: String
    private lateinit var romSha256: String
    private lateinit var romTitle: String
    private lateinit var romConsole: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snapshots)

        romUri = requireNotNull(intent.getStringExtra(EXTRA_URI))
        romFileName = requireNotNull(intent.getStringExtra(EXTRA_FILE_NAME))
        romSha256 = requireNotNull(intent.getStringExtra(EXTRA_SHA256))
        romTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        romConsole = requireNotNull(intent.getStringExtra(EXTRA_CONSOLE))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = romTitle
        toolbar.setNavigationOnClickListener { finish() }

        emptyView = findViewById(R.id.emptyView)
        store = SnapshotStore(this)
        adapter = SnapshotAdapter(onResume = ::resumeFrom, onDelete = ::confirmDelete)

        val list = findViewById<RecyclerView>(R.id.stateList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Les états changent pendant une partie : la liste se relit à chaque
        // retour sur l'écran plutôt que d'être figée à la construction.
        render()
    }

    private fun render() {
        val states = store.list(romSha256).sortedBy { it.slot }
        adapter.submit(states)
        emptyView.visibility = if (states.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun resumeFrom(info: SnapshotInfo) {
        startActivity(
            EmulationActivity.intent(
                context = this,
                uri = romUri,
                fileName = romFileName,
                sha256 = romSha256,
                title = romTitle,
                console = romConsole,
                resumeSlot = info.slot,
            )
        )
    }

    private fun confirmDelete(info: SnapshotInfo) {
        val label = if (info.slot == SnapshotStore.AUTO_SLOT) {
            getString(R.string.states_slot_auto)
        } else {
            getString(R.string.states_slot, info.slot)
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(R.string.states_delete_confirm)
            .setPositiveButton(R.string.states_delete) { _, _ ->
                store.delete(romSha256, info.slot)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_URI = "rom_uri"
        private const val EXTRA_FILE_NAME = "rom_file_name"
        private const val EXTRA_SHA256 = "rom_sha256"
        private const val EXTRA_TITLE = "rom_title"
        private const val EXTRA_CONSOLE = "rom_console"

        fun intent(context: Context, entry: RomEntry): Intent =
            Intent(context, SnapshotsActivity::class.java)
                .putExtra(EXTRA_URI, entry.uri)
                .putExtra(EXTRA_FILE_NAME, entry.fileName)
                .putExtra(EXTRA_SHA256, entry.fingerprints.sha256)
                .putExtra(EXTRA_TITLE, entry.displayName)
                .putExtra(EXTRA_CONSOLE, entry.console.name)
    }
}
