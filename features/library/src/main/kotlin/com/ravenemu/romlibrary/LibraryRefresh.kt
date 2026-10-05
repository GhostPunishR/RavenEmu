package com.ravenemu.romlibrary

/**
 * Fichier que le balayage a vu, reconnu à son extension, et écarté.
 *
 * Les analyseurs refusent toujours **avec une raison** : trop court pour porter
 * un en-tête, code unité inconnu, blocs de code hors du fichier. Cette raison
 * était jusqu'ici jetée par la boucle de balayage, et l'utilisateur se
 * retrouvait devant une bibliothèque à laquelle il manquait un jeu, sans rien
 * pour comprendre lequel ni pourquoi. Un fichier qu'on a soi-même rangé dans le
 * bon dossier, avec la bonne extension, et qui n'apparaît pas, ressemble à une
 * panne de l'application bien plus qu'à un refus motivé.
 */
data class RejectedRom(
    val fileName: String,
    val reason: String,
)

/**
 * Ce qu'un balayage rapporte : l'index, et ce qu'il a écarté en chemin.
 *
 * Les deux voyagent ensemble parce qu'ils se lisent ensemble. Rendre le seul
 * index obligerait l'appelant à recouper lui-même la liste des fichiers pour
 * deviner ce qui manque, ce qu'aucun appelant ne peut faire correctement.
 */
data class LibraryRefresh(
    val index: RomIndex,
    val rejected: List<RejectedRom> = emptyList(),
)
