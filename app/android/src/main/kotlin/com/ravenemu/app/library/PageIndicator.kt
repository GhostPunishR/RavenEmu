package com.ravenemu.app.library

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ravenemu.app.R

/**
 * Points de pagination sous la bibliothèque : un par console, le point plein
 * marquant la page ouverte.
 *
 * Une barre d'onglets nommerait les consoles au prix d'un bandeau permanent,
 * alors que le nom de la page est déjà dans la barre du haut. Les points disent
 * la seule chose qui manque : combien de pages, et où l'on se trouve.
 *
 * Vue dessinée à la main plutôt qu'assemblée : trois cercles n'ont pas besoin
 * d'une hiérarchie de vues, et rien ici ne se mesure ni ne se recycle.
 */
class PageIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayon = 3.5f * resources.displayMetrics.density
    private val ecart = 14f * resources.displayMetrics.density
    private val couleurActive = ContextCompat.getColor(context, R.color.raven_accent)
    private val couleurInactive = ContextCompat.getColor(context, R.color.raven_outline)

    private var pages = 0
    private var courante = 0

    /**
     * Fixe le nombre de pages et celle qui est ouverte. Sous deux pages, la
     * vue disparaît : un point unique n'apprend rien et prend une ligne.
     */
    fun setPages(nombre: Int, selectionnee: Int) {
        pages = nombre
        courante = selectionnee.coerceIn(0, (nombre - 1).coerceAtLeast(0))
        visibility = if (nombre > 1) VISIBLE else GONE
        invalidate()
    }

    /** Change la page marquée sans toucher à leur nombre. */
    fun setCurrent(index: Int) {
        if (index == courante) return
        courante = index.coerceIn(0, (pages - 1).coerceAtLeast(0))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (pages <= 1) return
        val largeurTotale = ecart * (pages - 1)
        var x = (width - largeurTotale) / 2f
        val y = height / 2f
        for (index in 0 until pages) {
            peinture.color = if (index == courante) couleurActive else couleurInactive
            canvas.drawCircle(x, y, rayon, peinture)
            x += ecart
        }
    }
}
