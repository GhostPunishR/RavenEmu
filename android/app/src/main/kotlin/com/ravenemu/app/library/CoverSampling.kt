package com.ravenemu.app.library

/**
 * Calcul du facteur de sous-échantillonnage d'une pochette.
 *
 * Les pochettes fournies par l'utilisateur sont des photos ou des scans :
 * plusieurs milliers de pixels de côté, pour une vignette qui en fait quelques
 * centaines. Décoder l'image à sa taille d'origine coûte le décodage complet
 * **et** l'allocation qui va avec — plusieurs mégaoctets par vignette — alors
 * que `inSampleSize` laisse le décodeur ne lire qu'un pixel sur *n*.
 *
 * Le calcul est isolé ici parce qu'il est purement arithmétique : c'est la
 * seule partie de la chaîne d'affichage qui se vérifie sans appareil ni image.
 */
object CoverSampling {

    /**
     * Plus grande puissance de deux telle que l'image reste au moins aussi
     * grande que la vignette.
     *
     * `BitmapFactory` arrondit de toute façon à la puissance de deux
     * inférieure : la calculer explicitement évite de dépendre de cet arrondi.
     * On s'arrête **avant** de passer sous la taille demandée, pour ne pas
     * afficher une image plus grossière que la vignette.
     */
    fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        // Dimensions inconnues : `BitmapFactory` rend 0 quand il n'a pas pu
        // lire l'en-tête. Ne pas sous-échantillonner est alors le seul choix
        // sûr — mieux vaut une image lourde qu'une image absente.
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        if (targetWidth <= 0 || targetHeight <= 0) return 1

        var facteur = 1
        while (
            sourceWidth / (facteur * 2) >= targetWidth &&
            sourceHeight / (facteur * 2) >= targetHeight
        ) {
            facteur *= 2
        }
        return facteur
    }
}
