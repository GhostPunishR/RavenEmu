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
import com.ravenemu.app.BuildConfig
import com.ravenemu.app.R
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.audio.AudioTransportStats
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.session.EmulationSession
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.SaveStateException
import com.ravenemu.input.ControlId
import com.ravenemu.input.ControlLayout
import com.ravenemu.input.GamepadMapper
import com.ravenemu.input.TouchSkin
import com.ravenemu.input.TouchControlsView
import com.ravenemu.renderer.EmulatorSurfaceView
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.settings.AppSettings
import com.ravenemu.emulation.api.display.DisplayAdjustments
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

    /** Relevé du transport audio de la session courante, ou `null`. */
    private var audioStats: AudioTransportStats? = null

    private val gamepad = GamepadMapper()

    private var core: EmulatorCore? = null
    private var session: EmulationSession? = null

    private lateinit var romUri: Uri
    private lateinit var romFileName: String
    private lateinit var romSha256: String
    private var romTitle: String = ""
    private var console: ConsoleType = ConsoleType.GAME_BOY

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
        console = intent.getStringExtra(EXTRA_CONSOLE)
            ?.let { runCatching { ConsoleType.valueOf(it) }.getOrNull() }
            ?: ConsoleType.GAME_BOY

        surface = findViewById(R.id.surface)
        controls = findViewById(R.id.controls)
        performanceOverlay = findViewById(R.id.performanceOverlay)
        editorPanel = findViewById(R.id.editorPanel)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // L'image est ancrée en haut en portrait : on la décale sous
        // l'encoche ou la caméra perforée.
        ViewCompat.setOnApplyWindowInsetsListener(surface) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            surface.topInsetPx = topInset
            controls.screenTopInsetPx = topInset
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
        controls.skinPanelVisible = settings.keepAspectRatio
        surface.integerScaling = settings.integerScaling
        // Portrait : écran de jeu en haut, commandes en dessous. Paysage :
        // l'image remplit la hauteur, le centrage reste naturel.
        surface.topAligned =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        // DMG : le renderer colorise les niveaux 0..3 via le profil d'écran
        // (à chaud). Game Boy Color : le moteur produit déjà des couleurs
        // ARGB, on laisse donc le renderer les afficher telles quelles.
        val monochrome =
            core?.framebufferFormat != com.ravenemu.emulation.api.FramebufferFormat.ARGB_8888
        surface.displayColors = if (monochrome) {
            MonochromeDisplayProfiles.byId(settings.screenProfileId).colors
        } else {
            null
        }
        // Réglages avancés en post-traitement (aucun effet si tout est neutre).
        // La correction LCD simule un panneau précis : elle se règle par console
        // et n'est plus imposée à toutes.
        surface.displayAdjustments = DisplayAdjustments(
            brightness = settings.displayBrightness,
            contrast = settings.displayContrast,
            lcdColorCorrection = settings.lcdColorCorrection(console),
        )
        performanceOverlay.visibility =
            if (settings.showPerformanceOverlay) View.VISIBLE else View.GONE
    }

    // ---- Profils de commandes ----

    /** Suffixe de profil propre à la console : la GBA a ses propres commandes. */
    private fun consoleKey(): String =
        if (console == ConsoleType.GAME_BOY_ADVANCE) "gba" else "gb"

    private fun orientationKey(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }

    private fun perGameProfileKey(): String =
        "${romSha256}_${orientationKey()}_${consoleKey()}"

    private fun hasPerGameProfile(): Boolean =
        settings.controlLayout(perGameProfileKey()) != null

    private fun activeProfileKey(): String =
        if (hasPerGameProfile()) perGameProfileKey() else "${orientationKey()}_${consoleKey()}"

    private fun defaultLayout(): ControlLayout {
        // Les gâchettes L/R ne sont affichées que pour les consoles qui en ont
        // (Game Boy Advance).
        val withShoulders = console == ConsoleType.GAME_BOY_ADVANCE
        return if (orientationKey() == "landscape") {
            ControlLayout.defaultLandscape(withShoulders)
        } else if (withShoulders) {
            ControlLayout.ravenGbaPortrait(portraitScreenBottomFraction(240f / 160f))
        } else {
            ControlLayout.ravenGbPortrait(portraitScreenBottomFraction(160f / 144f))
        }
    }

    /**
     * Position relative du bas de l'image native quand elle occupe la largeur.
     * Les profils par défaut placent ainsi MENU (et L/R en GBA) juste dessous
     * sur un téléphone court comme sur un écran portrait allongé.
     */
    private fun portraitScreenBottomFraction(screenAspectRatio: Float): Float {
        val metrics = resources.displayMetrics
        if (metrics.heightPixels <= 0 || screenAspectRatio <= 0f) return 0f
        return (metrics.widthPixels / screenAspectRatio / metrics.heightPixels)
            .coerceIn(0f, 1f)
    }

    private fun applyControlLayout() {
        controls.skin = when {
            orientationKey() == "landscape" -> TouchSkin.CLASSIC
            console == ConsoleType.GAME_BOY_ADVANCE -> TouchSkin.RAVEN_GBA
            else -> TouchSkin.RAVEN_GB
        }
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
        // Seule la lecture de la ROM est nécessaire ici : base de références
        // par défaut (l'identification est faite par la bibliothèque).
        val repository = LibraryRepository(this)
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
        // Le registre reçoit le réglage de sauvegarde propre à ce jeu.
        val newCore = RavenConsoles.registry(settings.forcedSaveType(romSha256))
            .create(console)
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
        // Relevé du transport audio : construction de diagnostic uniquement.
        // Sans lui, un craquement ne se distingue pas de sa cause.
        audioSink.stats.enabled = BuildConfig.DIAGNOSTICS
        audioStats = audioSink.stats
        audioSink.setVolume(settings.audioVolume / 100f)

        val newSession = EmulationSession(
            newCore,
            this,
            audioSink,
            // Le groupe d'ordonnancement Android décide, sur un processeur
            // hétérogène, si le thread tourne sur un cœur puissant ou économe.
            onThreadStart = {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
                )
            },
        )
        newSession.speedLimitEnabled = settings.speedLimitEnabled
        newSession.fastForwardMultiplier = settings.fastForwardMultiplier
        newSession.audioEnabled = settings.audioEnabled
        core = newCore
        session = newSession
        // Journalisation des anomalies du moteur : bridée, et Debug uniquement.
        // La mesure, elle, ne s'allume que si l'utilisateur la demande : elle
        // coûte assez cher pour fausser ce qu'elle observe.
        GbaDebugOverlay.attachLogging(newCore, measuring = settings.videoDiagnostics)

        // La ROM est chargée : le format (monochrome DMG ou couleur CGB) est
        // connu, on (ré)applique les réglages vidéo en conséquence.
        applyVideoSettings()

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

    override fun onStats(fps: Double, frameTimeMs: Double) {
        if (!settings.showPerformanceOverlay) return
        val text = GbaDebugOverlay.render(
            fps,
            frameTimeMs,
            core,
            audioStats,
            session?.audioOutputUnderruns() ?: 0,
        )
        runOnUiThread { performanceOverlay.text = text }
    }

    /**
     * Une sortie audio en échec ne coupait le son qu'en silence : le rappel
     * n'était pas redéfini et la sortie rattrapait elle-même ses exceptions.
     * Le compteur permet de le voir dans la surcouche, le journal de savoir
     * quoi — en construction de diagnostic uniquement.
     */
    override fun onAudioFailure(error: Exception) {
        audioStats?.onFailure(error)
        GbaDebugOverlay.logAudioFailure(error)
    }

    /**
     * Écrit la RAM de cartouche et rend compte du résultat : c'est ce booléen
     * qui autorise la session à acquitter la sauvegarde. La copie SAF externe
     * reste best-effort et n'entre pas dans ce verdict — seule l'écriture
     * privée atomique fait foi.
     */
    override fun onBatterySave(data: ByteArray): Boolean =
        saveStore.write(romSha256, romFileName, data, settings.saveDirectory)

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
            val saved = snapshotStore.write(romSha256, USER_SLOT, state)
            runOnUiThread {
                val message = if (saved) {
                    R.string.emulation_state_saved
                } else {
                    R.string.emulation_state_save_failed
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        // Le relevé de diagnostic se règle depuis les paramètres, donc en
        // quittant cet écran : le reprendre ici évite d'avoir à recharger la ROM
        // pour que le changement prenne effet.
        GbaDebugOverlay.attachLogging(core, measuring = settings.videoDiagnostics)
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
        // Un thread qui ne rend pas la main laisse la sortie audio en vie :
        // la libérer sous ses pieds planterait le processus. On le journalise
        // plutôt que de le taire.
        val stopResult = session?.stop()
        if (stopResult == EmulationSession.StopResult.TIMED_OUT) {
            android.util.Log.w(
                "RavenEmu",
                "thread d'émulation encore actif après le délai : sortie audio non libérée",
            )
        }
        session = null
        core = null
    }

    companion object {
        private const val EXTRA_URI = "rom_uri"
        private const val EXTRA_FILE_NAME = "rom_file_name"
        private const val EXTRA_SHA256 = "rom_sha256"
        private const val EXTRA_TITLE = "rom_title"
        private const val EXTRA_CONSOLE = "rom_console"

        /** Emplacement d'état utilisateur (le 0 est réservé à l'automatique). */
        private const val USER_SLOT = 1

        fun intent(context: Context, entry: RomEntry): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_URI, entry.uri)
                .putExtra(EXTRA_FILE_NAME, entry.fileName)
                .putExtra(EXTRA_SHA256, entry.fingerprints.sha256)
                .putExtra(EXTRA_TITLE, entry.displayName)
                .putExtra(EXTRA_CONSOLE, entry.console.name)
    }
}
