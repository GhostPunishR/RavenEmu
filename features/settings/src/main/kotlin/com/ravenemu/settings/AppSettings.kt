package com.ravenemu.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.ravenemu.deltaskin.DeltaSkinConsole
import com.ravenemu.deltaskin.DeltaSkinRepresentationPreference
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.input.ControlLayout

/**
 * Préférences RavenEmu, organisées par domaines (émulation, vidéo, audio,
 * contrôles, fichiers, bibliothèque, débogage). Stockage local uniquement :
 * aucune donnée ne quitte l'appareil.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ravenemu_settings", Context.MODE_PRIVATE)

    // ---- Émulation ----

    /** Pause automatique quand l'application passe en arrière-plan. */
    var pauseInBackground: Boolean
        get() = prefs.getBoolean("emu_pause_background", true)
        set(value) = prefs.edit { putBoolean("emu_pause_background", value) }

    /** Reprise automatique du dernier état au lancement d'un jeu. */
    var autoResume: Boolean
        get() = prefs.getBoolean("emu_auto_resume", false)
        set(value) = prefs.edit { putBoolean("emu_auto_resume", value) }

    // ---- Vidéo ----

    var keepAspectRatio: Boolean
        get() = prefs.getBoolean("video_keep_aspect", true)
        set(value) = prefs.edit { putBoolean("video_keep_aspect", value) }

    var integerScaling: Boolean
        get() = prefs.getBoolean("video_integer_scaling", false)
        set(value) = prefs.edit { putBoolean("video_integer_scaling", value) }

    /**
     * Identifiant du profil d'écran monochrome
     * (voir `MonochromeDisplayProfiles`). Défaut : Game Boy DMG.
     */
    var screenProfileId: String
        get() = prefs.getString("video_screen_profile", "dmg") ?: "dmg"
        set(value) = prefs.edit { putString("video_screen_profile", value) }

    var showPerformanceOverlay: Boolean
        get() = prefs.getBoolean("video_show_fps", false)
        set(value) = prefs.edit { putBoolean("video_show_fps", value) }

    /**
     * Relevé de diagnostic complet dans la surcouche, au lieu de la seule
     * cadence : registres de composition, dynamique de l'image, pixels par
     * couche, répartition du temps de trame.
     *
     * Désactivé par défaut, et sans effet hors construction de diagnostic. Il
     * n'est pas gratuit — il allume le chronométrage par sous-système, un
     * incrément par pixel dessiné et un balayage de l'image par trame — et c'est
     * précisément pourquoi il se demande explicitement plutôt que de suivre
     * l'affichage de la cadence.
     */
    var videoDiagnostics: Boolean
        get() = prefs.getBoolean("debug_video_diagnostics", false)
        set(value) = prefs.edit { putBoolean("debug_video_diagnostics", value) }

    /**
     * Luminosité de l'affichage, `-100..100` (`0` = neutre). Stockée en interne
     * sur `0..200` (curseur centré sur 100) pour le `SeekBarPreference`.
     */
    var displayBrightness: Int
        get() = prefs.getInt("video_brightness", 100).coerceIn(0, 200) - 100
        set(value) = prefs.edit { putInt("video_brightness", (value + 100).coerceIn(0, 200)) }

    /** Contraste de l'affichage, `-100..100` (`0` = neutre). Même stockage. */
    var displayContrast: Int
        get() = prefs.getInt("video_contrast", 100).coerceIn(0, 200) - 100
        set(value) = prefs.edit { putInt("video_contrast", (value + 100).coerceIn(0, 200)) }

    /**
     * Correction colorimétrique LCD **par console** : simulation calibrable de
     * la désaturation et du gamma d'un panneau réfléchissant. Désactivée par
     * défaut partout.
     *
     * Le réglage était unique et s'appliquait à toutes les consoles. Il n'a
     * pourtant de sens que pour le panneau qu'il simule : activé, il désature
     * de 28 % vers la luminance et assombrit les moyens tons. Sur une Game Boy
     * Color, dont les couleurs sont très vives, c'est l'effet recherché. Sur une
     * Game Boy Advance, c'est un voile gris et mat appliqué à une image qui ne
     * le demandait pas — et rien dans l'interface ne le laissait deviner.
     *
     * La Game Boy Advance reçoit donc sa propre clé, à `false`. La clé
     * historique garde son sens pour la Game Boy et la Game Boy Color : leur
     * réglage n'est ni déplacé, ni réinterprété, ni migré en silence.
     */
    fun lcdColorCorrection(console: ConsoleType): Boolean =
        prefs.getBoolean(lcdCorrectionKey(console), false)

    private fun lcdCorrectionKey(console: ConsoleType): String = when (console) {
        ConsoleType.GAME_BOY_ADVANCE -> "video_lcd_correction_gba"
        // Clé d'origine, conservée telle quelle : le réglage déjà pris par
        // l'utilisateur pour la Game Boy et la Game Boy Color reste le sien.
        else -> "video_lcd_correction"
    }

    // ---- Audio (préparé pour la phase audio dédiée) ----

    var audioEnabled: Boolean
        get() = prefs.getBoolean("audio_enabled", true)
        set(value) = prefs.edit { putBoolean("audio_enabled", value) }

    /** Volume 0..100. */
    var audioVolume: Int
        get() = prefs.getInt("audio_volume", 100).coerceIn(0, 100)
        set(value) = prefs.edit { putInt("audio_volume", value.coerceIn(0, 100)) }

    // ---- Contrôles ----

    var hapticFeedback: Boolean
        get() = prefs.getBoolean("controls_haptic", true)
        set(value) = prefs.edit { putBoolean("controls_haptic", value) }

    /** Retour visuel discret au-dessus des hitboxes d'un skin Delta. */
    var deltaSkinVisualFeedback: Boolean
        get() = prefs.getBoolean("controls_delta_visual_feedback", true)
        set(value) = prefs.edit { putBoolean("controls_delta_visual_feedback", value) }

    var deltaSkinRepresentationPreference: DeltaSkinRepresentationPreference
        get() = prefs.getString("controls_delta_representation", "automatic")
            ?.let(::representationPreferenceFromStorage)
            ?: DeltaSkinRepresentationPreference.AUTOMATIC
        set(value) = prefs.edit {
            putString("controls_delta_representation", representationPreferenceToStorage(value))
        }

    /**
     * Sélection portrait séparée entre le cœur GB/GBC et le cœur GBA.
     * `null` désigne explicitement les commandes classiques.
     */
    fun deltaSkinIdentifier(console: DeltaSkinConsole): String? =
        prefs.getString(deltaSkinSelectionKey(console), null)

    fun setDeltaSkinIdentifier(console: DeltaSkinConsole, identifier: String?) {
        prefs.edit {
            if (identifier == null) remove(deltaSkinSelectionKey(console))
            else putString(deltaSkinSelectionKey(console), identifier)
        }
    }

    private fun deltaSkinSelectionKey(console: DeltaSkinConsole): String = when (console) {
        DeltaSkinConsole.GB_GBC -> "controls_delta_selected_gb_portrait"
        DeltaSkinConsole.GBA -> "controls_delta_selected_gba_portrait"
    }

    private fun representationPreferenceFromStorage(
        value: String,
    ): DeltaSkinRepresentationPreference = when (value) {
        "standard" -> DeltaSkinRepresentationPreference.STANDARD
        "edge_to_edge" -> DeltaSkinRepresentationPreference.EDGE_TO_EDGE
        else -> DeltaSkinRepresentationPreference.AUTOMATIC
    }

    private fun representationPreferenceToStorage(
        value: DeltaSkinRepresentationPreference,
    ): String = when (value) {
        DeltaSkinRepresentationPreference.AUTOMATIC -> "automatic"
        DeltaSkinRepresentationPreference.STANDARD -> "standard"
        DeltaSkinRepresentationPreference.EDGE_TO_EDGE -> "edge_to_edge"
    }

    /**
     * Disposition tactile d'un profil. [profile] combine orientation et,
     * éventuellement, l'empreinte du jeu pour un profil par jeu.
     */
    fun controlLayout(profile: String): ControlLayout? =
        prefs.getString("controls_layout_$profile", null)
            ?.let { ControlLayout.fromJson(it) }

    fun saveControlLayout(profile: String, layout: ControlLayout) {
        prefs.edit { putString("controls_layout_$profile", layout.toJson()) }
    }

    fun resetControlLayout(profile: String) {
        prefs.edit { remove("controls_layout_$profile") }
    }

    // ---- Fichiers ----

    /** Dossiers de ROM accordés via SAF. */
    var romDirectories: List<Uri>
        get() = prefs.getStringSet("files_rom_dirs", emptySet())
            .orEmpty()
            .map(Uri::parse)
        set(value) = prefs.edit {
            putStringSet("files_rom_dirs", value.map(Uri::toString).toSet())
        }

    /** Dossier `.sav` choisi par l'utilisateur (copie des sauvegardes). */
    var saveDirectory: Uri?
        get() = prefs.getString("files_sav_dir", null)?.let(Uri::parse)
        set(value) = prefs.edit { putString("files_sav_dir", value?.toString()) }

    /** Dossier de pochettes choisi par l'utilisateur. */
    var coversDirectory: Uri?
        get() = prefs.getString("files_covers_dir", null)?.let(Uri::parse)
        set(value) = prefs.edit { putString("files_covers_dir", value?.toString()) }

    // ---- Bibliothèque ----

    /** `grid` ou `list`. */
    var libraryViewMode: String
        get() = prefs.getString("library_view", "grid") ?: "grid"
        set(value) = prefs.edit { putString("library_view", value) }

    /** `title`, `size` ou `status`. */
    var librarySortOrder: String
        get() = prefs.getString("library_sort", "title") ?: "title"
        set(value) = prefs.edit { putString("library_sort", value) }

    var showStatusBadges: Boolean
        get() = prefs.getBoolean("library_badges", true)
        set(value) = prefs.edit { putBoolean("library_badges", value) }

    /**
     * Console affichée dans la bibliothèque : nom d'énumération `ConsoleType`,
     * ou `all` pour ne rien filtrer.
     */
    var libraryConsoleFilter: String
        get() = prefs.getString("library_console_filter", "all") ?: "all"
        set(value) = prefs.edit { putString("library_console_filter", value) }

    // ---- Réglages par jeu ----

    /**
     * Type de mémoire de sauvegarde imposé pour un jeu Game Boy Advance
     * (nom d'énumération `GbaSaveType`), ou `null` pour laisser la détection
     * automatique décider.
     */
    fun forcedSaveType(romSha256: String): String? =
        prefs.getString("game_save_type_$romSha256", null)

    fun setForcedSaveType(romSha256: String, type: String?) {
        prefs.edit {
            if (type == null) remove("game_save_type_$romSha256")
            else putString("game_save_type_$romSha256", type)
        }
    }

    /**
     * Présence imposée de l'horloge de cartouche pour un jeu Game Boy Advance,
     * ou `null` pour laisser la détection automatique décider.
     *
     * La détection cherche la bibliothèque Seiko des cartouches d'origine :
     * elle ne peut rien affirmer d'une ROM modifiée, dont certaines pilotent
     * l'horloge sans porter cette signature. Sans ce réglage, un tel jeu
     * annonce que l'heure est illisible et le joueur n'a aucun recours.
     */
    fun forcedRtc(romSha256: String): Boolean? =
        if (prefs.contains(rtcKey(romSha256))) prefs.getBoolean(rtcKey(romSha256), false) else null

    fun setForcedRtc(romSha256: String, forced: Boolean?) {
        prefs.edit {
            if (forced == null) remove(rtcKey(romSha256)) else putBoolean(rtcKey(romSha256), forced)
        }
    }

    private fun rtcKey(romSha256: String) = "game_rtc_$romSha256"
}
