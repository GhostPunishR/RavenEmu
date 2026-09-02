package com.ravenemu.core.nds

import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import com.ravenemu.nativebridge.NativeCoreBridge
import com.ravenemu.nativebridge.NativeCoreHandle

/**
 * Adaptateur Kotlin du cœur Nintendo DS écrit en C++20.
 *
 * **Ce cœur ne fait pas encore tourner de jeu du commerce.** Il amorce une
 * cartouche, fait avancer les deux processeurs et dessine les deux écrans, mais
 * les appels du programme d'amorçage, le bus de cartouche, l'écran tactile, le
 * moteur 3D et le son manquent encore. Une cartouche qui compte sur l'amorceur
 * s'arrête sans rien afficher.
 *
 * L'application en est prévenue par [supportsSaveState], faux ici : aucun
 * format d'état n'est publié tant que la console n'est pas complète, et en
 * figer un maintenant promettrait une compatibilité que le prochain organe
 * ajouté briserait.
 */
class NdsCore : EmulatorCore, AutoCloseable {

    private val native = lazy(LazyThreadSafetyMode.NONE) {
        NativeCoreHandle(ConsoleType.NINTENDO_DS.storageId, NO_FORCED_SAVE)
    }
    private var closed = false

    override val console: ConsoleType = ConsoleType.NINTENDO_DS

    /**
     * Les deux écrans sont empilés dans un tampon unique : l'écran haut occupe
     * les 192 premières lignes, l'écran bas les 192 suivantes. Le contrat vidéo
     * de RavenEmu ne décrit qu'un écran, et l'élargir pour une seule console
     * aurait touché tous les moteurs ; l'agencement réel reste à la couche qui
     * affiche, seule à connaître l'appareil.
     */
    override val video: VideoSpec = VideoSpec(SCREEN_WIDTH, FRAMEBUFFER_HEIGHT, REFRESH_RATE_HZ)

    /**
     * Le mélangeur de la console produit du 16 bits signé à 32 768 Hz sur deux
     * voies. La cadence décrit le matériel, pas l'avancement de son émulation :
     * [readAudio] ne rend encore aucun échantillon.
     */
    override val audio: AudioSpec = AudioSpec(32_768, 2)

    override val framebufferFormat: FramebufferFormat = FramebufferFormat.ARGB_8888

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        NativeCoreBridge.loadRom(handle(), rom, batteryRam)
    }

    override fun reset() {
        NativeCoreBridge.reset(handle())
    }

    override fun runFrame(framebuffer: IntArray) = runFrame(framebuffer, true)

    override fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        NativeCoreBridge.runFrame(handle(), framebuffer, renderVideo)
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        NativeCoreBridge.setButton(handle(), button.ordinal, pressed)
    }

    /**
     * Le contact arrive en pixels de l'écran du bas ; le cœur natif le confie au
     * convertisseur du port série, qui le rendra au jeu sous la forme du
     * matériel : des mesures brutes, non des pixels.
     */
    override fun setTouch(down: Boolean, x: Int, y: Int) {
        NativeCoreBridge.setTouch(handle(), down, x, y)
    }

    /** Aucun échantillon : le son de la console n'est pas encore émulé. */
    override fun readAudio(buffer: ShortArray): Int = 0

    /**
     * La sauvegarde de cartouche passe par un bus que ce cœur n'a pas encore.
     * Se déclarer sans mémoire à pile est exact, et évite que l'application
     * n'écrive un `.sav` vide qui écraserait une vraie sauvegarde.
     */
    override val hasBatteryRam: Boolean = false
    override val batteryRamDirty: Boolean = false
    override fun snapshotBatteryRam(): BatteryRamSnapshot? = null
    override fun acknowledgeBatteryRamSaved(generation: Long) = Unit

    override val supportsSaveState: Boolean = SUPPORTS_SAVE_STATE

    override fun saveState(): ByteArray = NativeCoreBridge.saveState(handle())

    override fun loadState(state: ByteArray) = NativeCoreBridge.loadState(handle(), state)

    override fun close() {
        if (closed) return
        closed = true
        if (native.isInitialized()) native.value.close()
    }

    private fun handle(): Long {
        check(!closed) { "Le cœur natif RavenEmu est fermé" }
        return native.value.value()
    }

    companion object {
        /** Largeur d'un écran Nintendo DS, en pixels. */
        const val SCREEN_WIDTH = 256

        /** Hauteur d'un écran Nintendo DS, en pixels. */
        const val SCREEN_HEIGHT = 192

        /** Hauteur du tampon : les deux écrans empilés. */
        const val FRAMEBUFFER_HEIGHT = SCREEN_HEIGHT * 2

        /**
         * Cadence de rafraîchissement, en hertz.
         *
         * Horloge maître de 33,513982 MHz pour 560 190 cycles par trame, soit
         * 355 points sur 263 lignes à six cycles par point.
         */
        const val REFRESH_RATE_HZ = 33_513_982.0 / 560_190.0

        /**
         * Aucun format d'état n'est publié tant que la console n'est pas
         * complète : en figer un maintenant promettrait une compatibilité que le
         * prochain organe ajouté briserait.
         *
         * Nommé ici parce que la réponse se donne à deux endroits : sur le
         * moteur, pour l'écran de jeu, et sur le fournisseur de console, pour la
         * bibliothèque qui décide sans construire de moteur. Deux littéraux
         * auraient pu diverger le jour où le format arrive.
         */
        const val SUPPORTS_SAVE_STATE = false

        /** Le type de sauvegarde imposé ne concerne que le Game Boy Advance. */
        private const val NO_FORCED_SAVE = -1
    }
}
