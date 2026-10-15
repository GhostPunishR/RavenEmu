package com.ravenemu.shared

import androidx.compose.ui.graphics.ImageBitmap

/** UI-only save-state slot; host storage remains platform specific. */
data class GbStateSlot(
    /** Zero based: slot 0 corresponds to the original single RavenEmu state. */
    val index: Int,
    val savedAt: String? = null,
    val thumbnail: ImageBitmap? = null,
) {
    val occupied: Boolean get() = savedAt != null
}
