package com.ravenemu.android.deltaskin

import android.content.Context
import android.net.Uri
import com.ravenemu.shared.DeltaSkinEntry
import com.ravenemu.shared.GamePlatform

/**
 * Delta skins for the multiplatform app: import, list, delete, and the skin
 * chosen for each console (GB/GBC share one, as in Delta and RavenEmu 0.8.1). Its own
 * storage and preferences: RavenEmu 0.8.1's installed skins are left untouched.
 * Every call does disk I/O: never on the main thread.
 */
class DeltaSkins(context: Context) {
    private val store = AndroidDeltaSkinArchiveImporter(context.applicationContext, AndroidDeltaSkinPdfInspector)
    private val prefs = context.applicationContext
        .getSharedPreferences("ravenemu_delta_skins", Context.MODE_PRIVATE)

    /** Imports a .deltaskin and selects it for its console. Returns the message to show. */
    fun import(uri: Uri): String {
        var prepared: DeltaSkinPreparedImport? = null
        try {
            prepared = store.prepare(uri)
            var result = store.repository.install(prepared, replaceExisting = false)
            if (result is DeltaSkinInstallResult.Conflict) {
                // Same identifier, new archive: an update of that skin.
                result = store.repository.install(prepared, replaceExisting = true)
            }
            return when (result) {
                is DeltaSkinInstallResult.Installed -> {
                    select(result.skin.metadata.console, result.skin.metadata.manifest.identifier)
                    "Skin importé et sélectionné"
                }
                is DeltaSkinInstallResult.Replaced -> {
                    result.previousArchiveSha256.let(DeltaSkinPdfRenderer::invalidate)
                    select(result.skin.metadata.console, result.skin.metadata.manifest.identifier)
                    "Skin mis à jour et sélectionné"
                }
                is DeltaSkinInstallResult.Duplicate -> "Ce skin est déjà installé"
                is DeltaSkinInstallResult.Conflict -> message(DeltaSkinErrorCode.IDENTIFIER_ALREADY_INSTALLED)
            }
        } catch (error: DeltaSkinException) {
            return message(error.code)
        } finally {
            prepared?.let { runCatching { store.repository.discard(it) } }
        }
    }

    fun entries(): List<DeltaSkinEntry> = store.repository.list().map { skin ->
        val console = skin.metadata.console
        DeltaSkinEntry(
            id = skin.metadata.manifest.identifier,
            name = skin.metadata.manifest.name,
            console = if (console == DeltaSkinConsole.GBA) "Game Boy Advance" else "Game Boy / Game Boy Color",
            selected = selected(console) == skin.metadata.manifest.identifier,
        )
    }

    /** Use this skin for its console, or go back to the classic controls. */
    fun toggle(identifier: String) {
        val skin = store.repository.findByIdentifier(identifier) ?: return
        val console = skin.metadata.console
        select(console, if (selected(console) == identifier) null else identifier)
    }

    fun delete(identifier: String) {
        val removed = store.repository.delete(identifier) ?: return
        DeltaSkinPdfRenderer.invalidate(removed.metadata.archiveSha256)
        for (console in DeltaSkinConsole.entries) {
            if (selected(console) == identifier) select(console, null)
        }
    }

    /**
     * What the player shows for this game's console, or null for the classic
     * controls. A skin that no longer loads is unselected (as in RavenEmu 0.8.1).
     */
    fun configuration(platform: GamePlatform, haptics: Boolean): DeltaSkinControllerConfiguration? {
        val console = consoleOf(platform)
        val identifier = selected(console) ?: return null
        val installed = store.repository.findByIdentifier(identifier)
        val portrait = installed?.metadata?.manifest?.representations?.iphone
        val assets = if (installed == null || installed.metadata.console != console || portrait == null) {
            emptyMap()
        } else buildMap {
            for (kind in DeltaSkinRepresentationKind.entries) {
                val representation = portrait.portrait(kind) ?: continue
                val file = installed.assetFile(kind) ?: continue
                put(kind, DeltaSkinControllerAsset(representation, file))
            }
        }
        if (installed == null || assets.isEmpty()) {
            select(console, null)
            return null
        }
        return DeltaSkinControllerConfiguration(
            skinSha256 = installed.metadata.archiveSha256,
            console = console,
            assets = assets,
            preference = DeltaSkinRepresentationPreference.AUTOMATIC,
            nativeScreenSize = if (console == DeltaSkinConsole.GBA) {
                DeltaSkinSize(240.0, 160.0)
            } else {
                DeltaSkinSize(160.0, 144.0)
            },
            hapticFeedback = haptics,
            visualFeedback = true,
        )
    }

    /** A skin failed while playing: back to the classic controls for that console. */
    fun fallback(platform: GamePlatform, skinSha256: String?) {
        skinSha256?.let(DeltaSkinPdfRenderer::invalidate)
        select(consoleOf(platform), null)
    }

    private fun selected(console: DeltaSkinConsole): String? = prefs.getString(key(console), null)

    private fun select(console: DeltaSkinConsole, identifier: String?) {
        prefs.edit().apply { if (identifier == null) remove(key(console)) else putString(key(console), identifier) }.apply()
    }

    private fun key(console: DeltaSkinConsole) = "selected_" + console.name.lowercase()

    private fun consoleOf(platform: GamePlatform) =
        if (platform == GamePlatform.GBA) DeltaSkinConsole.GBA else DeltaSkinConsole.GB_GBC

    private fun message(code: DeltaSkinErrorCode): String = when (code) {
        DeltaSkinErrorCode.INVALID_EXTENSION -> "Le fichier doit porter l'extension .deltaskin"
        DeltaSkinErrorCode.MANIFEST_MISSING -> "Le fichier info.json est absent"
        DeltaSkinErrorCode.MANIFEST_DUPLICATE -> "L'archive contient plusieurs info.json"
        DeltaSkinErrorCode.UNSUPPORTED_CONSOLE -> "Cette console n'est pas prise en charge"
        DeltaSkinErrorCode.PORTRAIT_REPRESENTATION_MISSING -> "Aucune représentation iPhone portrait compatible"
        DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING -> "L'image PDF portrait est absente"
        DeltaSkinErrorCode.PDF_INVALID, DeltaSkinErrorCode.PDF_MULTIPLE_PAGES ->
            "Le PDF du skin est illisible ou contient plusieurs pages"
        DeltaSkinErrorCode.ARCHIVE_TOO_LARGE, DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE,
        DeltaSkinErrorCode.ENTRY_TOO_LARGE, DeltaSkinErrorCode.TOO_MANY_ENTRIES,
        DeltaSkinErrorCode.MANIFEST_TOO_LARGE -> "Le skin est trop volumineux"
        DeltaSkinErrorCode.IDENTIFIER_ALREADY_INSTALLED -> "Un skin portant cet identifiant existe déjà"
        DeltaSkinErrorCode.STORAGE_INSUFFICIENT, DeltaSkinErrorCode.STORAGE_ERROR ->
            "Le stockage disponible est insuffisant ou inaccessible"
        else -> "Archive .deltaskin invalide"
    }
}
