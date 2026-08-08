package com.ravenemu.emulation.api.display

/**
 * Catalogue des profils d'écran monochromes fournis par RavenEmu.
 *
 * Chaque palette est une **simulation LCD** calibrable, décrite pour donner
 * l'apparence du panneau visé sous un éclairage normal — jamais présentée
 * comme une palette officielle. Les valeurs sont volontairement sobres :
 * contraste mesuré, pas de blanc ni de noir numérique pur (sauf le profil
 * d'accessibilité « Noir et blanc »), aucune saturation excessive ni effet
 * fluorescent.
 */
object MonochromeDisplayProfiles {

    private class Profile(
        override val id: String,
        override val displayName: String,
        override val description: String,
        private val palette: IntArray,
    ) : MonochromeDisplayProfile {
        init {
            require(palette.size == 4) { "Un profil doit contenir 4 couleurs" }
        }

        override val colors: IntArray get() = palette.copyOf()
    }

    /** Game Boy DMG-01 : LCD réfléchissant vert olive, contraste faible, terne. */
    val DMG: MonochromeDisplayProfile = Profile(
        id = "dmg",
        displayName = "Game Boy DMG",
        description = "Simulation de l'écran LCD vert olive de la Game Boy originale.",
        palette = intArrayOf(
            0xFFB5C18C.toInt(), // niveau 0 : jaune-vert clair, terne
            0xFF8A9A5B.toInt(), // niveau 1 : vert olive intermédiaire
            0xFF4E5F30.toInt(), // niveau 2 : vert olive foncé
            0xFF1B2410.toInt(), // niveau 3 : vert-noir très sombre
        ),
    )

    /** Game Boy Pocket : LCD gris argenté, contraste amélioré, plus net. */
    val POCKET: MonochromeDisplayProfile = Profile(
        id = "pocket",
        displayName = "Game Boy Pocket",
        description = "Simulation de l'écran LCD gris à contraste amélioré de la Game Boy Pocket.",
        palette = intArrayOf(
            0xFFC7C9C0.toInt(), // gris argenté très clair
            0xFF8C8F85.toInt(), // gris neutre
            0xFF4F524A.toInt(), // gris anthracite
            0xFF1B1C18.toInt(), // gris très sombre (pas de noir pur)
        ),
    )

    /** Game Boy Light, rétroéclairage éteint : proche de la Pocket, en gris. */
    val LIGHT_OFF: MonochromeDisplayProfile = Profile(
        id = "light_off",
        displayName = "Game Boy Light — Éclairage éteint",
        description = "Simulation de l'écran de la Game Boy Light, rétroéclairage éteint.",
        palette = intArrayOf(
            0xFFC2C4BB.toInt(),
            0xFF888B81.toInt(),
            0xFF4C4E46.toInt(),
            0xFF191A15.toInt(),
        ),
    )

    /** Game Boy Light, rétroéclairage allumé : dominante cyan très modérée. */
    val LIGHT_ON: MonochromeDisplayProfile = Profile(
        id = "light_on",
        displayName = "Game Boy Light — Éclairage allumé",
        description = "Simulation de l'écran rétroéclairé de la Game Boy Light, teinte bleu-vert modérée.",
        palette = intArrayOf(
            0xFF9FC7BE.toInt(), // cyan-vert clair, sans néon
            0xFF6E9E96.toInt(),
            0xFF3B5E58.toInt(),
            0xFF122522.toInt(),
        ),
    )

    /** Accessibilité : niveaux de gris neutres à contraste élevé. */
    val BLACK_WHITE: MonochromeDisplayProfile = Profile(
        id = "black_white",
        displayName = "Noir et blanc",
        description = "Affichage numérique neutre à contraste élevé.",
        palette = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFFAAAAAA.toInt(),
            0xFF555555.toInt(),
            0xFF000000.toInt(),
        ),
    )

    /** Tous les profils, dans l'ordre d'affichage des paramètres. */
    val all: List<MonochromeDisplayProfile> =
        listOf(DMG, POCKET, LIGHT_OFF, LIGHT_ON, BLACK_WHITE)

    /** Profil par défaut : Game Boy DMG. */
    val default: MonochromeDisplayProfile = DMG

    /** Profil d'identifiant [id], ou [default] si inconnu. */
    fun byId(id: String?): MonochromeDisplayProfile =
        all.firstOrNull { it.id == id } ?: default
}
