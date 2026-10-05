package com.ravenemu.deltaskin

import com.ravenemu.emulation.api.EmulatorButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DeltaSkinTestFixtures {
    fun manifest(
        console: DeltaSkinConsole = DeltaSkinConsole.GB_GBC,
        standard: Boolean = true,
        edgeToEdge: Boolean = true,
        landscape: Boolean = false,
        ignoredInput: String? = null,
        partialItemEdges: Boolean = false,
    ): DeltaSkinManifest {
        val standardPortrait = representation(
            mappingHeight = 240.0,
            asset = "iphone_portrait.pdf",
            console = console,
            ignoredInput = ignoredInput,
            partialItemEdges = partialItemEdges,
        )
        val edgePortrait = representation(
            mappingHeight = 274.0,
            asset = "iphone_edgetoedge_portrait.pdf",
            console = console,
            ignoredInput = ignoredInput,
            partialItemEdges = partialItemEdges,
        )
        val standardLandscape = representation(
            mappingHeight = 180.0,
            asset = "iphone_landscape.pdf",
            console = console,
            landscape = true,
        )
        val edgeLandscape = representation(
            mappingHeight = 180.0,
            asset = "iphone_edgetoedge_landscape.pdf",
            console = console,
            landscape = true,
        )
        val suffix = when (console) {
            DeltaSkinConsole.GB_GBC -> "gbc"
            DeltaSkinConsole.GBA -> "gba"
            DeltaSkinConsole.DS -> "ds"
        }
        return DeltaSkinManifest(
            name = "Synthetic ${suffix.uppercase(Locale.ROOT)}",
            identifier = "org.ravenemu.synthetic.$suffix",
            gameTypeIdentifier = console.gameTypeIdentifier,
            debug = false,
            representations = DeltaSkinRepresentations(
                iphone = DeltaSkinIphoneRepresentations(
                    standard = DeltaSkinOrientations(
                        portrait = standardPortrait.takeIf { standard },
                        landscape = standardLandscape.takeIf { landscape },
                    ).takeIf { standard || landscape },
                    edgeToEdge = DeltaSkinOrientations(
                        portrait = edgePortrait.takeIf { edgeToEdge },
                        landscape = edgeLandscape.takeIf { landscape },
                    ).takeIf { edgeToEdge || landscape },
                )
            ),
        )
    }

    fun representation(
        mappingHeight: Double,
        asset: String,
        console: DeltaSkinConsole = DeltaSkinConsole.GB_GBC,
        ignoredInput: String? = null,
        partialItemEdges: Boolean = false,
        landscape: Boolean = false,
    ): DeltaSkinRepresentation {
        val items = mutableListOf(
            DeltaSkinItem(
                inputs = DeltaSkinInputs.Directional(
                    mapOf(
                        "up" to "up",
                        "down" to "down",
                        "left" to "left",
                        "right" to "right",
                    )
                ),
                frame = DeltaSkinFrame(20.0, 65.0, 90.0, 90.0),
                extendedEdges = if (partialItemEdges) {
                    DeltaSkinEdges(left = 12.0)
                } else {
                    DeltaSkinEdges()
                },
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("a")),
                DeltaSkinFrame(260.0, 75.0, 40.0, 40.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("b")),
                DeltaSkinFrame(210.0, 105.0, 40.0, 40.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("a", "b")),
                DeltaSkinFrame(260.0, 145.0, 45.0, 25.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("select")),
                DeltaSkinFrame(90.0, 150.0, 50.0, 20.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("start")),
                DeltaSkinFrame(150.0, 150.0, 50.0, 20.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("menu")),
                DeltaSkinFrame(145.0, 10.0, 30.0, 20.0),
            ),
        )
        // Les gâchettes suivent la console plutôt qu'un nom : deux consoles les
        // ont, et une troisième s'y ajouterait sans toucher à cette ligne.
        if (EmulatorButton.L in console.buttons) {
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("l")),
                DeltaSkinFrame(10.0, 35.0, 55.0, 20.0),
            )
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("r")),
                DeltaSkinFrame(255.0, 35.0, 55.0, 20.0),
            )
        }
        if (EmulatorButton.X in console.buttons) {
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("x")),
                DeltaSkinFrame(260.0, 40.0, 30.0, 30.0),
            )
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("y")),
                DeltaSkinFrame(180.0, 75.0, 30.0, 30.0),
            )
            // La zone tactile emprunte la forme d'une croix sans en être une.
            items += DeltaSkinItem(
                DeltaSkinInputs.Directional(
                    mapOf("x" to "touchScreenX", "y" to "touchScreenY")
                ),
                DeltaSkinFrame(0.0, 0.0, 320.0, 60.0),
            )
        }
        ignoredInput?.let { input ->
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf(input)),
                DeltaSkinFrame(115.0, 100.0, 30.0, 20.0),
            )
        }
        return DeltaSkinRepresentation(
            assets = DeltaSkinAssets(resizable = asset),
            mappingSize = DeltaSkinSize(320.0, mappingHeight),
            items = items,
            extendedEdges = DeltaSkinEdges(top = 5.0, bottom = 6.0, left = 7.0, right = 8.0),
            translucent = landscape,
            gameScreenFrame = DeltaSkinFrame(80.0, 0.0, 160.0, 144.0)
                .takeIf { landscape },
            screens = if (landscape) {
                listOf(
                    DeltaSkinScreen(
                        inputFrame = DeltaSkinFrame(0.0, 0.0, 160.0, 144.0),
                        outputFrame = DeltaSkinFrame(80.0, 0.0, 160.0, 144.0),
                    )
                )
            } else {
                emptyList()
            },
        )
    }

    fun createArchive(
        directory: File,
        manifest: DeltaSkinManifest = manifest(),
        includeManifest: Boolean = true,
        extraEntries: Map<String, ByteArray> = emptyMap(),
        pdfPages: Map<String, Int> = emptyMap(),
        omitAssets: Set<String> = emptySet(),
        fileName: String = "synthetic.deltaskin",
    ): File {
        val archive = File(directory, fileName)
        val entries = linkedMapOf<String, ByteArray>()
        if (includeManifest) {
            entries["info.json"] = DeltaSkinParser.encode(manifest)
                .toByteArray(StandardCharsets.UTF_8)
        }
        referencedAssets(manifest)
            .filterKeys { it !in omitAssets }
            .forEach { (name, mappingSize) ->
                entries[name] = syntheticPdf(
                    pages = pdfPages[name] ?: 1,
                    width = mappingSize.width.toInt(),
                    height = mappingSize.height.toInt(),
                )
        }
        entries.putAll(extraEntries)
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    /**
     * Construit un véritable PDF minimal à la volée. Le document ne contient
     * qu'un rectangle uni et n'embarque donc aucune fixture de marque.
     */
    fun syntheticPdf(
        pages: Int = 1,
        width: Int = 320,
        height: Int = 240,
    ): ByteArray {
        require(pages > 0)
        require(width > 0 && height > 0)
        val pageObjectIds = (0 until pages).map { 3 + it * 2 }
        val objects = mutableListOf<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects +=
            "<< /Type /Pages /Kids [${pageObjectIds.joinToString(" ") { "$it 0 R" }}] " +
            "/Count $pages >>"
        repeat(pages) { index ->
            val pageId = pageObjectIds[index]
            val contentId = pageId + 1
            val stream = "0.10 0.20 0.30 rg\n0 0 $width $height re f\n"
            objects +=
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                "/Resources << >> /Contents $contentId 0 R >>"
            objects +=
                "<< /Length ${stream.toByteArray(StandardCharsets.US_ASCII).size} >>\n" +
                "stream\n$stream" +
                "endstream"
        }

        val output = ByteArrayOutputStream()
        fun write(value: String) {
            output.write(value.toByteArray(StandardCharsets.US_ASCII))
        }
        write("%PDF-1.4\n%RAVENEMU-PAGES=$pages\n%RAVENEMU-SIZE=${width}x$height\n")
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { index, body ->
            val objectId = index + 1
            offsets[objectId] = output.size()
            write("$objectId 0 obj\n$body\nendobj\n")
        }
        val xrefOffset = output.size()
        write("xref\n0 ${objects.size + 1}\n")
        write("0000000000 65535 f \n")
        for (objectId in 1..objects.size) {
            write(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[objectId]))
        }
        write(
            "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n" +
                "startxref\n$xrefOffset\n%%EOF\n"
        )
        return output.toByteArray()
    }

    val pdfInspector = DeltaSkinPdfInspector { file ->
        val text = file.readText(StandardCharsets.US_ASCII)
        if (!text.startsWith("%PDF-")) throw IOException("not a PDF")
        val pages = Regex("%RAVENEMU-PAGES=(\\d+)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 0
        val size = Regex("%RAVENEMU-SIZE=(\\d+)x(\\d+)")
            .find(text)
            ?.groupValues
        DeltaSkinPdfInfo(
            pageCount = pages,
            width = size?.get(1)?.toInt() ?: 0,
            height = size?.get(2)?.toInt() ?: 0,
        )
    }

    fun markFirstFileAsUnixSymlink(archive: File) {
        RandomAccessFile(archive, "rw").use { file ->
            var offset = 0L
            while (offset <= file.length() - 4L) {
                file.seek(offset)
                if (Integer.reverseBytes(file.readInt()) == 0x02014b50) {
                    file.seek(offset + 4)
                    file.writeShort(java.lang.Short.reverseBytes(0x0314.toShort()).toInt())
                    file.seek(offset + 38)
                    val attributes = ((0xa000 or 0x01ff).toLong() shl 16).toInt()
                    file.writeInt(Integer.reverseBytes(attributes))
                    return
                }
                offset++
            }
            error("central directory not found")
        }
    }

    private fun referencedAssets(manifest: DeltaSkinManifest): Map<String, DeltaSkinSize> {
        val iphone = manifest.representations.iphone ?: return emptyMap()
        return listOfNotNull(
            iphone.standard?.portrait,
            iphone.standard?.landscape,
            iphone.edgeToEdge?.portrait,
            iphone.edgeToEdge?.landscape,
        ).associate { representation ->
            requireNotNull(representation.assets.resizable) to representation.mappingSize
        }
    }
}
