package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.CartridgeHeader
import com.ravenemu.core.gba.cartridge.GbaHeader

/**
 * État d'une ROM, **déduit de la cartouche elle-même**.
 *
 * Une cartouche porte ses propres sommes de contrôle. RavenEmu les recalcule
 * sur le fichier et compare : aucune base extérieure n'est nécessaire, et le
 * verdict ne dépend d'aucun catalogue à tenir à jour.
 *
 * Ce que le badge affirme est donc étroit et vérifiable : il dit si le contenu
 * correspond à ce que la cartouche déclare. Il ne dit **pas** de quel jeu il
 * s'agit, ni si la copie est légitime — une cartouche ne porte pas cette
 * information.
 */
enum class RomStatus(val displayName: String) {

    /**
     * En-tête et somme de contrôle globale concordent avec le contenu : le
     * fichier est exactement celui que la cartouche déclare.
     */
    INTACT("Intègre"),

    /**
     * L'en-tête est valide, mais le contenu ne correspond plus à la somme de
     * contrôle globale. Traduction, correctif, ROM hack ou copie abîmée : la
     * cartouche ne permet pas de distinguer ces cas, et prétendre le contraire
     * serait mentir.
     */
    MODIFIED("Modifiée"),

    /**
     * En-tête conforme, contenu invérifiable : la cartouche Game Boy Advance ne
     * porte pas de somme de contrôle couvrant l'ensemble de la ROM.
     */
    HEADER_ONLY("En-tête vérifié"),

    /**
     * La somme de contrôle de l'en-tête ne correspond pas à l'en-tête lui-même.
     * Le fichier démarrera peut-être, mais il n'est pas conforme.
     */
    INVALID_HEADER("En-tête douteux"),
}

/**
 * État d'une cartouche Game Boy ou Game Boy Color.
 *
 * La somme globale couvre **tous** les octets de la ROM sauf les deux qui la
 * portent : c'est elle qui distingue un contenu intact d'un contenu retouché.
 * Un seul octet changé la fait basculer.
 */
fun CartridgeHeader.romStatus(): RomStatus = when {
    !headerChecksumValid -> RomStatus.INVALID_HEADER
    globalChecksumValid -> RomStatus.INTACT
    else -> RomStatus.MODIFIED
}

/**
 * État d'une cartouche Game Boy Advance.
 *
 * Son en-tête ne comporte qu'une somme de contrôle de huit bits, couvrant les
 * seuls octets `0xA0`–`0xBC`. Rien n'y couvre le contenu, qui reste donc
 * invérifiable — d'où [RomStatus.HEADER_ONLY] plutôt qu'un « Intègre » que rien
 * ne justifierait.
 */
fun GbaHeader.romStatus(): RomStatus =
    if (headerChecksumValid) RomStatus.HEADER_ONLY else RomStatus.INVALID_HEADER
