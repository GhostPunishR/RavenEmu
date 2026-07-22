package com.ravenemu.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
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

    /** Multiplicateur d'avance rapide (2–4). */
    var fastForwardMultiplier: Int
        get() = prefs.getInt("emu_fast_forward", 2).coerceIn(2, 4)
        set(value) = prefs.edit { putInt("emu_fast_forward", value.coerceIn(2, 4)) }

    /** Limiter la vitesse à la cadence native (débridé sinon). */
    var speedLimitEnabled: Boolean
        get() = prefs.getBoolean("emu_speed_limit", true)
        set(value) = prefs.edit { putBoolean("emu_speed_limit", value) }

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

    /** Clé de palette DMG (voir [GameBoyPalettes]). */
    var paletteKey: String
        get() = prefs.getString("video_palette", "classic") ?: "classic"
        set(value) = prefs.edit { putString("video_palette", value) }

    var showPerformanceOverlay: Boolean
        get() = prefs.getBoolean("video_show_fps", false)
        set(value) = prefs.edit { putBoolean("video_show_fps", value) }

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
}
