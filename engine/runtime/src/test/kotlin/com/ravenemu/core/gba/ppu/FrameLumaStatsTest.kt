package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mesure de la dynamique de la trame produite par le moteur.
 *
 * Un voile signalé à l'écran est une dynamique comprimée : le noir n'est plus
 * noir. Cette mesure sert à savoir **où** il naît. Le moteur produit-il déjà
 * l'image délavée, ou la sort-il propre pour la voir se ternir après lui ?
 * Sans ce chiffre, la question se tranche à l'œil, donc pas du tout.
 *
 * Ces tests figent ce que la mesure doit dire d'images dont on connaît la
 * couleur exacte, et le fait qu'elle ne coûte rien tant qu'on ne la demande pas.
 */
class FrameLumaStatsTest {

    /**
     * Fait tourner une machine dont l'arrière-plan porte [couleur] (BGR555 sur
     * huit bits) jusqu'à ce qu'une trame complète soit mesurée.
     */
    private fun mesurer(couleur: Int, actif: Boolean = true): GbaMachine {
        val machine = GbaMachine(SyntheticRom.build(SyntheticRom.backdropProgram(couleur)))
        machine.ppu.collectFrameStats = actif
        // Deux trames : la première remplit l'image, la seconde la mesure au
        // passage de VCOUNT à zéro.
        repeat(2) { machine.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }
        return machine
    }

    @Test
    fun `une image noire donne une luminance nulle`() {
        val ppu = mesurer(0x00).ppu
        assertEquals(0, ppu.frameLumaMin)
        assertEquals(0, ppu.frameLumaMax)
        assertEquals(0, ppu.frameLumaMean)
    }

    @Test
    fun `une image unie donne trois fois la meme valeur`() {
        // Rouge saturé : `0x1F` sur cinq bits devient 255 sur huit, et la
        // pondération Rec. 709 lui laisse 54/256 de luminance.
        val ppu = mesurer(0x1F).ppu
        val attendu = (54 * 255) ushr 8
        assertEquals(attendu, ppu.frameLumaMin)
        assertEquals(attendu, ppu.frameLumaMax)
        assertEquals(attendu, ppu.frameLumaMean, "Une image unie n'a qu'une seule luminance")
    }

    @Test
    fun `un ecran blanc force atteint la luminance maximale`() {
        // `DISPCNT` bit 7 : le matériel sort du blanc pur, sans consulter la
        // moindre palette. C'est la borne haute de la mesure.
        val machine = GbaMachine(SyntheticRom.build())
        machine.ppu.collectFrameStats = true
        machine.bus.write16(0x0400_0000, 0x0080)
        repeat(2) { machine.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }

        assertEquals(255, machine.ppu.frameLumaMax)
        assertEquals(255, machine.ppu.frameLumaMin, "L'écran blanc forcé est uniforme")
    }

    @Test
    fun `la mesure desactivee ne renseigne rien`() {
        // La garantie de coût nul : aucun balayage, donc aucune valeur. C'est
        // aussi ce qui empêche de lire une mesure périmée pour une mesure vraie.
        val ppu = mesurer(0x1F, actif = false).ppu
        assertEquals(0, ppu.frameLumaMin)
        assertEquals(0, ppu.frameLumaMax)
        assertEquals(0, ppu.frameLumaMean)
    }

    @Test
    fun `la mesure suit le moteur quand l'image change`() {
        val machine = GbaMachine(SyntheticRom.build(SyntheticRom.backdropProgram(0x00)))
        machine.ppu.collectFrameStats = true
        repeat(2) { machine.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }
        assertEquals(0, machine.ppu.frameLumaMax, "L'image doit d'abord être noire")

        // Le jeu repeint son arrière-plan en blanc : la mesure doit suivre dès
        // la trame suivante, sans quoi elle décrirait une image déjà remplacée.
        machine.bus.write16(0x0500_0000, 0x7FFF)
        repeat(2) { machine.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }
        assertTrue(
            machine.ppu.frameLumaMin > 250,
            "Luminance ${machine.ppu.frameLumaMin} : l'image blanche n'est pas mesurée",
        )
    }

    @Test
    fun `la mesure distingue une image delavee d'une image contrastee`() {
        // Le cœur du sujet. Deux images de même luminance moyenne : l'une porte
        // du noir et du blanc, l'autre un gris uni. Seul `min` les sépare — et
        // c'est exactement ce qu'on cherche à voir quand un voile est signalé.
        val contrastee = GbaMachine(SyntheticRom.build())
        contrastee.ppu.collectFrameStats = true
        peindreMoitieBlanche(contrastee)
        repeat(2) { contrastee.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }

        val delavee = mesurer(0x00).let { noire ->
            // Gris moyen uni : `0x0F` sur les trois composantes.
            noire.bus.write16(0x0500_0000, 0x3DEF)
            repeat(2) { noire.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }
            noire
        }

        assertEquals(0, contrastee.ppu.frameLumaMin, "L'image contrastée doit garder son noir")
        assertTrue(
            delavee.ppu.frameLumaMin > 100,
            "Le gris uni doit relever le plancher, mesuré ${delavee.ppu.frameLumaMin}",
        )
        assertTrue(
            contrastee.ppu.frameLumaMax > delavee.ppu.frameLumaMax,
            "L'image contrastée doit garder un blanc plus clair que le gris uni",
        )
    }

    /**
     * Mode 3 : la moitié droite de chaque ligne est peinte en blanc, la gauche
     * reste noire. L'image porte alors les deux extrêmes.
     */
    private fun peindreMoitieBlanche(machine: GbaMachine) {
        machine.bus.write16(0x0400_0000, 0x0403) // mode 3, BG2 activé
        for (y in 0 until 160) {
            for (x in 120 until 240) {
                machine.bus.write16(0x0600_0000 + (y * 240 + x) * 2, 0x7FFF)
            }
        }
    }
}
