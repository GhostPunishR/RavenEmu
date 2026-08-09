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
        listOf(DMG, POCKET, BLACK_WHITE)

    /**
     * Identifiants retirés du catalogue.
     *
     * Les deux profils Game Boy Light ont été écartés : entre l'écran éteint et
     * la Pocket, l'écart était trop faible pour se voir, et la variante allumée
     * n'apportait qu'une teinte. Un réglage que personne ne distingue encombre
     * la liste sans rien offrir.
     *
     * Ils sont nommés ici plutôt qu'oubliés : [byId] retombe déjà sur le profil
     * par défaut pour un identifiant inconnu, et c'est ce qui protège la
     * préférence enregistrée chez un joueur qui les avait choisis. Cette liste
     * fige ce comportement, et interdit de réattribuer ces identifiants à un
     * futur profil qui apparaîtrait alors sans prévenir chez ces joueurs.
     */
    val retiredIds: Set<String> = setOf("light_off", "light_on")

    /** Profil par défaut : Game Boy DMG. */
    val default: MonochromeDisplayProfile = DMG

    /** Profil d'identifiant [id], ou [default] si inconnu. */
    fun byId(id: String?): MonochromeDisplayProfile =
        all.firstOrNull { it.id == id } ?: default
}
