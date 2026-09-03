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
import android.text.format.DateUtils
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ravenemu.app.BuildConfig
import com.ravenemu.app.R
import com.ravenemu.app.RavenActivity
import com.ravenemu.deltaskin.DeltaSkinConsole
import com.ravenemu.deltaskin.DeltaSkinErrorCode
import com.ravenemu.deltaskin.DeltaSkinInsets
import com.ravenemu.deltaskin.DeltaSkinRepresentationKind
import com.ravenemu.deltaskin.DeltaSkinRepository
import com.ravenemu.deltaskin.DeltaSkinScreenPlacement
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.audio.AudioTransportStats
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.session.EmulationSession
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.SaveStateException
import com.ravenemu.input.ControlLayout
import com.ravenemu.input.DeltaSkinControllerAsset
import com.ravenemu.input.DeltaSkinControllerConfiguration
import com.ravenemu.input.DeltaSkinControllerView
import com.ravenemu.input.DeltaSkinPdfRenderer
import com.ravenemu.input.GamepadMapper
import com.ravenemu.input.TouchControlsView
import com.ravenemu.renderer.EmulatorSurfaceView
import com.ravenemu.renderer.ScreenPlacement
import kotlin.math.round
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.settings.AppSettings
import com.ravenemu.emulation.api.display.DisplayAdjustments
import com.ravenemu.emulation.api.display.MonochromeDisplayProfiles
import com.ravenemu.storage.LibraryRepository
import com.ravenemu.storage.SaveFileStore
import com.ravenemu.storage.SnapshotStore
import com.ravenemu.platform.vibration.AndroidRumbleSink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran d'émulation : surface de rendu, commandes tactiles, menu de
 * l'émulateur, éditeur de disposition, manettes physiques, cycle de vie
 * Android (pause en arrière-plan, sauvegardes de secours).
 */
class EmulationActivity : RavenActivity(), EmulationSession.Callbacks {

    // Cet écran transmet déjà les insets à la surface et aux Delta Skins.
    override val applyDisplayCutoutInsetsToContent: Boolean = false

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
        applyVideoSettings()
        applyControlLayout()
        bindControls()
        bindDeltaSkinControls()
        bindEditor()
        applyControlPresentation()

        loadRomAndStart()
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
            // Un skin portrait impose le ratio natif dans la zone de jeu, et
            // son propre cadrage : ni centrage vertical libre, ni étirement.
            //
            // La mise à l'échelle entière, elle, reste au joueur. La forcer à
            // l'arrêt rendait le réglage sans effet dès qu'un skin était actif,
            // sans que rien ne le dise : le joueur cochait la case, l'image ne
            // changeait pas, et l'ondulation des décors en défilement restait.
            surface.keepAspectRatio = true
            surface.integerScaling = settings.integerScaling
            surface.topAligned = false
        }
    }

    // ---- Profils de commandes ----

    /**
     * Suffixe de profil propre à la console : chacune a ses propres commandes.
     *
     * Il vient de la console elle-même. L’avoir décidé ici rangeait les
     * dispositions de la Nintendo DS avec celles de la Game Boy, si bien qu’une
     * console à six touches de plus héritait d’une disposition qui les ignore.
     */
    private fun consoleKey(): String = console.layoutKey

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
        // Les touches dessinées sont celles que la console possède : la
        // disposition n’en décide pas, elle les reçoit.
        return if (orientationKey() == "landscape") {
            ControlLayout.defaultLandscape(console.buttons)
        } else {
            ControlLayout.defaultPortrait(console.buttons)
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

            override fun onSkinReady(
                gameArea: android.graphics.Rect,
                screens: List<DeltaSkinScreenPlacement>,
            ) {
                customSkinActive = true
                controls.visibility = View.GONE
                editorPanel.visibility = View.GONE
                surface.contentBounds = gameArea
                surface.screenPlacements = screens.map(::toPlacement)
                surface.keepAspectRatio = true
                surface.integerScaling = settings.integerScaling
                surface.topAligned = false
            }

            override fun onSkinLayoutChanged(
                gameArea: android.graphics.Rect,
                screens: List<DeltaSkinScreenPlacement>,
            ) {
                if (!customSkinActive) return
                surface.contentBounds = gameArea
                surface.screenPlacements = screens.map(::toPlacement)
            }

            override fun onSkinError(code: DeltaSkinErrorCode) {
                handleDeltaSkinFailure(code)
            }

            /**
             * Le skin donne une position en fractions de sa zone tactile ; la
             * console la veut en pixels de son écran. La conversion appartient
             * à la console, qui seule connaît sa résolution.
             */
            override fun onTouchScreen(point: com.ravenemu.deltaskin.DeltaSkinTouchPoint) {
                val screen = console.touchScreen ?: return
                val (x, y) = screen.pixelAt(point.fractionX, point.fractionY)
                session?.setTouch(down = true, x = x, y = y)
            }

            override fun onTouchScreenReleased() {
                if (console.touchScreen == null) return
                // Un stylet levé n'a pas de position : les coordonnées sont
                // ignorées par le moteur, qui n'en retient aucune.
                session?.setTouch(down = false, x = 0, y = 0)
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
                    // Les dimensions viennent de la console elle-même : les
                    // redire ici en aurait fait une seconde source, et une
                    // console ajoutée aurait pris l'écran d'une autre.
                    nativeScreenSize = skinConsole.screenSize,
                    hapticFeedback = settings.hapticFeedback,
                    visualFeedback = settings.deltaSkinVisualFeedback,
                )
            )
        }
    }

    /**
     * Passe un emplacement d'écran du vocabulaire des skins à celui du rendu.
     *
     * Les deux mondes se rencontrent ici et nulle part ailleurs : le module des
     * skins raisonne en réels, parce qu'un cadre de manifeste n'est pas un
     * pixel, et l'affichage en pixels entiers de la vue.
     */
    private fun toPlacement(
        placement: DeltaSkinScreenPlacement,
    ): ScreenPlacement = ScreenPlacement(
        source = placement.source?.let { frame ->
            android.graphics.Rect(
                round(frame.x).toInt(),
                round(frame.y).toInt(),
                round(frame.x + frame.width).toInt(),
                round(frame.y + frame.height).toInt(),
            )
        },
        destination = android.graphics.Rect(
            round(placement.destination.left).toInt(),
            round(placement.destination.top).toInt(),
            round(placement.destination.right).toInt(),
            round(placement.destination.bottom).toInt(),
        ),
    )

    private fun showClassicControls() {
        customSkinActive = false
        activeDeltaSkinSha256 = null
        deltaSkinControls.setConfiguration(null)
        controls.visibility = View.VISIBLE
        surface.contentBounds = null
        surface.screenPlacements = emptyList()
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

    private fun deltaSkinConsole(): DeltaSkinConsole = when (console) {
        ConsoleType.GAME_BOY_ADVANCE -> DeltaSkinConsole.GBA
        ConsoleType.NINTENDO_DS -> DeltaSkinConsole.DS
        ConsoleType.GAME_BOY -> DeltaSkinConsole.GB_GBC
    }

    // ---- Chargement ----

    private fun loadRomAndStart() {
        lifecycleScope.launch { startEmulation() }
    }

    /**
     * Charge la cartouche dans [core] sans la faire passer par la mémoire Java.
     *
     * Une cartouche Nintendo DS pèse jusqu'à un demi-gigaoctet, et le tas Java
     * d'une application Android est plafonné bien en dessous de ce que
     * l'appareil permettrait : c'est ce plafond, et non le matériel, qui
     * refusait les grosses cartouches. Le descripteur est ouvert ici et rendu
     * ici ; le pont natif ne le ferme pas.
     *
     * Rend `false` quand ce chemin n'est pas disponible — moteur qui ne le sait
     * pas, ou document dont la taille n'est pas connue d'avance. L'appelant
     * retombe alors sur la lecture en mémoire.
     */
    private fun loadFromDescriptor(core: EmulatorCore, battery: ByteArray?): Boolean =
        contentResolver.openFileDescriptor(romUri, "r")?.use { opened ->
            val size = opened.statSize
            if (size < 0L) false else core.loadRomFromDescriptor(opened.fd, size, battery)
        } ?: false

    private suspend fun startEmulation() {
        // Le registre reçoit les réglages propres à ce jeu : mémoire de
        // sauvegarde et présence de l'horloge de cartouche.
        val newCore = RavenConsoles.registry(
            forcedGbaSaveType = settings.forcedSaveType(romSha256),
            forcedGbaRtc = settings.forcedRtc(romSha256),
        ).create(console)
        try {
            val battery = saveStore.read(romSha256, romFileName, settings.saveDirectory)
            if (!withContext(Dispatchers.IO) { loadFromDescriptor(newCore, battery) }) {
                // Base de références par défaut : l'identification a été faite
                // par la bibliothèque, seule la lecture est nécessaire ici.
                val repository = LibraryRepository(this, RavenConsoles.romAnalyzers())
                val data = repository.readRom(romUri) ?: error("ROM illisible")
                newCore.loadRom(data, battery)
            }
        } catch (e: Throwable) {
            // `Throwable` et non `Exception`, délibérément. Charger une ROM
            // choisie par le joueur peut échouer de deux façons qui ne sont pas
            // des exceptions : un fichier trop gros pour le tas Java lève une
            // `OutOfMemoryError`, et un champ interne refusé par Android une
            // `NoSuchFieldError`. Ces erreurs traversent un rattrapage
            // d'exceptions et tuent l'application, là où elles devraient donner
            // un refus lisible. Aucun fichier ouvert par un joueur ne doit
            // pouvoir arrêter RavenEmu.
            runCatching { (newCore as? AutoCloseable)?.close() }
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
            AndroidRumbleSink(this),
            // Le groupe d'ordonnancement Android décide, sur un processeur
            // hétérogène, si le thread tourne sur un cœur puissant ou économe.
            onThreadStart = {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
                )
            },
        )
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

        // Un emplacement demandé explicitement — reprise depuis l'écran des
        // états — prime sur la reprise automatique : c'est un choix du joueur,
        // pas un filet de sécurité.
        val requestedSlot = intent.getIntExtra(EXTRA_RESUME_SLOT, NO_SLOT)
        val restoreSlot = when {
            requestedSlot != NO_SLOT -> requestedSlot
            settings.autoResume -> SnapshotStore.AUTO_SLOT
            else -> NO_SLOT
        }
        if (restoreSlot != NO_SLOT) {
            snapshotStore.read(romSha256, restoreSlot)?.let { state ->
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
        val perGameLabel = getString(
            if (hasPerGameProfile()) R.string.emulation_per_game_profile_off
            else R.string.emulation_per_game_profile_on
        )
        // Libellé et action vont ensemble. Ils étaient auparavant un tableau de
        // textes et un « when » sur l'indice : retirer une entrée décalait
        // silencieusement toutes les suivantes, et le joueur qui demandait la
        // remise à zéro obtenait l'édition des commandes.
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        fun entree(label: String, action: () -> Unit) {
            actions += label to action
        }

        entree(getString(R.string.emulation_resume)) { currentSession.resume() }
        // Un moteur sans format d'état ne se voit pas proposer d'en écrire un :
        // l'entrée est absente plutôt que présente et fautive.
        if (supportsSaveState) {
            entree(getString(R.string.emulation_save_state)) { saveSnapshot() }
            entree(getString(R.string.emulation_load_state)) { loadSnapshot() }
        }
        entree(getString(R.string.emulation_reset)) {
            currentSession.post { it.reset() }
            currentSession.resume()
        }
        entree(getString(R.string.emulation_edit_controls)) { enterEditMode() }
        entree(perGameLabel) { togglePerGameProfile() }
        entree(getString(R.string.emulation_quit)) { finish() }
        AlertDialog.Builder(this)
            .setTitle(romTitle.ifBlank { romFileName })
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setOnCancelListener { currentSession.resume() }
            .show()
    }

    /**
     * Vrai quand le moteur chargé sait enregistrer un instantané.
     *
     * C'est le moteur qui répond, jamais la console : redire ici quelle console
     * a un format d'état en ferait une seconde source, et l'écran continuerait
     * d'offrir des emplacements le jour où un moteur cesserait de les tenir.
     * Sans moteur il n'y a pas de partie, donc rien à enregistrer non plus.
     */
    private val supportsSaveState: Boolean
        get() = core?.supportsSaveState == true

    /**
     * Sauvegarde dans un emplacement choisi.
     *
     * Un emplacement unique obligeait à écraser sa seule sauvegarde pour en
     * prendre une autre, et rendait l'écran des états sans objet. Le libellé de
     * chaque emplacement indique s'il est déjà occupé, pour qu'un écrasement
     * soit un choix et non une surprise.
     */
    private fun saveSnapshot() {
        val currentSession = session ?: return
        val occupes = snapshotStore.list(romSha256).associateBy { it.slot }
        val labels = USER_SLOTS.map { slot ->
            val base = getString(R.string.states_slot, slot)
            occupes[slot]?.let { info ->
                base + " · " + DateUtils.getRelativeTimeSpanString(
                    info.savedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
            } ?: base
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.states_choose_slot)
            .setItems(labels) { _, which ->
                writeSnapshot(currentSession, USER_SLOTS.first + which)
            }
            .setOnCancelListener { currentSession.resume() }
            .show()
    }

    private fun writeSnapshot(currentSession: EmulationSession, slot: Int) {
        currentSession.post { c ->
            val state = c.saveState()
            val saved = snapshotStore.write(romSha256, slot, state)
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

    /**
     * Recharge un état. Seuls les emplacements réellement occupés sont
     * proposés : offrir des emplacements vides n'apprendrait rien et ferait
     * échouer le chargement une fois sur deux.
     */
    private fun loadSnapshot() {
        val currentSession = session ?: return
        val disponibles = snapshotStore.list(romSha256)
            .filter { it.slot in USER_SLOTS }
            .sortedBy { it.slot }
        if (disponibles.isEmpty()) {
            Toast.makeText(this, R.string.emulation_no_state, Toast.LENGTH_SHORT).show()
            currentSession.resume()
            return
        }
        val labels = disponibles.map { info ->
            getString(R.string.states_slot, info.slot) + " · " +
                DateUtils.getRelativeTimeSpanString(
                    info.savedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.emulation_load_state)
            .setItems(labels) { _, which ->
                readSnapshot(currentSession, disponibles[which].slot)
            }
            .setOnCancelListener { currentSession.resume() }
            .show()
    }

    private fun readSnapshot(currentSession: EmulationSession, slot: Int) {
        val state = snapshotStore.read(romSha256, slot)
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
        // Un moteur sans format d'état ne la tente pas : elle échouerait à
        // chaque mise en pause, et le rattrapage silencieux ci-dessous cacherait
        // une erreur attendue au milieu de celles qui ne le sont pas.
        if (supportsSaveState) {
            currentSession.post { c ->
                try {
                    snapshotStore.write(romSha256, SnapshotStore.AUTO_SLOT, c.saveState())
                } catch (_: Exception) {
                    // La sauvegarde de secours ne doit jamais faire échouer la pause.
                }
            }
        }
        if (settings.pauseInBackground) currentSession.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        val currentCore = core
        // Un thread qui ne rend pas la main laisse la sortie audio en vie :
        // libérer sa sortie ou son cœur natif sous ses pieds planterait le
        // processus. On le journalise plutôt que de le taire.
        val stopResult = session?.stop()
        if (stopResult == EmulationSession.StopResult.TIMED_OUT) {
            android.util.Log.w(
                "RavenEmu",
                "thread d'émulation encore actif après le délai : ressources non libérées",
            )
        } else {
            runCatching { (currentCore as? AutoCloseable)?.close() }
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
        private const val EXTRA_RESUME_SLOT = "rom_resume_slot"

        /**
         * Emplacements d'états choisis par le joueur. Le 0 reste réservé à la
         * reprise automatique : l'écraser ferait perdre la partie en cours au
         * profit d'une sauvegarde volontaire, ce que personne n'attend.
         */
        val USER_SLOTS = 1..4

        /** Aucun emplacement imposé : la reprise automatique décide. */
        const val NO_SLOT = -1

        fun intent(context: Context, entry: RomEntry, resumeSlot: Int = NO_SLOT): Intent =
            intent(
                context = context,
                uri = entry.uri,
                fileName = entry.fileName,
                sha256 = entry.fingerprints.sha256,
                title = entry.displayName,
                console = entry.console.name,
                resumeSlot = resumeSlot,
            )

        /**
         * Variante sans [RomEntry], pour les écrans qui ne transportent que les
         * champs nécessaires — l'entrée d'index n'est pas sérialisable dans un
         * `Intent` et la recopier n'apporterait rien.
         */
        fun intent(
            context: Context,
            uri: String,
            fileName: String,
            sha256: String,
            title: String,
            console: String,
            resumeSlot: Int = NO_SLOT,
        ): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_SHA256, sha256)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CONSOLE, console)
                .putExtra(EXTRA_RESUME_SLOT, resumeSlot)
    }
}
