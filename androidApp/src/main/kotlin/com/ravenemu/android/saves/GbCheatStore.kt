package com.ravenemu.android.saves

import android.util.AtomicFile
import com.ravenemu.shared.saves.GbCheat
import com.ravenemu.shared.saves.GbCheatList
import java.io.File
import java.io.FileNotFoundException

/**
 * Each game's cheats, in private storage, keyed by ROM content like its
 * battery save. The file is RavenEmu PC's `<ROM>.cheats` format (see
 * [GbCheatList]): written whole, never half a list.
 */
internal class GbCheatStore(private val directory: File) {
    fun read(romKey: String): List<GbCheat> = try {
        AtomicFile(fileFor(romKey)).openRead().use { stream ->
            val bytes = stream.readBytes()
            require(bytes.size <= MAX_BYTES) { "Liste de codes de triche trop grande." }
            GbCheatList.parse(bytes.decodeToString())
        }
    } catch (_: FileNotFoundException) {
        emptyList()
    }

    fun write(romKey: String, cheats: List<GbCheat>) {
        val atomic = AtomicFile(fileFor(romKey))
        if (cheats.isEmpty()) {
            atomic.delete()
            return
        }
        check(directory.isDirectory || directory.mkdirs()) { "Impossible de créer le dossier des codes de triche." }
        val stream = atomic.startWrite()
        try {
            stream.write(GbCheatList.format(cheats).encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun fileFor(key: String): File {
        require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }) { "Identifiant de ROM invalide." }
        return File(directory, "$key.cheats")
    }

    private companion object {
        const val MAX_BYTES = 1024 * 1024
    }
}
