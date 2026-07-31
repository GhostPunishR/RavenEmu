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
import com.ravenemu.deltaskin.DeltaSkinConsole
import com.ravenemu.deltaskin.DeltaSkinErrorCode
import com.ravenemu.deltaskin.DeltaSkinInsets
import com.ravenemu.deltaskin.DeltaSkinRepresentationKind
import com.ravenemu.deltaskin.DeltaSkinRepository
import com.ravenemu.deltaskin.DeltaSkinSize
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.audio.AudioTransportStats
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.session.EmulationSession
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.SaveStateException
import com.ravenemu.input.ControlId
import com.ravenemu.input.ControlLayout
import com.ravenemu.input.DeltaSkinControllerAsset
import com.ravenemu.input.DeltaSkinControllerConfiguration
import com.ravenemu.input.DeltaSkinControllerView
import com.ravenemu.input.DeltaSkinPdfRenderer
import com.ravenemu.input.GamepadMapper
import com.ravenemu.input.TouchControlsView
import com.ravenemu.renderer.EmulatorSurfaceView
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.settings.AppSettings
import com.ravenemu.emulation.api.display.DisplayAdjustments
import com.ravenemu.emulation.api.display.MonochromeDisplayProfiles
import com.ravenemu.storage.LibraryRepository
import com.ravenemu.storage.SaveFileStore
import com.ravenemu.storage.SnapshotStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var deltaSkinControls: DeltaSkinControllerView
    private lateinit var deltaSkinRepository: DeltaSkinRepository
    private lateinit var performanceOverlay: TextView
    private lateinit var editorPanel: View

    /** Relevé du transport audio de la session courante, ou `null`. */
    private var audioStats: AudioTransportStats? = null

    private val gamepad = GamepadMapper()

    private var core: EmulatorCore? = null
    private var session: EmulationSession? = null
    private var deltaSkinLoadGeneration = 0L
    private var customSkinActive = false
    private var restoreCustomSkinAfterEditing = false
    private var activeDeltaSkinSha256: String? = null

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
        deltaSkinRepository = DeltaSkinRepository(
            File(filesDir, "controller-skins/delta")
        )

        romUri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_URI)))
        romFileName = requireNotNull(intent.getStringExtra(EXTRA_FILE_NAME))
        romSha256 = requireNotNull(intent.getStringExtra(EXTRA_SHA256))
        romTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        console = intent.getStringExtra(EXTRA_CONSOLE)
            ?.let { runCatching { ConsoleType.valueOf(it) }.getOrNull() }
            ?: ConsoleType.GAME_BOY

        surface = findViewById(R.id.surface)
        controls = findViewById(R.id.controls)
        deltaSkinControls = findViewById(R.id.deltaSkinControls)
        performanceOverlay = findViewById(R.id.performanceOverlay)
        editorPanel = findViewById(R.id.editorPanel)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById<View>(R.id.emulationRoot)
        ) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemBars()
            )
            surface.topInsetPx = safe.top
            deltaSkinControls.setSafeInsets(
                DeltaSkinInsets(
                    left = safe.left.toDouble(),
                    top = safe.top.toDouble(),
                    right = safe.right.toDouble(),
                    bottom = safe.bottom.toDouble(),
                )
            )
            insets
        }
        applyImmersiveMode()
        applyVideoSettings()
        applyControlLayout()
        bindControls()
        bindDeltaSkinControls()
        bindEditor()
        applyControlPresentation()

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
        if (customSkinActive) {
            // Un skin portrait impose le ratio natif dans la zone de jeu.
            surface.keepAspectRatio = true
            surface.integerScaling = false
            surface.topAligned = false
        }
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
        } else {
            ControlLayout.defaultPortrait(withShoulders)
        }
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

    private fun bindDeltaSkinControls() {
        deltaSkinControls.listener = object : DeltaSkinControllerView.Listener {
            override fun onButtonChanges(
                changes: com.ravenemu.deltaskin.DeltaSkinButtonChanges,
            ) {
                session?.setButtons(changes.pressed, changes.released)
            }

            override fun onMenu() {
                showEmulatorMenu()
            }

            override fun onSkinReady(gameArea: android.graphics.Rect) {
                customSkinActive = true
                controls.visibility = View.GONE
                editorPanel.visibility = View.GONE
                surface.contentBounds = gameArea
                surface.keepAspectRatio = true
                surface.integerScaling = false
                surface.topAligned = false
            }

            override fun onSkinLayoutChanged(gameArea: android.graphics.Rect) {
                if (customSkinActive) surface.contentBounds = gameArea
            }

            override fun onSkinError(code: DeltaSkinErrorCode) {
                handleDeltaSkinFailure(code)
            }
        }
    }

    /**
     * Le mode paysage et toute erreur reviennent au chemin historique. Le skin
     * classique reste affiché jusqu'à ce que le PDF personnalisé soit prêt.
     */
    private fun applyControlPresentation() {
        if (controls.editMode) return
        val skinConsole = deltaSkinConsole()
        val identifier = settings.deltaSkinIdentifier(skinConsole)
        val portrait =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val token = ++deltaSkinLoadGeneration
        if (!portrait || identifier == null) {
            showClassicControls()
            return
        }

        showClassicControls()
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                deltaSkinRepository.findByIdentifier(identifier)
            }
            if (token != deltaSkinLoadGeneration || controls.editMode) return@launch
            if (installed == null || installed.metadata.console != skinConsole) {
                handleDeltaSkinFailure(DeltaSkinErrorCode.INVALID_ARCHIVE)
                return@launch
            }
            val iphone = installed.metadata.manifest.representations.iphone
            if (iphone == null) {
                handleDeltaSkinFailure(DeltaSkinErrorCode.PORTRAIT_REPRESENTATION_MISSING)
                return@launch
            }
            val assets = buildMap {
                for (kind in DeltaSkinRepresentationKind.entries) {
                    val representation = iphone.portrait(kind) ?: continue
                    val file = installed.assetFile(kind) ?: continue
                    put(kind, DeltaSkinControllerAsset(representation, file))
                }
            }
            if (assets.isEmpty()) {
                handleDeltaSkinFailure(DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING)
                return@launch
            }
            activeDeltaSkinSha256 = installed.metadata.archiveSha256
            deltaSkinControls.setConfiguration(
                DeltaSkinControllerConfiguration(
                    skinSha256 = installed.metadata.archiveSha256,
                    console = skinConsole,
                    assets = assets,
                    preference = settings.deltaSkinRepresentationPreference,
                    nativeScreenSize = if (skinConsole == DeltaSkinConsole.GBA) {
                        DeltaSkinSize(240.0, 160.0)
                    } else {
                        DeltaSkinSize(160.0, 144.0)
                    },
                    hapticFeedback = settings.hapticFeedback,
                    visualFeedback = settings.deltaSkinVisualFeedback,
                )
            )
        }
    }

    private fun showClassicControls() {
        customSkinActive = false
        activeDeltaSkinSha256 = null
        deltaSkinControls.setConfiguration(null)
        controls.visibility = View.VISIBLE
        surface.contentBounds = null
        applyVideoSettings()
    }

    private fun handleDeltaSkinFailure(code: DeltaSkinErrorCode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("RavenEmuDeltaSkin", "Fallback classique : $code")
        }
        activeDeltaSkinSha256?.let(DeltaSkinPdfRenderer::invalidate)
        settings.setDeltaSkinIdentifier(deltaSkinConsole(), null)
        showClassicControls()
        Toast.makeText(
            this,
            R.string.delta_skins_runtime_fallback,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun deltaSkinConsole(): DeltaSkinConsole =
        if (console == ConsoleType.GAME_BOY_ADVANCE) {
            DeltaSkinConsole.GBA
        } else {
            DeltaSkinConsole.GB_GBC
        }

    // ---- Chargement ----

    private fun loadRomAndStart() {
        // Seule la lecture de la ROM est nécessaire ici : base de références
        // par défaut (l'identification est faite par la bibliothèque).
        val repository = LibraryRepository(this, RavenConsoles.romAnalyzers())
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
        // coûte assez cher pour fausser ce qu'elle observe. Appel direct sans
        // risque : le thread d'émulation n'est pas encore démarré.
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
        restoreCustomSkinAfterEditing = customSkinActive
        if (customSkinActive) showClassicControls()
        controls.editMode = true
        editorPanel.visibility = View.VISIBLE
    }

    private fun exitEditMode() {
        controls.editMode = false
        editorPanel.visibility = View.GONE
        settings.saveControlLayout(activeProfileKey(), controls.layoutSpec)
        if (restoreCustomSkinAfterEditing) {
            restoreCustomSkinAfterEditing = false
            applyControlPresentation()
        }
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
        applyControlPresentation()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        applyVideoSettings()
        applyControlPresentation()
        // Le relevé de diagnostic se règle depuis les paramètres, donc en
        // quittant cet écran : le reprendre ici évite d'avoir à recharger la ROM
        // pour que le changement prenne effet.
        //
        // Il passe par la file de la session, comme toute autre mutation du
        // cœur. Le moteur est mono-thread : ces drapeaux et le rappel de
        // chronométrage sont lus par la boucle d'émulation pendant `runFrame`.
        // Les écrire depuis le fil d'interface pourrait, par exemple, brancher
        // le rappel de l'unité audio entre son test de nullité et son appel — et
        // le relevé mesurerait alors le temps écoulé depuis l'origine.
        val diagnosticsDemandes = settings.videoDiagnostics
        session?.post { c -> GbaDebugOverlay.attachLogging(c, measuring = diagnosticsDemandes) }
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
