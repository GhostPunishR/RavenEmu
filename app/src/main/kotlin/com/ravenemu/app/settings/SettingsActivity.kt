package com.ravenemu.app.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.ravenemu.app.R
import com.ravenemu.settings.AppSettings
import com.ravenemu.settings.GameBoyPalettes
import com.ravenemu.storage.RomIndexStore

/** Onglet Paramètres : émulation, vidéo, audio, contrôles, fichiers, bibliothèque, débogage. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var settings: AppSettings

        private val pickSaveDir =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    settings.saveDirectory = uri
                    updateDirectorySummaries()
                }
            }

        private val pickCoversDir =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    settings.coversDirectory = uri
                    updateDirectorySummaries()
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Même fichier de préférences que AppSettings.
            preferenceManager.sharedPreferencesName = "ravenemu_settings"
            setPreferencesFromResource(R.xml.preferences, rootKey)
            settings = AppSettings(requireContext())

            findPreference<ListPreference>("video_palette")?.let { preference ->
                preference.entries =
                    GameBoyPalettes.all.map { it.displayName }.toTypedArray()
                preference.entryValues = GameBoyPalettes.all.map { it.key }.toTypedArray()
            }

            findPreference<Preference>("controls_reset_layouts")
                ?.setOnPreferenceClickListener {
                    for (profile in listOf("portrait", "landscape")) {
                        settings.resetControlLayout(profile)
                    }
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_layouts_reset_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                    true
                }

            findPreference<Preference>("files_sav_dir_picker")
                ?.setOnPreferenceClickListener {
                    pickSaveDir.launch(null)
                    true
                }

            findPreference<Preference>("files_covers_dir_picker")
                ?.setOnPreferenceClickListener {
                    pickCoversDir.launch(null)
                    true
                }

            findPreference<Preference>("library_clear_index")
                ?.setOnPreferenceClickListener {
                    RomIndexStore(requireContext()).clear()
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_index_cleared,
                        Toast.LENGTH_SHORT,
                    ).show()
                    true
                }

            updateDirectorySummaries()
        }

        private fun updateDirectorySummaries() {
            findPreference<Preference>("files_sav_dir_picker")?.summary =
                settings.saveDirectory?.toString()
                    ?: getString(R.string.settings_sav_dir_none)
            findPreference<Preference>("files_covers_dir_picker")?.summary =
                settings.coversDirectory?.toString()
                    ?: getString(R.string.settings_covers_dir_none)
        }
    }
}
