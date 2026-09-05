package com.ravenemu.app.settings

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.ravenemu.app.R
import com.ravenemu.app.RavenActivity
import com.ravenemu.settings.AppSettings
import com.ravenemu.emulation.api.display.MonochromeDisplayProfiles
import com.ravenemu.storage.RomIndexStore

/**
 * Onglet Paramètres, rangé en quatre écrans : Général, Audio, Vidéo, Interface.
 *
 * Le premier niveau ne contient que ces quatre entrées ; tous les réglages
 * vivent dans l'un d'eux. Déroulés à la file, ils obligeaient à parcourir tout
 * l'écran pour savoir si un réglage existait.
 *
 * La navigation est celle d'`androidx.preference` : un `PreferenceScreen`
 * imbriqué prévient l'activité, qui remplace le fragment par le même, monté sur
 * la clé de l'écran demandé. Le titre affiché suit la pile de retour, seul
 * repère disponible dans une fenêtre immersive sans barre d'action.
 */
class SettingsActivity :
    RavenActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener { showCurrentTitle() }
        // Après une rotation, la pile est restaurée sans changer : l'écouteur
        // ne se déclenche pas et le titre resterait celui de la racine.
        showCurrentTitle()
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment.forScreen(pref.key))
            // Le nom de l'entrée porte le titre à afficher : la pile de retour
            // devient ainsi la seule source du fil d'Ariane, sans état parallèle
            // à garder cohérent.
            .addToBackStack(pref.title?.toString())
            .commit()
        return true
    }

    private fun showCurrentTitle() {
        val manager = supportFragmentManager
        val title = if (manager.backStackEntryCount == 0) {
            getString(R.string.settings_title)
        } else {
            manager.getBackStackEntryAt(manager.backStackEntryCount - 1).name
                ?: getString(R.string.settings_title)
        }
        findViewById<TextView>(R.id.settingsTitle).text = title
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
            // Le câblage qui suit vaut pour l'arborescence entière. Monté sur
            // un écran donné, `findPreference` ne trouve que ce qu'il contient
            // et les autres appels ne font rien : c'est voulu, et c'est ce qui
            // permet de ne pas dupliquer ce code par écran.

            findPreference<ListPreference>("video_screen_profile")?.let { preference ->
                preference.entries =
                    MonochromeDisplayProfiles.all.map { it.displayName }.toTypedArray()
                preference.entryValues =
                    MonochromeDisplayProfiles.all.map { it.id }.toTypedArray()
                // Un profil retiré du catalogue peut rester enregistré chez un
                // joueur qui l'avait choisi. La liste n'a alors aucune entrée
                // correspondante : le résumé s'affiche vide et la boîte de
                // dialogue ne coche rien. Le moteur, lui, applique déjà le
                // profil par défaut — la préférence est ramenée dessus pour que
                // l'écran montre ce qui est réellement appliqué.
                if (MonochromeDisplayProfiles.all.none { it.id == preference.value }) {
                    preference.value = MonochromeDisplayProfiles.default.id
                }
            }

            findPreference<Preference>("controls_reset_layouts")
                ?.setOnPreferenceClickListener {
                    // Une disposition par orientation et par console : la Game
                    // Boy Advance a ses propres gâchettes L/R.
                    for (orientation in listOf("portrait", "landscape")) {
                        for (consoleKey in listOf("gb", "gba")) {
                            settings.resetControlLayout("${orientation}_$consoleKey")
                        }
                    }
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_layouts_reset_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                    true
                }

            findPreference<Preference>("controls_skin_manager")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), ControllerSkinsActivity::class.java))
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

        companion object {
            /**
             * Fragment monté sur un écran imbriqué.
             *
             * `PreferenceFragmentCompat` lit lui-même cet argument et le rend
             * dans `rootKey` : le même fragment sert donc les cinq écrans, et
             * le câblage ci-dessus n'est écrit qu'une fois.
             */
            fun forScreen(key: String): SettingsFragment = SettingsFragment().apply {
                arguments = Bundle().apply {
                    putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key)
                }
            }
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
