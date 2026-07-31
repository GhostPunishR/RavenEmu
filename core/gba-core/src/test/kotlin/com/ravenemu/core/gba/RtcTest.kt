package com.ravenemu.core.gba

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Horloge temps réel de cartouche, éprouvée **par le protocole** plutôt que par
 * ses champs internes : le pilote de test ci-dessous bat les broches exactement
 * comme le fait la bibliothèque Seiko embarquée dans les jeux — poids fort en
 * tête pour la commande, poids faible pour les données, échantillonnage sur les
 * fronts montants, relecture horloge haute.
 *
 * Un test qui appellerait directement les méthodes du composant validerait sa
 * propre idée du dialogue ; celui-ci valide le dialogue réel, du registre GPIO
 * jusqu'à l'octet rendu au jeu.
 */
class RtcTest {

    // Commandes telles que les émettent les jeux concernés.
    private val cmdReset = 0x60
    private val cmdReadStatus = 0x63
    private val cmdWriteStatus = 0x62
    private val cmdReadDateTime = 0x65
    private val cmdWriteDateTime = 0x64
    private val cmdReadTime = 0x67
    private val cmdWriteTime = 0x66

    private val dataRegister = 0x0800_00C4
    private val directionRegister = 0x0800_00C6
    private val controlRegister = 0x0800_00C8

    /** Pilote minimal reproduisant le cadençage de la bibliothèque du jeu. */
    private inner class Master(val bus: GbaBus) {

        fun begin() {
            bus.write16(controlRegister, 1)   // registres relisibles
            bus.write16(directionRegister, 7) // SCK, SIO et CS pilotés par le jeu
            bus.write16(dataRegister, 1)      // SCK haut, CS bas
            bus.write16(dataRegister, 5)      // CS monte : la transaction commence
        }

        fun end() {
            bus.write16(dataRegister, 1)      // CS retombe
        }

        /** Octet de commande : poids fort en tête. */
        fun command(value: Int) {
            bus.write16(directionRegister, 7)
            for (i in 7 downTo 0) clockOut((value ushr i) and 1)
        }

        /** Octet de données émis par le jeu : poids faible en tête. */
        fun writeByte(value: Int) {
            bus.write16(directionRegister, 7)
            for (i in 0 until 8) clockOut((value ushr i) and 1)
        }

        /** Octet rendu par le composant : poids faible en tête. */
        fun readByte(): Int {
            bus.write16(directionRegister, 5) // SIO passe en entrée
            var value = 0
            for (i in 0 until 8) {
                bus.write16(dataRegister, 4)  // SCK bas : le bit est présenté
                bus.write16(dataRegister, 5)  // SCK haut : le bit est stable
                value = value or (((bus.read16(dataRegister) ushr 1) and 1) shl i)
            }
            return value
        }

        private fun clockOut(bit: Int) {
            bus.write16(dataRegister, (bit shl 1) or 4)
            bus.write16(dataRegister, (bit shl 1) or 5)
        }

        fun transaction(command: Int, count: Int): IntArray {
            begin()
            command(command)
            val result = IntArray(count) { readByte() }
            end()
            return result
        }
    }

    /** ROM synthétique contenant la signature de la bibliothèque d'horloge. */
    private fun romWithRtc(): ByteArray {
        val rom = SyntheticRom.build(sizeBytes = 4096)
        "SIIRTC_V001".toByteArray(Charsets.US_ASCII).copyInto(rom, 0x400)
        return rom
    }

    private fun busWithRtc(now: LocalDateTime): Pair<GbaBus, Master> {
        val bus = GbaBus(GbaCartridge.create(romWithRtc()))
        bus.gpio!!.rtc.clock = { now }
        return bus to Master(bus)
    }

    private fun bcd(value: Int): Int = ((value / 10) shl 4) or (value % 10)

    @Test
    fun `la signature de la bibliotheque revele la presence d'une horloge`() {
        assertTrue(GbaCartridge.hasRealTimeClock(romWithRtc()))
        assertNotNull(GbaCartridge.create(romWithRtc()).gpio)
    }

    @Test
    fun `une cartouche ordinaire n'expose aucun port GPIO`() {
        val rom = SyntheticRom.build(sizeBytes = 4096)
        assertFalse(GbaCartridge.hasRealTimeClock(rom))
        assertNull(GbaCartridge.create(rom).gpio)
    }

    /**
     * Le cœur de l'affaire : c'est ce registre qui fait dire « la pile interne
     * est épuisée ». Bit 7 à zéro = alimentation jamais coupée, bit 6 à un =
     * format 24 heures, les deux conditions que le jeu vérifie.
     */
    @Test
    fun `le registre d'etat annonce une horloge saine en format 24 heures`() {
        val (_, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 9, 30, 15))
        val status = master.transaction(cmdReadStatus, 1)[0]
        assertEquals(0x00, status and 0x80, "coupure d'alimentation signalée à tort")
        assertEquals(0x40, status and 0x40, "format 24 heures non annoncé")
    }

    @Test
    fun `la date et l'heure sont rendues en BCD`() {
        val now = LocalDateTime.of(2026, 7, 27, 14, 5, 9)
        val (_, master) = busWithRtc(now)
        val bytes = master.transaction(cmdReadDateTime, 7)

        assertEquals(bcd(26), bytes[0], "année")
        assertEquals(bcd(7), bytes[1], "mois")
        assertEquals(bcd(27), bytes[2], "jour")
        assertEquals(now.dayOfWeek.value % 7, bytes[3], "jour de la semaine")
        assertEquals(bcd(14), bytes[4], "heure")
        assertEquals(bcd(5), bytes[5], "minute")
        assertEquals(bcd(9), bytes[6], "seconde")
    }

    /**
     * En format 24 heures, le bit 7 de l'heure ne sert pas : il n'indique
     * l'après-midi qu'en format 12 heures, que le composant n'annonce pas.
     */
    @Test
    fun `l'apres-midi ne met pas l'indicateur 12 heures`() {
        val (_, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 23, 0, 0))
        val hour = master.transaction(cmdReadTime, 3)[0]
        assertEquals(bcd(23), hour)
    }

    @Test
    fun `la commande de temps seul ne rend que trois octets`() {
        val (_, master) = busWithRtc(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
        val bytes = master.transaction(cmdReadTime, 3)
        assertEquals(listOf(bcd(3), bcd(4), bcd(5)), bytes.toList())
    }

    @Test
    fun `regler l'heure decale l'horloge et se relit`() {
        val (_, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 9, 0, 0))

        master.begin()
        master.command(cmdWriteTime)
        master.writeByte(bcd(18))
        master.writeByte(bcd(45))
        master.writeByte(bcd(30))
        master.end()

        val bytes = master.transaction(cmdReadTime, 3)
        assertEquals(listOf(bcd(18), bcd(45), bcd(30)), bytes.toList())
    }

    @Test
    fun `regler la date complete se relit`() {
        val (bus, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 9, 0, 0))

        master.begin()
        master.command(cmdWriteDateTime)
        for (value in intArrayOf(bcd(30), bcd(12), bcd(24), 2, bcd(6), bcd(7), bcd(8))) {
            master.writeByte(value)
        }
        master.end()

        val bytes = master.transaction(cmdReadDateTime, 7)
        assertEquals(bcd(30), bytes[0], "année")
        assertEquals(bcd(12), bytes[1], "mois")
        assertEquals(bcd(24), bytes[2], "jour")
        assertEquals(bcd(6), bytes[4], "heure")
        assertEquals(bcd(7), bytes[5], "minute")
        assertEquals(bcd(8), bytes[6], "seconde")
        assertTrue(bus.gpio!!.rtc.offsetSeconds != 0L, "l'écart n'a pas été enregistré")
    }

    /** Le temps continue de couler : l'écart posé par le jeu s'y ajoute. */
    @Test
    fun `l'horloge suit la machine apres un reglage`() {
        var now = LocalDateTime.of(2026, 7, 27, 9, 0, 0)
        val bus = GbaBus(GbaCartridge.create(romWithRtc()))
        bus.gpio!!.rtc.clock = { now }
        val master = Master(bus)

        master.begin()
        master.command(cmdWriteTime)
        master.writeByte(bcd(12))
        master.writeByte(bcd(0))
        master.writeByte(bcd(0))
        master.end()

        now = now.plusMinutes(30)
        val bytes = master.transaction(cmdReadTime, 3)
        assertEquals(listOf(bcd(12), bcd(30), bcd(0)), bytes.toList())
    }

    @Test
    fun `la remise a zero efface le reglage du jeu`() {
        val (bus, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 9, 0, 0))

        master.begin()
        master.command(cmdWriteTime)
        master.writeByte(bcd(18))
        master.writeByte(bcd(0))
        master.writeByte(bcd(0))
        master.end()
        assertTrue(bus.gpio!!.rtc.offsetSeconds != 0L)

        master.begin()
        master.command(cmdReset)
        master.end()

        assertEquals(0L, bus.gpio!!.rtc.offsetSeconds)
        assertEquals(bcd(9), master.transaction(cmdReadTime, 3)[0])
    }

    /** Écrire l'état ne doit jamais permettre de réintroduire une panne de pile. */
    @Test
    fun `ecrire l'etat ne rallume pas l'indicateur de coupure`() {
        val (_, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 9, 0, 0))

        master.begin()
        master.command(cmdWriteStatus)
        master.writeByte(0xFF)
        master.end()

        val status = master.transaction(cmdReadStatus, 1)[0]
        assertEquals(0x00, status and 0x80)
        assertEquals(0x40, status and 0x40)
    }

    /**
     * Tant que le jeu n'a pas autorisé la relecture, les trois registres se
     * comportent comme de la ROM ordinaire — c'est ce qui permet à une cartouche
     * sans horloge de se lire sans surprise.
     */
    @Test
    fun `sans autorisation de relecture le port laisse passer la ROM`() {
        val rom = romWithRtc()
        rom[0xC4] = 0x11
        rom[0xC5] = 0x22
        val bus = GbaBus(GbaCartridge.create(rom))

        assertEquals(0x2211, bus.read16(dataRegister))
        bus.write16(controlRegister, 1)
        bus.write16(directionRegister, 0)
        assertEquals(0x0000, bus.read16(dataRegister) and 0xD)
    }

    /**
     * Une cartouche sans horloge n'intercepte rien : les octets de ROM situés à
     * ces adresses restent lisibles, écriture comprise (sans effet).
     */
    @Test
    fun `une cartouche sans horloge ne detourne pas ces adresses`() {
        val rom = SyntheticRom.build(sizeBytes = 4096)
        rom[0xC4] = 0x34
        rom[0xC5] = 0x12
        val bus = GbaBus(GbaCartridge.create(rom))

        bus.write16(controlRegister, 1)
        bus.write16(dataRegister, 0x7)
        assertEquals(0x1234, bus.read16(dataRegister))
    }

    /** Les registres sont visibles depuis chacun des miroirs de la cartouche. */
    @Test
    fun `le port repond dans les trois zones d'attente`() {
        val (bus, master) = busWithRtc(LocalDateTime.of(2026, 7, 27, 9, 0, 0))
        master.begin()
        for (mirror in intArrayOf(0x0800_0000, 0x0A00_0000, 0x0C00_0000)) {
            assertEquals(1, bus.read16(mirror + 0xC8), "miroir ${mirror.toString(16)}")
        }
    }

    @Test
    fun `un etat instantane conserve l'horloge`() {
        val now = LocalDateTime.of(2026, 7, 27, 9, 0, 0)
        val core = GbaCore()
        core.loadRom(romWithRtc())
        val bus = core.machine!!.bus
        bus.gpio!!.rtc.clock = { now }
        val master = Master(bus)

        master.begin()
        master.command(cmdWriteTime)
        master.writeByte(bcd(21))
        master.writeByte(bcd(15))
        master.writeByte(bcd(0))
        master.end()
        val offset = bus.gpio!!.rtc.offsetSeconds

        val state = core.saveState()

        // L'horloge est ramenée à l'heure de l'hôte, puis l'état la restaure.
        master.begin()
        master.command(cmdReset)
        master.end()
        assertEquals(0L, bus.gpio!!.rtc.offsetSeconds)

        core.loadState(state)
        assertEquals(offset, core.machine!!.bus.gpio!!.rtc.offsetSeconds)
    }
}
