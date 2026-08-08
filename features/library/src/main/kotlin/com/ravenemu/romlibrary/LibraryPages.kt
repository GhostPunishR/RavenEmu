package com.ravenemu.romlibrary

import com.ravenemu.emulation.api.ConsoleType

/**
 * Pages de la bibliothèque : une console par page, dans un ordre fixe.
 *
 * La console cesse d'être une case à cocher dans un menu pour devenir l'axe de
 * navigation. Une page ne s'affiche que si elle a quelque chose à montrer :
 * proposer « Game Boy Advance » à quelqu'un qui n'a que des cartouches Game Boy
 * revient à lui faire feuilleter du vide.
 *
 * Ce choix est ici, hors d'Android, parce qu'il ne dépend que du contenu de
 * l'index — et qu'une règle de navigation mérite d'être vérifiée sans appareil.
 */
object LibraryPages {

    /**
     * Pages propres à une console, dans l'ordre d'apparition. Cet ordre suit
     * les générations de matériel, pas le nombre de jeux : il ne doit pas
     * changer sous les doigts quand la bibliothèque grossit.
     */
    private val CONSOLE_PAGES = listOf(
        LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES,
        LibraryFilter.GAME_BOY_COLOR_CARTRIDGES,
        ConsoleType.GAME_BOY_ADVANCE.name,
    )

    /**
     * Filtres des pages à afficher pour [entries].
     *
     * Une page « Tous » n'apparaît qu'à partir de deux consoles : avec une
     * seule, elle montrerait exactement la même chose que la page voisine.
     * La liste n'est jamais vide — une bibliothèque vide garde une page, sans
     * quoi l'écran n'aurait rien à afficher, pas même son message d'accueil.
     */
    fun forEntries(entries: List<RomEntry>): List<String> {
        val peuplees = CONSOLE_PAGES.filter { filtre ->
            entries.any { LibraryFilter.matches(it, filtre) }
        }
        return when {
            peuplees.isEmpty() -> listOf(LibraryFilter.ALL)
            peuplees.size == 1 -> peuplees
            else -> listOf(LibraryFilter.ALL) + peuplees
        }
    }

    /**
     * Index de la page à afficher pour [filter] dans [pages], ou 0 si ce filtre
     * n'a plus de page — le dernier jeu d'une console peut avoir disparu depuis
     * la dernière fois que l'écran a été ouvert.
     */
    fun indexOf(pages: List<String>, filter: String): Int =
        pages.indexOf(LibraryFilter.normalize(filter)).coerceAtLeast(0)
}
