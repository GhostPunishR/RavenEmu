package com.ravenemu.romlibrary

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

/** Erreur d'import d'une base d'empreintes. */
class ReferenceImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Analyseur de bases d'empreintes au format DAT Logiqx (No-Intro, Redump…).
 *
 * Ces fichiers sont des **métadonnées librement distribuables** décrivant des
 * dumps vérifiés — ils ne contiennent aucune ROM. Chaque `<game>` (ou
 * `<machine>`) fournit un titre et un `<rom>` avec ses empreintes (CRC32,
 * SHA-1, éventuellement SHA-256) et sa taille.
 *
 * Les DAT No-Intro décrivant des dumps officiels vérifiés, les entrées sont
 * classées [ReferenceKind.OFFICIAL] par défaut ; un autre type peut être
 * imposé pour des bases de hacks ou de homebrews.
 *
 * Le parseur est durci contre l'injection d'entités externes (XXE) : la
 * résolution d'entités externes est neutralisée.
 */
object NoIntroDatParser {

    fun parse(xml: String, kind: ReferenceKind = ReferenceKind.OFFICIAL): List<ReferenceEntry> {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val builder = factory.newDocumentBuilder()
            // Neutralise toute récupération d'entité externe (DTD comprise).
            builder.setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw ReferenceImportException("Fichier DAT illisible", e)
        }

        val entries = ArrayList<ReferenceEntry>()
        for (tag in listOf("game", "machine")) {
            val games = document.getElementsByTagName(tag)
            for (i in 0 until games.length) {
                val game = games.item(i)
                val title = attribute(game, "name")?.takeIf { it.isNotBlank() } ?: continue
                val rom = firstChildElement(game, "rom") ?: continue
                val crc = attribute(rom, "crc")
                val sha1 = attribute(rom, "sha1")
                val sha256 = attribute(rom, "sha256")
                if (crc == null && sha1 == null && sha256 == null) continue
                entries += ReferenceEntry(
                    title = title,
                    kind = kind,
                    sha256 = sha256,
                    sha1 = sha1,
                    crc32 = crc,
                    sizeBytes = attribute(rom, "size")?.toLongOrNull(),
                )
            }
        }
        if (entries.isEmpty()) {
            throw ReferenceImportException("Aucune entrée exploitable dans le fichier DAT")
        }
        return entries
    }

    private fun attribute(node: org.w3c.dom.Node?, name: String): String? {
        val attrs = node?.attributes ?: return null
        return attrs.getNamedItem(name)?.nodeValue
    }

    private fun firstChildElement(parent: org.w3c.dom.Node, tag: String): org.w3c.dom.Node? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE && child.nodeName == tag) {
                return child
            }
        }
        return null
    }
}
