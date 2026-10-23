package com.ravenemu.shared.retro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.ravenemu.shared.theme.RavenAccent
import com.ravenemu.shared.theme.RavenBackground
import com.ravenemu.shared.theme.RavenGold
import com.ravenemu.shared.theme.RavenOutline
import com.ravenemu.shared.theme.RavenSecondary
import com.ravenemu.shared.theme.RavenSurface
import com.ravenemu.shared.theme.RavenText

/**
 * RetroAchievements overlays of the player: challenge and tracker lines at
 * the bottom; at the top, the one-at-a-time banner (as on the PC) and, under
 * it, the measured progress of an achievement with the achievement's badge.
 * Shared by the classic layout and Delta skins.
 */
@Composable
internal fun BoxScope.RetroOverlays(
    retroIndicators: List<String>,
    retroNotice: RetroBanner?,
    retroNoticeBadge: ImageBitmap?,
    retroNoticeVisible: Boolean,
    retroProgress: RetroProgress?,
    retroProgressBadge: ImageBitmap?,
    popupPosition: String = "top",
) {
    if (retroIndicators.isNotEmpty()) {
        // Persistent, non-interactive: challenges and trackers.
        Column(
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 10.dp, start = 12.dp, end = 12.dp)
                .background(RavenSurface.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (label in retroIndicators.take(4)) {
                Text(label, color = RavenText, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // The banner's badge drops in, then the band unfolds; the reverse to leave.
    val drop = remember { Animatable(0f) }
    val unfold = remember { Animatable(0f) }
    val entering = retroNotice != null && retroNoticeVisible
    LaunchedEffect(retroNotice, entering) {
        if (entering) {
            drop.snapTo(0f)
            unfold.snapTo(0f)
            drop.animateTo(1f, tween(RetroBannerMotion.DROP_MILLIS, easing = BannerEaseOut))
            unfold.animateTo(1f, tween(RetroBannerMotion.UNFOLD_MILLIS, easing = FastOutSlowInEasing))
        } else {
            unfold.animateTo(0f, tween(RetroBannerMotion.UNFOLD_MILLIS, easing = FastOutSlowInEasing))
            drop.animateTo(0f, tween(RetroBannerMotion.DROP_MILLIS, easing = BannerEaseOut))
        }
    }
    // Kept while fading out, so the card leaves with its last content.
    // Plain holder, not state: it only follows the parameters.
    val held = remember { HeldProgress() }
    if (retroProgress != null) {
        held.progress = retroProgress
        held.badge = retroProgressBadge
    }
    val progressAlpha by animateFloatAsState(
        if (retroProgress != null) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "retroProgress",
    )
    val showNotice = retroNotice != null && (entering || drop.value > 0f)
    val progress = held.progress
    val showProgress = progress != null && progressAlpha > 0f
    if (!showNotice && !showProgress) return
    Column(
        Modifier.align(if (popupPosition == "bottom") Alignment.BottomCenter else Alignment.TopCenter)
            .padding(top = 10.dp, bottom = if (retroIndicators.isEmpty()) 10.dp else 90.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showNotice) {
            RetroBannerView(retroNotice, retroNoticeBadge, drop.value, unfold.value,
                fromBottom = popupPosition == "bottom")
        }
        if (showProgress && progress != null) {
            RetroPopupCard("Progression · ${progress.title}", held.badge, progressAlpha,
                detail = progress.detail)
        }
    }
}

private val BannerEaseOut = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/**
 * The banner, as on the PC: a badge square, then a band beside it with the
 * heading over the title. [drop] brings the badge from the screen's edge,
 * [unfold] uncovers the text; the banner stays centered all along. An award
 * has a gold frame and heading.
 */
@Composable
private fun RetroBannerView(banner: RetroBanner, badge: ImageBitmap?, drop: Float, unfold: Float, fromBottom: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    val frame = if (banner.award) RavenGold else RavenOutline
    Row(
        Modifier.graphicsLayer {
            translationY = (1f - drop) * (BANNER_SIZE + 24.dp).toPx() * (if (fromBottom) 1f else -1f)
        }
            .shadow(6.dp, shape)
            .background(RavenBackground.copy(alpha = 0.94f), shape)
            .height(BANNER_SIZE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The badge fills its square, or the star stands in.
        Box(
            Modifier.padding(5.dp).size(BANNER_SIZE - 10.dp).clip(RoundedCornerShape(7.dp))
                .background(RavenSurface).border(2.dp, frame, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (badge != null) {
                Image(badge, "Badge du bandeau", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("★", color = if (banner.award) RavenGold else RavenAccent, fontSize = 24.sp)
            }
        }
        // The text is uncovered as the band unfolds, never drawn past it.
        Box(Modifier.clipToBounds().unfoldWidth(unfold)) {
            Column(Modifier.padding(start = 8.dp, end = 16.dp).widthIn(max = 420.dp)) {
                if (banner.heading.isNotBlank()) {
                    Text(banner.heading, color = if (banner.award) RavenGold else RavenSecondary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (banner.title.isNotBlank()) {
                    Text(banner.title, color = RavenText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val BANNER_SIZE = 56.dp

/** Lays the content out whole but keeps only [fraction] of its width. */
private fun Modifier.unfoldWidth(fraction: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    layout((placeable.width * fraction.coerceIn(0f, 1f)).roundToInt(), placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

private class HeldProgress {
    var progress: RetroProgress? = null
    var badge: ImageBitmap? = null
}

/**
 * The progress card: the achievement's badge, or the star when it is not
 * available, then the text. Fixed badge slot: the card keeps the same size.
 */
@Composable
private fun RetroPopupCard(text: String, badge: ImageBitmap?, alpha: Float, detail: String = "", detailLines: Int = 1) {
    Row(
        Modifier.alpha(alpha)
            .background(RavenSurface, RoundedCornerShape(12.dp))
            .border(1.dp, RavenAccent, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (badge != null) {
                Image(
                    badge, "Badge du succès",
                    Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("★", color = RavenAccent, fontSize = 21.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, color = RavenText, fontSize = 12.sp,
                maxLines = if (detail.isBlank()) 2 else 1, overflow = TextOverflow.Ellipsis)
            // Reserve the second line for the measured value, even when
            // a long achievement title needs ellipsizing on a small screen.
            if (detail.isNotBlank()) {
                Text(detail, color = RavenText, fontSize = 12.sp,
                    maxLines = detailLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
