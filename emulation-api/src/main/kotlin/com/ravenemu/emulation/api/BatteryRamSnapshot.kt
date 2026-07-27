package com.ravenemu.emulation.api

/**
 * Instantané de la RAM de cartouche, pris à un instant précis de la partie.
 *
 * Le protocole de sauvegarde tient en deux temps : l'appelant prend un
 * instantané, l'écrit où il le doit, puis n'acquitte le moteur qu'une fois
 * l'écriture **confirmée**. Acquitter d'avance — le défaut que ce type corrige
 * — fait croire au moteur que tout est sauvegardé alors qu'un disque plein ou
 * un fichier verrouillé a fait échouer l'écriture, et la partie disparaît sans
 * le moindre message.
 *
 * La [generation] rend l'acquittement sûr même si le jeu continue d'écrire
 * pendant l'écriture disque : elle identifie le contenu exact de [data]. Un
 * acquittement portant une génération dépassée est refusé, et les octets écrits
 * entre-temps restent à sauvegarder.
 */
class BatteryRamSnapshot(
    /** Contenu brut au format `.sav`, déjà copié : le moteur peut poursuivre. */
    val data: ByteArray,
    /** Génération de la RAM au moment de la copie. */
    val generation: Long,
)
