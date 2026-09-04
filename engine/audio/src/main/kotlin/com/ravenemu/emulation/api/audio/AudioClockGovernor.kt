package com.ravenemu.emulation.api.audio

import kotlin.math.abs

/**
 * Asservissement du débit de production sur l'horloge de sortie.
 *
 * ### Le problème
 *
 * Le moteur produit trente-deux mille sept cent soixante-huit échantillons pour
 * chaque seconde **émulée**, et la sortie en consomme quarante-huit mille pour
 * chaque seconde **réelle**. Ces deux secondes ne sont pas la même : le
 * quartz du téléphone n'a aucune raison de tomber exactement sur la cadence
 * vidéo à laquelle la session s'aligne. Un écart d'un dixième de pour cent,
 * ordinaire, retire ou ajoute une trame d'avance toutes les seize secondes.
 *
 * Sans rien pour le rattraper, cet écart s'accumule dans un seul sens. Le
 * tampon finit soit par déborder — la session bloque de plus en plus longtemps
 * en écriture, l'image ralentit — soit par se vider, et c'est alors la
 * séquence rupture, arrêt, vidage, repréremplissage : un blanc de cent
 * millisecondes, qui revient à intervalle régulier.
 *
 * ### La correction
 *
 * Plutôt que d'attendre la rupture pour la réparer, on corrige en permanence,
 * si peu que rien : le rééchantillonneur avance dans l'entrée un peu plus vite
 * ou un peu moins vite, ce qui produit un peu moins ou un peu plus de trames de
 * sortie. La correction est bornée à [MAX_CORRECTION], soit un demi pour cent —
 * moins de neuf centièmes de demi-ton, en dessous du seuil auquel une oreille
 * même exercée entend un changement de hauteur, et très en dessous de ce que
 * masque un haut-parleur de téléphone.
 *
 * Trois précautions rendent cette correction inaudible :
 *
 * - une **zone morte** autour de la cible : tant que l'avance reste dans la
 *   tolérance, la correction vaut exactement un et le son passe au débit natif,
 *   sans le moindre écart de hauteur. C'est le cas ordinaire ;
 * - une **limite de pente** ([MAX_STEP] par bloc) : la hauteur ne peut pas
 *   sauter, elle ne peut que glisser, sur une fraction de seconde ;
 * - un **rejet des relevés absurdes** : le compteur de trames jouées de la
 *   plateforme repart de zéro à chaque vidage et déborde au bout d'une douzaine
 *   d'heures de lecture continue. Une avance négative, ou plus grande que ce
 *   que le tampon peut contenir, ne dit rien d'utilisable : la correction en
 *   cours est alors conservée telle quelle.
 *
 * C'est une boucle proportionnelle, sans terme intégral : l'erreur résiduelle
 * qu'elle laisse est justement ce qui maintient la correction, et la zone morte
 * fait que cette erreur ne se paie en hauteur que lorsqu'il le faut.
 */
class AudioClockGovernor(
    private val targetFrames: Int,
    private val toleranceFrames: Int = targetFrames / 4,
) {
    init {
        require(targetFrames > 0) { "Cible de remplissage invalide" }
        require(toleranceFrames >= 0) { "Tolérance invalide" }
    }

    /** Correction courante, à passer au rééchantillonneur. */
    var rateScale: Double = 1.0
        private set

    /**
     * Prend en compte l'avance mesurée dans la sortie, en trames, et rend la
     * correction à appliquer au bloc suivant.
     *
     * Une avance **supérieure** à la cible veut dire que le moteur produit plus
     * vite que la sortie ne consomme : la correction passe au-dessus de un, le
     * rééchantillonneur avance plus vite dans l'entrée et rend donc moins de
     * trames. Une avance inférieure fait l'inverse.
     */
    fun onQueuedFrames(queuedFrames: Int): Double {
        if (queuedFrames < 0 || queuedFrames > targetFrames * IMPLAUSIBLE_FACTOR) {
            return rateScale
        }
        val error = queuedFrames - targetFrames
        val desired = if (abs(error) <= toleranceFrames) {
            1.0
        } else {
            val relative = error.toDouble() / (targetFrames * FULL_SCALE_ERROR)
            1.0 + MAX_CORRECTION * relative.coerceIn(-1.0, 1.0)
        }
        rateScale += (desired - rateScale).coerceIn(-MAX_STEP, MAX_STEP)
        return rateScale
    }

    /** Repart sans correction (reprise après un vidage de la sortie). */
    fun reset() {
        rateScale = 1.0
    }

    companion object {
        /**
         * Correction maximale, en proportion du débit.
         *
         * Un demi pour cent vaut huit centièmes et demi de demi-ton. Le seuil
         * couramment retenu pour la perception d'un écart de hauteur sur un son
         * tenu est de l'ordre de cinq à dix fois cela.
         */
        const val MAX_CORRECTION = 0.005

        /**
         * Variation maximale de la correction d'un bloc à l'autre.
         *
         * Les blocs arrivent au rythme de la vidéo, une soixantaine par
         * seconde : la correction met environ un tiers de seconde à parcourir
         * toute son amplitude. Un glissement de cette lenteur ne s'entend pas,
         * là où un saut, lui, s'entendrait.
         */
        const val MAX_STEP = 0.00025

        /**
         * Écart, en proportion de la cible, auquel la correction est à fond.
         *
         * Le gain se lit ainsi : la moitié de la cible d'écart appelle le demi
         * pour cent, et le reste suit proportionnellement. Une boucle
         * proportionnelle se stabilise là où sa correction compense exactement
         * la dérive : avec ce gain, une horloge fausse de trois millièmes — un
         * quartz déjà médiocre — se rattrape en gardant les deux tiers de
         * l'avance visée, soit quatre trames vidéo de réserve. Un gain plus
         * faible laisserait l'avance fondre presque jusqu'au bout avant de
         * corriger assez ; un gain plus fort ferait travailler la hauteur pour
         * des écarts qui se résorbent d'eux-mêmes.
         */
        private const val FULL_SCALE_ERROR = 0.5

        /**
         * Au-delà de ce multiple de la cible, le relevé n'est pas crédible :
         * le tampon de sortie lui-même ne fait qu'une fraction de cela.
         */
        private const val IMPLAUSIBLE_FACTOR = 16
    }
}
