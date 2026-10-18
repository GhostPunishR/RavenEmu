package com.ravenemu.shared

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RetroAchievements overlays of the player: challenge and tracker lines at
 * the bottom; at the top, the one-at-a-time popup and, under it, the
 * measured progress of an achievement, both with the achievement's badge.
 * Shared by the classic layout and Delta skins.
 */
@Composable
internal fun BoxScope.RetroOverlays(
    retroIndicators: List<String>,
    retroNotice: String?,
    retroNoticeBadge: ImageBitmap?,
    retroNoticeVisible: Boolean,
    retroProgress: RetroProgress?,
    retroProgressBadge: ImageBitmap?,
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
    val noticeAlpha by animateFloatAsState(
        if (retroNotice != null && retroNoticeVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "retroNotice",
    )
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
    val showNotice = retroNotice != null && noticeAlpha > 0f
    val progress = held.progress
    val showProgress = progress != null && progressAlpha > 0f
    if (!showNotice && !showProgress) return
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showNotice) {
            RetroPopupCard(retroNotice.orEmpty(), retroNoticeBadge, noticeAlpha)
        }
        if (showProgress && progress != null) {
            RetroPopupCard("Progression · ${progress.title}", held.badge, progressAlpha,
                detail = progress.detail)
        }
    }
}

private class HeldProgress {
    var progress: RetroProgress? = null
    var badge: ImageBitmap? = null
}

/**
 * One RetroAchievements popup: the achievement's badge, or the star when it
 * is not available (offline, or a notice without achievement), then the text.
 * Fixed badge slot: the popup keeps the same size either way.
 */
@Composable
private fun RetroPopupCard(text: String, badge: ImageBitmap?, alpha: Float, detail: String = "") {
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
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
