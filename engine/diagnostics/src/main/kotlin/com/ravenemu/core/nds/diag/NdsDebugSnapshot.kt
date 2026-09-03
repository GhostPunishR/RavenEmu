package com.ravenemu.core.nds.diag

/**
 * Ce que la Nintendo DS a rencontré et n'a pas su faire.
 *
 * Un écran noir a plusieurs causes possibles, et rien à l'écran ne les
 * distingue : un programme qui n'avance plus, un plan que ce moteur ne dessine
 * pas, un registre qu'il ignore. Ce relevé les sépare, et c'est le seul moyen
 * de savoir laquelle corriger sans deviner.
 *
 * Il arrive du pont natif comme une suite de nombres dont **l'ordre est le
 * contrat**. [of] le renomme, et refuse une suite d'une autre longueur : mieux
 * vaut n'avoir aucun relevé qu'un relevé décalé, qui accuserait le mauvais
 * organe avec l'aplomb d'une mesure.
 */
data class NdsDebugSnapshot(
    val mainInstructions: Int,
    val secondaryInstructions: Int,
    val mainProgramCounter: Int,
    val secondaryProgramCounter: Int,
    val mainHalted: Boolean,
    val secondaryHalted: Boolean,
    val mainUndefined: Int,
    val mainFirstUndefined: Int,
    val secondaryUndefined: Int,
    val secondaryFirstUndefined: Int,
    val mainUnimplementedIo: Int,
    val mainFirstUnimplementedIo: Int,
    val secondaryUnimplementedIo: Int,
    val secondaryFirstUnimplementedIo: Int,
    val mainUnsupportedSwi: Int,
    val secondaryUnsupportedSwi: Int,
    val mainDisplayControl: Int,
    val secondaryDisplayControl: Int,
    val unimplementedLayers: Int,
    val unimplementedDisplay: Int,
    val unimplementedObjects: Int,
    val nonBlackPixels: Int,
    val screensSwapped: Boolean,
    val cartridgeUnsupported: Int,
    val mainFirstUnsupportedSwi: Int,
    val secondaryFirstUnsupportedSwi: Int,
    val mainMode: Int,
    val secondaryMode: Int,
    val mainStackPointer: Int,
    val secondaryStackPointer: Int,
    val mainLinkRegister: Int,
    val secondaryLinkRegister: Int,
    val mainVectorBase: Int,
    val mainDtcmBase: Int,
    val mainDtcmSize: Int,
    val mainInterruptEnable: Int,
    val mainInterruptFlags: Int,
    val secondaryInterruptEnable: Int,
    val secondaryInterruptFlags: Int,
    val mainSync: Int,
    val secondarySync: Int,
) {
    companion object {
        /**
         * Nombre de valeurs du relevé.
         *
         * Il est écrit ici en toutes lettres et non déduit du nombre de champs :
         * c'est la longueur que le pont natif promet, et une vérification qui se
         * calculerait sur la classe qu'elle contrôle ne contrôlerait rien.
         */
        const val VALUE_COUNT = 41

        /** Renomme la suite du pont, ou rend `null` si elle n'a pas la bonne taille. */
        fun of(values: IntArray?): NdsDebugSnapshot? {
            if (values == null || values.size != VALUE_COUNT) return null
            return NdsDebugSnapshot(
                mainInstructions = values[0],
                secondaryInstructions = values[1],
                mainProgramCounter = values[2],
                secondaryProgramCounter = values[3],
                mainHalted = values[4] != 0,
                secondaryHalted = values[5] != 0,
                mainUndefined = values[6],
                mainFirstUndefined = values[7],
                secondaryUndefined = values[8],
                secondaryFirstUndefined = values[9],
                mainUnimplementedIo = values[10],
                mainFirstUnimplementedIo = values[11],
                secondaryUnimplementedIo = values[12],
                secondaryFirstUnimplementedIo = values[13],
                mainUnsupportedSwi = values[14],
                secondaryUnsupportedSwi = values[15],
                mainDisplayControl = values[16],
                secondaryDisplayControl = values[17],
                unimplementedLayers = values[18],
                unimplementedDisplay = values[19],
                unimplementedObjects = values[20],
                nonBlackPixels = values[21],
                screensSwapped = values[22] != 0,
                cartridgeUnsupported = values[23],
                mainFirstUnsupportedSwi = values[24],
                secondaryFirstUnsupportedSwi = values[25],
                mainMode = values[26],
                secondaryMode = values[27],
                mainStackPointer = values[28],
                secondaryStackPointer = values[29],
                mainLinkRegister = values[30],
                secondaryLinkRegister = values[31],
                mainVectorBase = values[32],
                mainDtcmBase = values[33],
                mainDtcmSize = values[34],
                mainInterruptEnable = values[35],
                mainInterruptFlags = values[36],
                secondaryInterruptEnable = values[37],
                secondaryInterruptFlags = values[38],
                mainSync = values[39],
                secondarySync = values[40],
            )
        }
    }
}
