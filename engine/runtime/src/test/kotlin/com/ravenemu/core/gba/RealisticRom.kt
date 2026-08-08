package com.ravenemu.core.gba

/**
 * ROM synthétiques **réalistes** : elles font ce que fait un vrai jeu au
 * démarrage, sans contenir le moindre octet de contenu commercial.
 *
 * Contrairement aux ROM de quelques instructions utilisées pour les tests
 * unitaires, celles-ci enchaînent code ARM et Thumb, installent un gestionnaire
 * d'interruption, configurent le mode vidéo, décompressent des données LZ77 vers
 * la VRAM, copient un motif par DMA, arment un timer, alimentent une FIFO audio
 * et attendent plusieurs VBlank. Elles servent de banc d'essai d'intégration :
 * si l'une d'elles reste bloquée ou n'écrit pas ses témoins, un mécanisme du
 * moteur est cassé.
 *
 * Les résultats observables sont déposés en IWRAM, aux offsets décrits par
 * [Witness].
 */
object RealisticRom {

    /** Offsets, depuis `0x0300_0000`, des témoins écrits par le programme. */
    object Witness {
        /** Nombre d'interruptions traitées par le gestionnaire du jeu. */
        const val IRQ_COUNT = 0x00

        /** Dernier `IF` observé par le gestionnaire. */
        const val LAST_IF = 0x04

        /** Témoin écrit par la partie Thumb du programme (`0x5A`). */
        const val THUMB_DONE = 0x08

        /** Valeur du compteur du timer 0 après les attentes de VBlank. */
        const val TIMER_VALUE = 0x0C

        /** Nombre d'attentes de VBlank effectivement terminées. */
        const val VBLANK_COUNT = 0x10

        /** Base de la zone de témoins. */
        const val BASE = 0x0300_0000
    }

    /** Adresses où le programme dépose ses données graphiques. */
    object Video {
        /** Destination de la décompression LZ77 (début du cadre en mode 3). */
        const val LZ77_DEST = 0x0600_0000

        /** Destination de la copie DMA. */
        const val DMA_DEST = 0x0600_0100
    }

    /** Nombre d'attentes de VBlank que le programme effectue avant de finir. */
    const val VBLANK_WAITS = 3

    /** Témoin écrit par le code Thumb. */
    const val THUMB_MARKER = 0x5A

    /**
     * Les seize octets que la décompression LZ77 doit produire : huit littéraux
     * suivis d'une référence arrière qui les répète.
     */
    val EXPECTED_LZ77 = intArrayOf(
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
    )

    /** Les quatre mots que le DMA doit recopier en VRAM. */
    val EXPECTED_PATTERN = intArrayOf(
        0x7C1F_03E0.toInt(), 0x001F_7FFF, 0x03E0_7C00, 0x7FFF_0000,
    )

    /**
     * ROM complète : démarrage, configuration vidéo, interruptions, LZ77, DMA,
     * audio, timer, attentes de VBlank et bascule en Thumb.
     */
    fun bootSequence(): ByteArray = SyntheticRom.buildFromCode(assembleBootSequence())

    /**
     * Variante minimale mais réaliste : un programme **Thumb** qui écrit un
     * motif en VRAM puis boucle. Sert à vérifier que le moteur exécute une ROM
     * entièrement en Thumb après le branchement initial.
     */
    fun thumbOnly(): ByteArray {
        val asm = GbaAssembler()
        asm.b("main")
        asm.padTo(HEADER_END)

        asm.label("main")
        asm.ldrPc(0, "cIoBase")
        asm.ldrPc(1, "cDispcnt")
        asm.strh(1, 0, 0x00)
        asm.ldrPc(0, "cVram")
        asm.ldrPc(1, "cThumbEntry")
        asm.bx(1)

        asm.align(2)
        asm.label("thumbMain")
        asm.tMov(1, 0x1F)          // rouge BGR555 sur huit bits
        asm.tStr(1, 0, 0)
        asm.tMov(1, THUMB_MARKER)
        asm.tStr(1, 0, 4)
        asm.tInfiniteLoop()

        asm.align(4)
        asm.label("cIoBase"); asm.word(IO_BASE)
        asm.label("cDispcnt"); asm.word(DISPCNT_MODE3_BG2)
        asm.label("cVram"); asm.word(Video.LZ77_DEST)
        asm.label("cThumbEntry"); asm.thumbAddressWord("thumbMain")
        return SyntheticRom.buildFromCode(asm.assemble())
    }

    /** Assemble la séquence de démarrage complète. */
    private fun assembleBootSequence(): ByteArray {
        val asm = GbaAssembler()

        // 0x0800_0000 : une vraie cartouche commence par un branchement, l'en-tête
        // occupant les octets suivants jusqu'à 0xBF.
        asm.b("main")
        asm.padTo(HEADER_END)

        // ---- Gestionnaire d'interruption du jeu ----
        // Le BIOS l'appelle en mode ARM après avoir sauvegardé r0-r3, r12 et lr.
        asm.label("irqHandler")
        asm.ldrPc(0, "cIfRegister")
        asm.ldrh(1, 0)                 // IF : sources levées
        asm.strh(1, 0)                 // acquittement (écrire 1 efface le drapeau)
        asm.ldrPc(2, "cWitnessBase")
        asm.ldr(3, 2, Witness.IRQ_COUNT)
        asm.add(3, 3, 1)
        asm.str(3, 2, Witness.IRQ_COUNT)
        asm.str(1, 2, Witness.LAST_IF)
        asm.bx(14)                     // retour au gestionnaire du BIOS

        // ---- Programme principal ----
        asm.label("main")
        asm.ldrPc(4, "cIoBase")        // r4 = 0x0400_0000
        asm.ldrPc(5, "cDma3Base")      // r5 = 0x0400_00D4
        asm.ldrPc(6, "cTimerBase")     // r6 = 0x0400_0100
        asm.ldrPc(7, "cIrqRegisters")  // r7 = 0x0400_0200
        asm.ldrPc(8, "cWitnessBase")   // r8 = 0x0300_0000

        // Étape 1 : mode vidéo 3 avec BG2, et interruption VBlank armée.
        asm.ldrPc(0, "cDispcnt")
        asm.strh(0, 4, 0x00)
        asm.mov(0, DISPSTAT_VBLANK_IRQ)
        asm.strh(0, 4, 0x04)

        // Étape 2 : installe le gestionnaire du jeu, autorise le VBlank, IME.
        asm.ldrPc(0, "cIrqHandlerAddress")
        asm.ldrPc(1, "cUserIrqPointer")
        asm.str(0, 1)
        asm.mov(0, 1)                  // IE = VBlank
        asm.strh(0, 7, 0x00)
        asm.strh(0, 7, 0x08)           // IME = 1

        // Étape 3 : décompresse les données LZ77 vers la VRAM (SWI 0x12).
        asm.ldrPc(0, "cLz77Source")
        asm.ldrPc(1, "cVramBase")
        asm.swi(0x12)

        // Étape 4 : copie le motif en VRAM par le canal DMA 3.
        asm.ldrPc(0, "cPatternSource")
        asm.str(0, 5, 0x00)            // DMA3SAD
        asm.ldrPc(0, "cPatternDest")
        asm.str(0, 5, 0x04)            // DMA3DAD
        asm.mov(0, EXPECTED_PATTERN.size)
        asm.strh(0, 5, 0x08)           // nombre de mots
        asm.ldrPc(0, "cDmaControl")
        asm.strh(0, 5, 0x0A)           // activation, 32 bits, immédiat

        // Étape 5 : audio — sortie maîtresse, mixage, canal carré et FIFO A.
        asm.mov(0, SOUNDCNT_X_ENABLE)
        asm.strh(0, 4, 0x84)
        asm.ldrPc(0, "cSoundCntL")
        asm.strh(0, 4, 0x80)
        asm.ldrPc(0, "cSoundCntH")
        asm.strh(0, 4, 0x82)
        asm.ldrPc(0, "cSquareEnvelope")
        asm.strh(0, 4, 0x62)
        asm.ldrPc(0, "cSquareFrequency")
        asm.strh(0, 4, 0x64)
        asm.ldrPc(0, "cFifoSamples")
        asm.str(0, 4, 0xA0)            // FIFO A : quatre échantillons
        asm.str(0, 4, 0xA0)            // puis quatre autres

        // Étape 6 : arme le timer 0, puis attend plusieurs VBlank.
        asm.ldrPc(0, "cTimerReload")
        asm.strh(0, 6, 0x00)
        asm.mov(0, TIMER_ENABLE)
        asm.strh(0, 6, 0x02)

        asm.mov(2, VBLANK_WAITS)
        asm.label("waitLoop")
        asm.swi(0x05)                  // VBlankIntrWait
        asm.ldr(0, 8, Witness.VBLANK_COUNT)
        asm.add(0, 0, 1)
        asm.str(0, 8, Witness.VBLANK_COUNT)
        asm.subs(2, 2, 1)
        asm.bne("waitLoop")

        // Relève le timer, puis passe la main au code Thumb.
        asm.ldrh(0, 6, 0x00)
        asm.str(0, 8, Witness.TIMER_VALUE)
        asm.movReg(0, 8)               // r0 = base des témoins, pour le Thumb
        asm.ldrPc(1, "cThumbEntry")
        asm.bx(1)

        // ---- Code Thumb ----
        asm.align(2)
        asm.label("thumbMain")
        asm.tMov(1, THUMB_MARKER)
        asm.tStr(1, 0, Witness.THUMB_DONE)
        asm.tInfiniteLoop()

        // ---- Table de constantes ----
        asm.align(4)
        asm.label("cIoBase"); asm.word(IO_BASE)
        asm.label("cDma3Base"); asm.word(DMA3_BASE)
        asm.label("cTimerBase"); asm.word(TIMER0_BASE)
        asm.label("cIrqRegisters"); asm.word(IRQ_REGISTERS)
        asm.label("cIfRegister"); asm.word(IF_REGISTER)
        asm.label("cWitnessBase"); asm.word(Witness.BASE)
        asm.label("cUserIrqPointer"); asm.word(USER_IRQ_POINTER)
        asm.label("cVramBase"); asm.word(Video.LZ77_DEST)
        asm.label("cPatternDest"); asm.word(Video.DMA_DEST)
        asm.label("cDispcnt"); asm.word(DISPCNT_MODE3_BG2)
        asm.label("cDmaControl"); asm.word(DMA_ENABLE_WORD32)
        asm.label("cSoundCntL"); asm.word(SOUNDCNT_L_ALL)
        asm.label("cSoundCntH"); asm.word(SOUNDCNT_H_MIX)
        asm.label("cSquareEnvelope"); asm.word(SQUARE_ENVELOPE)
        asm.label("cSquareFrequency"); asm.word(SQUARE_TRIGGER)
        asm.label("cFifoSamples"); asm.word(FIFO_SAMPLES)
        asm.label("cTimerReload"); asm.word(TIMER_RELOAD)
        asm.label("cIrqHandlerAddress"); asm.addressWord("irqHandler")
        asm.label("cThumbEntry"); asm.thumbAddressWord("thumbMain")
        // Adresses des blocs de données, et non leur contenu.
        asm.label("cLz77Source"); asm.addressWord("lz77Data")
        asm.label("cPatternSource"); asm.addressWord("patternData")

        // ---- Données ----
        asm.align(4)
        asm.label("lz77Data")
        // En-tête LZ77 : type 0x10, taille décompressée sur 24 bits.
        asm.word(0x10 or (EXPECTED_LZ77.size shl 8))
        asm.byte(0x00)                 // huit littéraux
        asm.bytes(intArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88))
        asm.byte(0x80)                 // le bloc suivant est une référence arrière
        asm.byte(0x50)                 // longueur 8 (5 + 3), poids fort du déplacement
        asm.byte(0x07)                 // déplacement 8 (7 + 1) : répète les huit octets
        asm.align(4)

        asm.label("patternData")
        for (word in EXPECTED_PATTERN) asm.word(word)

        return asm.assemble()
    }

    // Fin de la zone d'en-tête de cartouche : le programme reprend ici.
    private const val HEADER_END = 0xC0

    private const val IO_BASE = 0x0400_0000
    private const val DMA3_BASE = 0x0400_00D4
    private const val TIMER0_BASE = 0x0400_0100
    private const val IRQ_REGISTERS = 0x0400_0200
    private const val IF_REGISTER = 0x0400_0202
    private const val USER_IRQ_POINTER = 0x0300_7FFC

    /** DISPCNT : mode 3 (bitmap 16 bpp) avec BG2 activé. */
    private const val DISPCNT_MODE3_BG2 = 0x0403

    /** DISPSTAT : interruption VBlank autorisée. */
    private const val DISPSTAT_VBLANK_IRQ = 0x08

    /** DMAxCNT_H : activation, unités de 32 bits, déclenchement immédiat. */
    private const val DMA_ENABLE_WORD32 = 0x8400

    private const val SOUNDCNT_X_ENABLE = 0x80

    /** SOUNDCNT_L : volume maître maximal, quatre canaux PSG à gauche et à droite. */
    private const val SOUNDCNT_L_ALL = 0x77FF

    /**
     * SOUNDCNT_H : PSG à 100 %, Direct Sound A à pleine échelle, panoramique
     * gauche et droite, cadencé par le timer 0.
     */
    private const val SOUNDCNT_H_MIX = 0x0B02

    /** Canal carré 1 : volume 15, rapport cyclique 50 %. */
    private const val SQUARE_ENVELOPE = 0xF080

    /** Canal carré 1 : déclenchement et fréquence. */
    private const val SQUARE_TRIGGER = 0x8400

    /** Quatre échantillons PCM signés non nuls pour la FIFO A. */
    private const val FIFO_SAMPLES = 0x4060_4060

    /** Rechargement du timer 0, choisi pour déborder plusieurs fois par trame. */
    private const val TIMER_RELOAD = 0xFF00

    /** TMxCNT_H : activation, prescaler 1. */
    private const val TIMER_ENABLE = 0x80
}
