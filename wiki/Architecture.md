# Architecture

RavenEmu sépare les moteurs d'émulation des composants Android.

## Modules

| Module | Type | Responsabilité |
|---|---|---|
| `app` | Android | Écrans, navigation et session d'émulation |
| `emulation-api` | Kotlin/JVM | Contrats communs entre application et moteurs |
| `gameboy-core` | Kotlin/JVM | Game Boy et Game Boy Color |
| `gba-core` | Kotlin/JVM | Game Boy Advance |
| `rom-library` | Kotlin/JVM | En-têtes, empreintes, identification et index |
| `storage` | Android | Sélecteur de documents, sauvegardes, états et pochettes |
| `renderer` | Android | Affichage du framebuffer |
| `input` | Android | Commandes tactiles et manettes |
| `settings` | Android | Préférences et profils |

## Principes

### Moteurs indépendants

`gba-core` ne dépend pas de `gameboy-core`. Chaque console implémente les contrats de `emulation-api`.

### Kotlin/JVM testable

Les moteurs ne dépendent pas d'Android. Les tests peuvent s'exécuter sur une JVM de bureau sans lancer un émulateur Android.

### Sélection par fabrique

L'application détecte la console d'une ROM et demande le moteur correspondant. Les écrans Android n'instancient pas directement les coeurs.

### Données locales

Le stockage passe par des interfaces dédiées. Les moteurs reçoivent les octets nécessaires sans connaître les URI Android.

### Boucle déterministe

Chaque moteur avance selon un budget de cycles et produit un framebuffer, des échantillons audio et des données de sauvegarde par l'API commune.

## Ajouter une console

Un nouveau moteur doit:

1. vivre dans son propre module;
2. implémenter `emulation-api`;
3. rester indépendant des moteurs existants;
4. fournir des tests synthétiques;
5. ajouter sa détection à la bibliothèque;
6. déclarer ses capacités à l'application;
7. documenter clairement ses limites.

Voir [[Contribution]] avant de proposer une évolution importante.
