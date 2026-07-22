package com.ravenemu.app.emulation

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ravenemu.app.R
import com.ravenemu.core.gb.GameBoyCore
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.SaveStateException
import com.ravenemu.input.ControlId
import com.ravenemu.input.ControlLayout
import com.ravenemu.input.GamepadMapper
import com.ravenemu.input.TouchControlsView
import com.ravenemu.renderer.EmulatorSurfaceView
import com.ravenemu.romlibrary.GameBoyRomAnalyzer
import com.ravenemu.romlibrary.ReferenceDatabase
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.settings.AppSettings
import com.ravenemu.emulation.api.display.MonochromeDisplayProfiles
import com.ravenemu.storage.LibraryRepository
import com.ravenemu.storage.SaveFileStore
import com.ravenemu.storage.SnapshotStore
import kotlinx.coroutines.launch

/**
 * Écran d'émulation : surface de rendu, commandes tactiles, menu de
 * l'émulateur, éditeur de disposition, manettes physiques, cycle de vie
 * Android (pause en arrière-plan, sauvegardes de secours).
 */
class EmulationActivity : AppCompatActivity(), EmulationSession.Callbacks {

    private lateinit var settings: AppSettings
    private lateinit var saveStore: SaveFileStore
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var surface: EmulatorSurfaceView
    private lateinit var controls: TouchControlsView
    private lateinit var performanceOverlay: TextView
    private lateinit var editorPanel: View

    private val gamepad = GamepadMapper()
    private var core: GameBoyCore? = null
    private var session: EmulationSession? = null

    private lateinit var romUri: Uri
    private lateinit var romFileName: String
    private lateinit var romSha256: String
    private var romTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulation)

        settings = AppSettings(this)
        saveStore = SaveFileStore(this)
        snapshotStore = SnapshotStore(this)

        romUri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_URI)))
        romFileName = requireNotNull(intent.getStringExtra(EXTRA_FILE_NAME))
        romSha256 = requireNotNull(intent.getStringExtra(EXTRA_SHA256))
        romTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        surface = findViewById(R.id.surface)
        controls = findViewById(R.id.controls)
        performanceOverlay = findViewById(R.id.performanceOverlay)
        editorPanel = findViewById(R.id.editorPanel)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // L'image est ancrée en haut en portrait : on la décale sous
        // l'encoche ou la caméra perforée.
        ViewCompat.setOnApplyWindowInsetsListener(surface) { _, insets ->
            surface.topInsetPx =
                insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            insets
        }
        applyImmersiveMode()
        applyVideoSettings()
        applyControlLayout()
        bindControls()
        bindEditor()

        loadRomAndStart()
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applyVideoSettings() {
        surface.keepAspectRatio = settings.keepAspectRatio
        surface.integerScaling = settings.integerScaling
        // Portrait : écran de jeu en haut, commandes en dessous. Paysage :
        // l'image remplit la hauteur, le centrage reste naturel.
        surface.topAligned =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        // Profil d'écran monochrome appliqué par le renderer, à chaud : le
        // changement est visible immédiatement, sans redémarrer le jeu.
        surface.displayColors =
            MonochromeDisplayProfiles.byId(settings.screenProfileId).colors
        performanceOverlay.visibility =
            if (settings.showPerformanceOverlay) View.VISIBLE else View.GONE
    }

    // ---- Profils de commandes ----

    private fun orientationKey(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }

    private fun perGameProfileKey(): String = "${romSha256}_${orientationKey()}"

    private fun hasPerGameProfile(): Boolean =
        settings.controlLayout(perGameProfileKey()) != null

    private fun activeProfileKey(): String =
        if (hasPerGameProfile()) perGameProfileKey() else orientationKey()

    private fun defaultLayout(): ControlLayout =
        if (orientationKey() == "landscape") {
            ControlLayout.defaultLandscape()
        } else {
            ControlLayout.defaultPortrait()
        }

    private fun applyControlLayout() {
        val layout = settings.controlLayout(activeProfileKey()) ?: defaultLayout()
        controls.layoutSpec = layout.copy(hapticFeedback = settings.hapticFeedback)
    }

    private fun bindControls() {
        controls.listener = object : TouchControlsView.Listener {
            override fun onButton(button: EmulatorButton, pressed: Boolean) {
                session?.setButton(button, pressed)
            }

            override fun onMenu() {
                showEmulatorMenu()
            }
        }
    }

    // ---- Chargement ----

    private fun loadRomAndStart() {
        val repository = LibraryRepository(
            this,
            listOf(GameBoyRomAnalyzer(ReferenceDatabase.empty())),
        )
        lifecycleScope.launch {
            val data = repository.readRom(romUri)
            if (data == null) {
                Toast.makeText(
                    this@EmulationActivity,
                    R.string.emulation_rom_error,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }
            startEmulation(data)
        }
    }

    private fun startEmulation(rom: ByteArray) {
        val newCore = GameBoyCore()
        try {
            val battery = saveStore.read(romSha256, romFileName, settings.saveDirectory)
            newCore.loadRom(rom, battery)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.emulation_rom_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        surface.configure(newCore.video.width, newCore.video.height)

        val samplesPerFrame =
            (newCore.audio.sampleRateHz / newCore.video.refreshRateHz).toInt() + 1
        val audioSink = AndroidAudioSink(this, newCore.audio.sampleRateHz, samplesPerFrame)
        audioSink.setVolume(settings.audioVolume / 100f)

        val newSession = EmulationSession(newCore, this, audioSink)
        newSession.speedLimitEnabled = settings.speedLimitEnabled
        newSession.fastForwardMultiplier = settings.fastForwardMultiplier
        newSession.audioEnabled = settings.audioEnabled
        core = newCore
        session = newSession

        if (settings.autoResume) {
            snapshotStore.read(romSha256, SnapshotStore.AUTO_SLOT)?.let { state ->
                newSession.post { c ->
                    try {
                        c.loadState(state)
                    } catch (_: SaveStateException) {
                        // État d'une autre version : on démarre à froid.
                    }
                }
            }
        }
        newSession.start()
    }

    // ---- Callbacks de session (thread d'émulation) ----

    override fun onFrame(framebuffer: IntArray) {
        surface.presentFrame(framebuffer)
    }

    override fun onStats(fps: Double) {
        if (settings.showPerformanceOverlay) {
            runOnUiThread {
                performanceOverlay.text = "%.1f FPS".format(fps)
            }
        }
    }

    override fun onBatterySave(data: ByteArray) {
        saveStore.write(romSha256, romFileName, data, settings.saveDirectory)
    }

    // ---- Menu de l'émulateur ----

    private fun showEmulatorMenu() {
        val currentSession = session ?: return
        currentSession.pause()
        val fastForwardLabel = getString(R.string.emulation_fast_forward) +
            if (currentSession.fastForward) " ✓" else ""
        val perGameLabel = getString(
            if (hasPerGameProfile()) R.string.emulation_per_game_profile_off
            else R.string.emulation_per_game_profile_on
        )
        val items = arrayOf(
            getString(R.string.emulation_resume),
            getString(R.string.emulation_save_state),
            getString(R.string.emulation_load_state),
            fastForwardLabel,
            getString(R.string.emulation_reset),
            getString(R.string.emulation_edit_controls),
            perGameLabel,
            getString(R.string.emulation_quit),
        )
        AlertDialog.Builder(this)
            .setTitle(romTitle.ifBlank { romFileName })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> currentSession.resume()
                    1 -> saveSnapshot()
                    2 -> loadSnapshot()
                    3 -> {
                        currentSession.fastForward = !currentSession.fastForward
                        currentSession.resume()
                    }
                    4 -> {
                        currentSession.post { it.reset() }
                        currentSession.resume()
                    }
                    5 -> enterEditMode()
                    6 -> togglePerGameProfile()
                    7 -> finish()
                }
            }
            .setOnCancelListener { currentSession.resume() }
            .show()
    }

    private fun saveSnapshot() {
        val currentSession = session ?: return
        currentSession.post { c ->
            val state = c.saveState()
            snapshotStore.write(romSha256, USER_SLOT, state)
            runOnUiThread {
                Toast.makeText(this, R.string.emulation_state_saved, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        currentSession.resume()
    }

    private fun loadSnapshot() {
        val currentSession = session ?: return
        val state = snapshotStore.read(romSha256, USER_SLOT)
        if (state == null) {
            Toast.makeText(this, R.string.emulation_no_state, Toast.LENGTH_SHORT).show()
            currentSession.resume()
            return
        }
        currentSession.post { c ->
            try {
                c.loadState(state)
            } catch (_: SaveStateException) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        R.string.emulation_state_load_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        currentSession.resume()
    }

    private fun togglePerGameProfile() {
        if (hasPerGameProfile()) {
            settings.resetControlLayout(perGameProfileKey())
            Toast.makeText(this, R.string.emulation_profile_removed, Toast.LENGTH_SHORT)
                .show()
        } else {
            settings.saveControlLayout(perGameProfileKey(), controls.layoutSpec)
            Toast.makeText(this, R.string.emulation_profile_created, Toast.LENGTH_SHORT)
                .show()
        }
        applyControlLayout()
        session?.resume()
    }

    // ---- Éditeur de commandes ----

    private fun bindEditor() {
        val scaleBar = findViewById<SeekBar>(R.id.editorScale)
        val opacityBar = findViewById<SeekBar>(R.id.editorOpacity)
        val visibleBox = findViewById<CheckBox>(R.id.editorVisible)
        val resetButton = findViewById<Button>(R.id.editorReset)
        val doneButton = findViewById<Button>(R.id.editorDone)

        controls.onLayoutChanged = { layout ->
            settings.saveControlLayout(activeProfileKey(), layout)
            val selected = controls.selectedElement?.let(layout::element)
            if (selected != null) {
                scaleBar.progress = ((selected.scale - 0.5f) / 2f * 100).toInt()
                opacityBar.progress = (selected.opacity * 100).toInt()
                visibleBox.isChecked = selected.visible
            }
        }
        scaleBar.setOnSeekBarChangeListener(seekListener { value ->
            controls.adjustSelected(scale = 0.5f + value / 100f * 2f)
        })
        opacityBar.setOnSeekBarChangeListener(seekListener { value ->
            controls.adjustSelected(opacity = value / 100f)
        })
        visibleBox.setOnCheckedChangeListener { _, checked ->
            if (controls.editMode) controls.adjustSelected(visible = checked)
        }
        resetButton.setOnClickListener {
            controls.layoutSpec = defaultLayout()
            settings.resetControlLayout(activeProfileKey())
        }
        doneButton.setOnClickListener { exitEditMode() }
    }

    private fun seekListener(onValue: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) onValue(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }

    private fun enterEditMode() {
        session?.pause()
        controls.editMode = true
        editorPanel.visibility = View.VISIBLE
    }

    private fun exitEditMode() {
        controls.editMode = false
        editorPanel.visibility = View.GONE
        settings.saveControlLayout(activeProfileKey(), controls.layoutSpec)
        session?.resume()
    }

    // ---- Manette physique ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!controls.editMode) {
            gamepad.mapKeyEvent(event)?.let { (button, pressed) ->
                session?.setButton(button, pressed)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val changes = gamepad.mapMotionEvent(event)
        if (changes.isNotEmpty()) {
            for ((button, pressed) in changes) session?.setButton(button, pressed)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- Cycle de vie ----

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyVideoSettings()
        applyControlLayout()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        applyVideoSettings()
        session?.let { s ->
            s.audioEnabled = settings.audioEnabled
            s.setAudioVolume(settings.audioVolume / 100f)
        }
        if (!controls.editMode) session?.resume()
    }

    override fun onPause() {
        super.onPause()
        val currentSession = session ?: return
        currentSession.flushBattery()
        // Sauvegarde de secours avant une éventuelle interruption du processus.
        currentSession.post { c ->
            try {
                snapshotStore.write(romSha256, SnapshotStore.AUTO_SLOT, c.saveState())
            } catch (_: Exception) {
                // La sauvegarde de secours ne doit jamais faire échouer la pause.
            }
        }
        if (settings.pauseInBackground) currentSession.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.stop()
        session = null
        core = null
    }

    companion object {
        private const val EXTRA_URI = "rom_uri"
        private const val EXTRA_FILE_NAME = "rom_file_name"
        private const val EXTRA_SHA256 = "rom_sha256"
        private const val EXTRA_TITLE = "rom_title"

        /** Emplacement d'état utilisateur (le 0 est réservé à l'automatique). */
        private const val USER_SLOT = 1

        fun intent(context: Context, entry: RomEntry): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_URI, entry.uri)
                .putExtra(EXTRA_FILE_NAME, entry.fileName)
                .putExtra(EXTRA_SHA256, entry.fingerprints.sha256)
                .putExtra(EXTRA_TITLE, entry.displayName)
    }
}
