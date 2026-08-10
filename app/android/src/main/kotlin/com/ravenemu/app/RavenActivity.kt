package com.ravenemu.app

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Base commune des écrans RavenEmu.
 *
 * Toutes les activités occupent la fenêtre entière et laissent Android
 * afficher temporairement ses barres par un geste depuis un bord. Le mode est
 * réappliqué aux moments où le système peut l'abandonner durablement (retour
 * dans l'application, reprise de focus ou changement de configuration).
 */
abstract class RavenActivity : AppCompatActivity() {

    /**
     * Les écrans ordinaires sont écartés des encoches automatiquement.
     * Un écran qui distribue lui-même ces insets peut désactiver ce traitement.
     */
    protected open val applyDisplayCutoutInsetsToContent: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (applyDisplayCutoutInsetsToContent) {
            applyDisplayCutoutInsets()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyDisplayCutoutInsets() {
        val content = findViewById<View>(android.R.id.content)
        val initialLeft = content.paddingLeft
        val initialTop = content.paddingTop
        val initialRight = content.paddingRight
        val initialBottom = content.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                initialLeft + cutout.left,
                initialTop + cutout.top,
                initialRight + cutout.right,
                initialBottom + cutout.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
