package com.ravenemu.app.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs

/**
 * Jaquette générée localement quand aucune pochette n'est associée : fond
 * coloré déterministe (dérivé du titre) et initiales du jeu. Aucun contenu
 * téléchargé ni distribué.
 */
object CoverArtGenerator {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    fun generate(title: String, width: Int = 256, height: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor(title))
        val initials = title.split(' ', '-', '_')
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
        paint.textSize = height * 0.32f
        canvas.drawText(initials, width / 2f, height / 2f + paint.textSize / 3f, paint)
        return bitmap
    }

    private fun backgroundColor(title: String): Int {
        val hue = abs(title.hashCode()) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f))
    }
}
