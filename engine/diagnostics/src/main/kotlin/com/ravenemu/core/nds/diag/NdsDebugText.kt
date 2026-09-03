package com.ravenemu.core.nds.diag

/**
 * Met le relevé de la Nintendo DS en quelques lignes lisibles.
 *
 * L'ordre suit la question qu'on se pose devant un écran noir, de la plus
 * grossière à la plus fine : la console avance-t-elle, produit-elle une image,
 * et si oui que lui manque-t-il pour être juste. Une ligne dont tous les
 * compteurs sont à zéro n'est pas écrite : ce qui va bien n'a rien à dire.
 */
fun NdsDebugSnapshot.toDiagnosticText(fps: Double, frameTimeMs: Double): String {
    val lignes = mutableListOf<String>()
    lignes += "NDS %.1f FPS  %.1f ms".format(fps, frameTimeMs)
    lignes += "ARM9 %s %,d instr  pc %08X".format(
        if (mainHalted) "arrêt" else "actif",
        mainInstructions,
        mainProgramCounter,
    )
    // Le compteur de programme dit où l'on est échoué, jamais comment on y est
    // arrivé. Le mode dit si une exception a été prise, le registre de lien
    // d'où le saut est parti, et la pile si le processeur en a seulement une.
    lignes += "     %s  sp %08X  lr %08X".format(
        modeName(mainMode),
        mainStackPointer,
        mainLinkRegister,
    )
    lignes += "ARM7 %s %,d instr  pc %08X".format(
        if (secondaryHalted) "arrêt" else "actif",
        secondaryInstructions,
        secondaryProgramCounter,
    )
    lignes += "     %s  sp %08X  lr %08X".format(
        modeName(secondaryMode),
        secondaryStackPointer,
        secondaryLinkRegister,
    )
    lignes += "cp15 vect %08X  dtcm %08X+%X".format(mainVectorBase, mainDtcmBase, mainDtcmSize)
    lignes += "image %,d px allumés  A %04X  B %04X%s".format(
        nonBlackPixels,
        mainDisplayControl,
        secondaryDisplayControl,
        if (screensSwapped) "  écrans échangés" else "",
    )

    val manques = buildList {
        if (unimplementedLayers > 0) add("plans %,d".format(unimplementedLayers))
        if (unimplementedDisplay > 0) add("modes %,d".format(unimplementedDisplay))
        if (unimplementedObjects > 0) add("sprites %,d".format(unimplementedObjects))
    }
    if (manques.isNotEmpty()) lignes += "non dessiné : " + manques.joinToString("  ")

    val ignores = buildList {
        if (mainUnimplementedIo > 0) {
            add("E/S9 %,d dès %08X".format(mainUnimplementedIo, mainFirstUnimplementedIo))
        }
        if (secondaryUnimplementedIo > 0) {
            add("E/S7 %,d dès %08X".format(secondaryUnimplementedIo, secondaryFirstUnimplementedIo))
        }
        if (cartridgeUnsupported > 0) add("cartouche %,d".format(cartridgeUnsupported))
    }
    if (ignores.isNotEmpty()) lignes += "ignoré : " + ignores.joinToString("  ")

    val fautes = buildList {
        if (mainUndefined > 0) {
            add("indéf.9 %,d dès %08X".format(mainUndefined, mainFirstUndefined))
        }
        if (secondaryUndefined > 0) {
            add("indéf.7 %,d dès %08X".format(secondaryUndefined, secondaryFirstUndefined))
        }
        // Le numéro de l'appel accompagne le compte : c'est lui qui dit quel
        // service écrire, quand le compte ne dit que qu'il en manque un.
        if (mainUnsupportedSwi > 0) {
            add("BIOS9 %,d dès n° %02X".format(mainUnsupportedSwi, mainFirstUnsupportedSwi))
        }
        if (secondaryUnsupportedSwi > 0) {
            add("BIOS7 %,d dès n° %02X".format(secondaryUnsupportedSwi, secondaryFirstUnsupportedSwi))
        }
    }
    if (fautes.isNotEmpty()) lignes += "buté : " + fautes.joinToString("  ")

    return lignes.joinToString("\n")
}

/**
 * Nom court du mode du processeur.
 *
 * Les valeurs sont celles que le registre d'état porte, et non un rang : les
 * renommer ici les rend lisibles sans que le relevé ait à les traduire en
 * chiffres. Une valeur inconnue est rendue telle quelle plutôt que muée en
 * « inconnu », qui cacherait ce qu'elle vaut.
 */
private fun modeName(mode: Int): String = when (mode) {
    0x10 -> "user"
    0x11 -> "fiq "
    0x12 -> "irq "
    0x13 -> "svc "
    0x17 -> "abrt"
    0x1b -> "undf"
    0x1f -> "syst"
    else -> "%04X".format(mode)
}
