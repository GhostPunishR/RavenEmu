package com.ravenemu.shared.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class RavenGlyph { SETTINGS, ADD, MORE, SEARCH, BACK, FAST_FORWARD, REWIND }

/* Vector icons render consistently on Android and iOS without system glyphs. */
@Composable
internal fun RavenIcon(icon: RavenGlyph, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val line = 2.dp.toPx()
        when (icon) {
            RavenGlyph.ADD -> {
                drawLine(tint, Offset(cx, size.height * .16f),
                    Offset(cx, size.height * .84f), strokeWidth = line)
                drawLine(tint, Offset(size.width * .16f, cy),
                    Offset(size.width * .84f, cy), strokeWidth = line)
            }
            RavenGlyph.MORE -> {
                for (fraction in listOf(.24f, .5f, .76f)) {
                    drawCircle(tint, radius = 1.8.dp.toPx(),
                        center = Offset(cx, size.height * fraction))
                }
            }
            RavenGlyph.SEARCH -> {
                drawCircle(tint, radius = size.minDimension * .27f,
                    center = Offset(cx * .88f, cy * .86f),
                    style = Stroke(width = line))
                drawLine(tint, Offset(size.width * .63f, size.height * .63f),
                    Offset(size.width * .85f, size.height * .85f), strokeWidth = line)
            }
            RavenGlyph.BACK -> {
                drawLine(tint, Offset(size.width * .6f, size.height * .18f),
                    Offset(size.width * .28f, cy), strokeWidth = line)
                drawLine(tint, Offset(size.width * .28f, cy),
                    Offset(size.width * .6f, size.height * .82f), strokeWidth = line)
            }
            RavenGlyph.SETTINGS -> {
                drawCircle(tint, radius = size.minDimension * .28f,
                    center = Offset(cx, cy), style = Stroke(width = line))
                drawCircle(tint, radius = size.minDimension * .09f,
                    center = Offset(cx, cy), style = Stroke(width = line))
                for (i in 0..7) {
                    val angle = i.toDouble() * PI / 4
                    val x = cos(angle).toFloat()
                    val y = sin(angle).toFloat()
                    drawLine(tint,
                        Offset(cx + x * size.minDimension * .32f,
                            cy + y * size.minDimension * .32f),
                        Offset(cx + x * size.minDimension * .44f,
                            cy + y * size.minDimension * .44f),
                        strokeWidth = line)
                }
            }
            RavenGlyph.FAST_FORWARD, RavenGlyph.REWIND -> {
                // Two triangles side by side, pointing right or left.
                val forward = icon == RavenGlyph.FAST_FORWARD
                for (base in listOf(.14f, .48f)) {
                    val back = if (forward) base else base + .38f
                    val tip = if (forward) base + .38f else base
                    drawPath(Path().apply {
                        moveTo(size.width * back, size.height * .24f)
                        lineTo(size.width * tip, cy)
                        lineTo(size.width * back, size.height * .76f)
                        close()
                    }, tint)
                }
            }
        }
    }
}
