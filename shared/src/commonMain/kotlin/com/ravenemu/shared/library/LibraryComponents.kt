package com.ravenemu.shared.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravenemu.shared.theme.ConsoleIcon
import com.ravenemu.shared.theme.RavenAccent
import com.ravenemu.shared.theme.RavenGlyph
import com.ravenemu.shared.theme.RavenIcon
import com.ravenemu.shared.theme.RavenIconType
import com.ravenemu.shared.theme.RavenMuted
import com.ravenemu.shared.theme.RavenOutline
import com.ravenemu.shared.theme.RavenSecondary
import com.ravenemu.shared.theme.RavenSurface
import com.ravenemu.shared.theme.RavenText

@Composable
internal fun LibraryToolbar(
    title: String,
    importEnabled: Boolean,
    listMode: Boolean,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onToggleView: () -> Unit,
    onImportFolder: (() -> Unit)?,
    /** Reads the library again; null where the host cannot. */
    onRefresh: (() -> Unit)?,
    /** One archive with the saves of every game that has one. */
    onExportAllSaves: (() -> Unit)?,
    /** No import, refresh or transfer running. */
    libraryIdle: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Text(
            title,
            Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = RavenText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
            // Exact RavenEmu 0.8.1 toolbar icon, white and borderless; + and ⋮ unchanged.
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                RavenIcon(RavenIconType.SETTINGS, RavenText)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
            IconButton(
                onClick = onAdd,
                enabled = importEnabled,
                modifier = Modifier.size(48.dp),
            ) {
                RavenIcon(RavenGlyph.ADD, if (importEnabled) RavenAccent else RavenMuted)
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Plus d'options" },
                ) { RavenIcon(RavenGlyph.MORE, RavenText) }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(RavenSurface),
                ) {
                    DropdownMenuItem(
                        text = { Text(if (listMode) "Vue grille" else "Vue liste", color = RavenText) },
                        onClick = { menuOpen = false; onToggleView() },
                    )
                    if (onImportFolder != null) {
                        DropdownMenuItem(
                            text = { Text("Importer un dossier", color = RavenText) },
                            enabled = importEnabled,
                            onClick = { menuOpen = false; onImportFolder() },
                        )
                    }
                    if (onRefresh != null) {
                        DropdownMenuItem(
                            text = { Text("Actualiser la bibliothèque de jeux", color = RavenText) },
                            enabled = libraryIdle,
                            onClick = { menuOpen = false; onRefresh() },
                        )
                    }
                    if (onExportAllSaves != null) {
                        DropdownMenuItem(
                            text = { Text("Exporter toutes les sauvegardes (.zip)", color = RavenText) },
                            enabled = libraryIdle,
                            onClick = { menuOpen = false; onExportAllSaves() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = RavenText, fontSize = 15.sp),
        cursorBrush = SolidColor(RavenAccent),
        modifier = Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .height(44.dp)
            .border(1.dp, RavenOutline.copy(alpha = 0.8f),
                RoundedCornerShape(14.dp))
            .background(RavenSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RavenIcon(RavenGlyph.SEARCH, RavenMuted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Rechercher un jeu", color = RavenMuted, fontSize = 15.sp)
                    }
                    field()
                }
            }
        },
    )
}

@Composable
internal fun LibraryPageContent(
    entries: List<GameEntry>,
    covers: Map<String, ImageBitmap>,
    emptyMessage: String,
    listMode: Boolean,
    onSelect: (GameEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyMessage,
                Modifier.padding(32.dp),
                textAlign = TextAlign.Center,
                color = RavenMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
    } else if (listMode) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(RavenSurface.copy(alpha = 0.75f),
                            RoundedCornerShape(10.dp))
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArtwork(
                        entry.platform, covers[entry.id],
                        Modifier.width(44.dp).height(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(entry.title, color = RavenText, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(platformLabel(entry.platform), color = RavenMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 106.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                Column(
                    modifier = Modifier.clickable { onSelect(entry) }
                        .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverArtwork(entry.platform, covers[entry.id],
                        Modifier.fillMaxWidth().aspectRatio(6f / 7f))
                    Spacer(Modifier.height(6.dp))
                    Text(entry.title, color = RavenSecondary, fontSize = 11.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        lineHeight = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Display imported artwork; never substitute invented or downloaded game covers. */
@Composable
internal fun CoverArtwork(
    platform: GamePlatform,
    bitmap: ImageBitmap?,
    modifier: Modifier,
) {
    if (bitmap == null) {
        CoverPlaceholder(platform, modifier)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Jaquette personnalisée",
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, RavenOutline.copy(alpha = 0.75f),
                    RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

/* No invented artwork. A neutral placeholder is used until covers are migrated. */
@Composable
private fun CoverPlaceholder(platform: GamePlatform, modifier: Modifier) {
    Box(
        modifier.border(1.dp, RavenOutline.copy(alpha = 0.75f),
            RoundedCornerShape(10.dp))
            .background(RavenSurface, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.width(24.dp).height(3.dp)
                .background(RavenAccent.copy(alpha = 0.65f), CircleShape))
            Text(
                platform.name,
                color = RavenSecondary.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** One console pictogram per library page, instead of dots; tap to switch. */
@Composable
internal fun PageIndicator(pages: List<LibraryPage>, currentPage: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pages.size > 1) {
            pages.forEachIndexed { index, page ->
                val active = index == currentPage
                Box(
                    Modifier.padding(horizontal = 4.dp).size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) RavenAccent.copy(alpha = 0.16f) else Color.Transparent)
                        .selectable(selected = active, role = Role.Tab) { onSelect(index) }
                        .semantics { contentDescription = page.title },
                    contentAlignment = Alignment.Center,
                ) {
                    ConsoleIcon(
                        page = page,
                        tint = if (active) RavenAccent else RavenMuted,
                        active = active,
                        modifier = Modifier.size(if (active) 24.dp else 21.dp),
                    )
                }
            }
        }
    }
}

internal fun platformLabel(platform: GamePlatform): String = when (platform) {
    GamePlatform.GB -> "Game Boy"
    GamePlatform.GBC -> "Game Boy Color"
    GamePlatform.GBA -> "Game Boy Advance"
}
