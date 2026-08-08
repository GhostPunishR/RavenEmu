package com.ravenemu.core.gba.state

import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.ppu.GbaPpu
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.SaveStateException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Garantie transactionnelle de la restauration d'état GBA.
 *
 * Un état refusé ne doit rien coûter à la partie en cours : ni un registre, ni
 * un octet de mémoire, ni une image. La restauration s'appliquant à une machine
 * neuve, la vérification porte sur l'identité de la machine active **et** sur
 * son contenu, comparé octet à octet avant et après chaque tentative ratée.
 */
class GbaStateTransactionTest {

    private fun coreEnCours(): KotlinGbaCore {
        val core = KotlinGbaCore()
        core.loadRom(SyntheticRom.build(programWords = SyntheticRom.backdropProgram(0x1F)))
        // Une partie déjà avancée : registres, mémoire et PPU hors de leur
        // valeur de démarrage, sans quoi « intact » ne prouverait rien.
        val machine = core.machine!!
        machine.bus.write32(0x0200_0000, 0x0BAD_F00D)
        machine.bus.write32(0x0300_0100, 0x1234_5678)
        machine.cpu.state.regs[7] = 0x0042_4242
        core.runFrame(IntArray(core.video.pixelCount))
        machine.ppu.tick(12_345)
        return core
    }

    /**
     * Vérifie qu'une tentative de restauration refusée ne laisse aucune trace :
     * même machine, même état sérialisé, même image produite ensuite.
     */
    private fun resteIntact(nom: String, corrompre: (KotlinGbaCore, ByteArray) -> ByteArray) {
        val core = coreEnCours()
        val machineAvant = core.machine!!
        val etatAvant = core.saveState()

        val corrompu = corrompre(core, core.saveState())
        assertFailsWith<SaveStateException>(nom) { core.loadState(corrompu) }

        assertSame(machineAvant, core.machine, "$nom : la machine active a été remplacée")
        assertContentEquals(etatAvant, core.saveState(), "$nom : l'état de la machine a changé")

        // La partie doit rester jouable, pas seulement identique sur le papier.
        val image = IntArray(core.video.pixelCount)
        core.runFrame(image)
        val temoin = KotlinGbaCore().apply {
            loadRom(SyntheticRom.build(programWords = SyntheticRom.backdropProgram(0x1F)))
            loadState(etatAvant)
        }
        val attendue = IntArray(temoin.video.pixelCount)
        temoin.runFrame(attendue)
        assertContentEquals(attendue, image, "$nom : la trame suivante diffère")
    }

    @Test
    fun `un etat tronque laisse la partie intacte`() {
        // Plusieurs points de coupure : en-tête, plein milieu des mémoires, et
        // juste avant les derniers champs.
        for (fraction in listOf(0.1, 0.5, 0.9, 0.99)) {
            resteIntact("troncature à ${(fraction * 100).toInt()} %") { _, etat ->
                etat.copyOf((etat.size * fraction).toInt())
            }
        }
    }

    @Test
    fun `un en-tete invalide laisse la partie intacte`() {
        resteIntact("magic") { _, etat -> etat.also { it[0] = 0 } }
        resteIntact("version") { _, etat -> etat.also { it[5] = 0x7F } }
        resteIntact("console") { _, etat -> etat.also { it[6] = 0x7F } }
        resteIntact("empreinte de ROM") { _, etat ->
            etat.also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
        }
    }

    @Test
    fun `des donnees excedentaires laissent la partie intacte`() {
        resteIntact("octet surnuméraire") { _, etat -> etat + byteArrayOf(0) }
    }

    /**
     * Le cas qui compte : la valeur refusée est lue **après** les mémoires. À ce
     * moment, EWRAM, IWRAM, VRAM, palette, OAM et les registres CPU ont déjà été
     * écrits — dans la machine jetable. La machine active n'en sait rien.
     */
    @Test
    fun `une valeur PPU hors bornes est refusee apres les memoires`() {
        val core = coreEnCours()
        val decalage = decalageChampsPpu(core)

        // Contrôle de l'arithmétique de décalage : sans lui, le test pourrait
        // corrompre un octet quelconque et passer pour de mauvaises raisons.
        val etat = core.saveState()
        assertEquals(
            GbaPpu.STATE_FIELD_COUNT,
            lireInt(etat, decalage - 4),
            "Le décalage calculé ne pointe pas sur le compte de champs PPU",
        )
        assertTrue(
            lireInt(etat, decalage) in 0..227, // 160 lignes visibles + 68 de VBlank
            "Le premier champ PPU devrait être un VCOUNT valide",
        )

        resteIntact("VCOUNT hors bornes") { c, e ->
            e.also { ecrireInt(it, decalageChampsPpu(c), 0x0007_FFFF) }
        }
    }

    @Test
    fun `une taille de sauvegarde incoherente laisse la partie intacte`() {
        // Une cartouche à mémoire Flash : le bloc de sauvegarde est alors
        // présent et sa taille annoncée peut être falsifiée.
        val core = KotlinGbaCore(forcedSaveType = GbaSaveType.FLASH_128K)
        core.loadRom(SyntheticRom.build(programWords = SyntheticRom.backdropProgram(0x1F)))
        core.runFrame(IntArray(core.video.pixelCount))

        val machineAvant = core.machine!!
        val etatAvant = core.saveState()
        val corrompu = core.saveState()
        ecrireInt(corrompu, decalageTailleSauvegarde(core), 1234)

        assertFailsWith<SaveStateException> { core.loadState(corrompu) }
        assertSame(machineAvant, core.machine)
        assertContentEquals(etatAvant, core.saveState())
    }

    @Test
    fun `l'octet console de l'etat porte l'identifiant stable`() {
        val core = coreEnCours()
        assertEquals(
            ConsoleType.GAME_BOY_ADVANCE.storageId,
            core.saveState()[6].toInt() and 0xFF,
            "L'état doit désigner la console par son identifiant persisté, pas par son rang",
        )
    }

    @Test
    fun `un etat designant une autre console est refuse`() {
        val autre = ConsoleType.entries.first { it != ConsoleType.GAME_BOY_ADVANCE }
        resteIntact("console ${autre.name}") { _, etat ->
            etat.also { it[6] = autre.storageId.toByte() }
        }
    }

    // --- Décalages calculés depuis la machine, pas codés en dur ------------

    private fun lireInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun ecrireInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    /** Position du premier champ PPU dans l'état sérialisé. */
    private fun decalageChampsPpu(core: KotlinGbaCore): Int {
        val machine = core.machine!!
        val enTete = 4 + 2 + 1 + core.romHash.size
        val cpu = 16 * 4 + 4 + 1 + 4 + machine.cpu.state.exportBanks().size * 4
        val bus = machine.bus.let {
            it.ewram.size + it.iwram.size + it.io.size + it.paletteRam.size +
                it.vram.size + it.oam.size + it.sram.size
        } + 4 // pressedBits
        return enTete + cpu + bus + 4 // + le compte de champs PPU
    }

    /** Position de la taille annoncée du bloc de sauvegarde de cartouche. */
    private fun decalageTailleSauvegarde(core: KotlinGbaCore): Int {
        val machine = core.machine!!
        val ppu = GbaPpu.STATE_FIELD_COUNT * 4 + machine.ppu.frame.size * 4
        val peripheriques = 4 + 4 + 1 + // IE, IF, IME
            16 * 4 + // timers
            9 * 4 + // DMA
            1 + 4 + 1 // attente BIOS
        return decalageChampsPpu(core) + ppu + peripheriques
    }
}
