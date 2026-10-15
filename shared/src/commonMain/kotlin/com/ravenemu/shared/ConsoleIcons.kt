package com.ravenemu.shared

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Library page pictograms, drawn for RavenEmu on a 24x24 grid: a portrait
 * handheld for Game Boy, the same with a color screen for Game Boy Color, a
 * landscape handheld for Game Boy Advance and a grid for every game. Simple
 * generic shapes, no manufacturer logo or artwork.
 */
@Composable
fun ConsoleIcon(page: LibraryPage, tint: Color, active: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val origin = Offset((size.width - 24f * unit) / 2f, (size.height - 24f * unit) / 2f)
        fun at(x: Float, y: Float) = Offset(origin.x + x * unit, origin.y + y * unit)
        fun box(w: Float, h: Float) = Size(w * unit, h * unit)
        val stroke = Stroke(width = 1.6f * unit)
        when (page) {
            LibraryPage.ALL -> {
                for (x in listOf(3.5f, 13f)) for (y in listOf(3.5f, 13f)) {
                    drawRoundRect(tint, at(x, y), box(7.5f, 7.5f),
                        CornerRadius(1.8f * unit), style = stroke)
                }
            }
            LibraryPage.GB, LibraryPage.GBC -> {
                drawRoundRect(tint, at(5.8f, 1.8f), box(12.4f, 20.4f),
                    CornerRadius(2.2f * unit), style = stroke)
                val screenTopLeft = at(8.4f, 4.4f)
                val screenSize = box(7.2f, 6.4f)
                if (page == LibraryPage.GBC) {
                    drawRoundRect(
                        Brush.horizontalGradient(
                            ColorScreen.map { it.copy(alpha = if (active) 1f else 0.5f) },
                            startX = screenTopLeft.x, endX = screenTopLeft.x + screenSize.width,
                        ),
                        screenTopLeft, screenSize, CornerRadius(0.8f * unit),
                    )
                } else {
                    drawRoundRect(tint, screenTopLeft, screenSize, CornerRadius(0.8f * unit))
                }
                dpad(at(10f, 16.2f), unit, tint)
                drawCircle(tint, 1f * unit, at(14.6f, 17f))
                drawCircle(tint, 1f * unit, at(16.2f, 15.2f))
            }
            LibraryPage.GBA -> {
                drawRoundRect(tint, at(1.6f, 5.8f), box(20.8f, 12.4f),
                    CornerRadius(4f * unit), style = stroke)
                drawRoundRect(tint, at(7.4f, 8.2f), box(9.2f, 7.6f), CornerRadius(0.8f * unit))
                dpad(at(4.6f, 12f), unit, tint, reach = 2.1f)
                drawCircle(tint, 1f * unit, at(18.6f, 12.9f))
                drawCircle(tint, 1f * unit, at(20.1f, 10.9f))
            }
        }
    }
}

private val ColorScreen = listOf(Color(0xFFE5566E), Color(0xFFF2C14E), Color(0xFF4EC9E0))

private fun DrawScope.dpad(centre: Offset, unit: Float, tint: Color, reach: Float = 2.6f) {
    val arm = 1.1f * unit
    val span = reach * unit
    drawRect(tint, Offset(centre.x - span, centre.y - arm / 2f), Size(span * 2f, arm))
    drawRect(tint, Offset(centre.x - arm / 2f, centre.y - span), Size(arm, span * 2f))
}
