# Architecture

RavenEmu sépare les moteurs d'émulation des composants Android.

## Modules

| Module | Type | Responsabilité |
|---|---|---|
| `app` | Android | Écrans, navigation et session d'émulation |
| `emulation-api` | Kotlin/JVM | Contrats communs entre application et moteurs |
| `deltaskin` | Kotlin/JVM | Manifeste, validation ZIP, stockage, disposition et inputs DeltaSkin |
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

### Un cœur par console, pas par modèle

`ConsoleType` désigne un **cœur d'émulation**, pas un modèle commercialisé : `gameboy-core` couvre à lui seul la Game Boy et la Game Boy Color, et n'y figure donc qu'une fois.

Ce que déclare la cartouche (monochrome, compatible couleur, ou couleur exigée) est une métadonnée distincte, lue à l'octet `0x0143` de l'en-tête et portée par `GameBoyCartridgeMode`. L'extension du fichier ne décide de rien : des `.gbc` contiennent des cartouches monochromes, des `.gb` des cartouches couleur.

Les formats persistés désignent la console par un identifiant **figé** (`ConsoleType.storageId`), jamais par son rang de déclaration : ajouter ou retirer une console ne doit pas réinterpréter les fichiers déjà enregistrés par les utilisateurs. Un identifiant retiré n'est jamais réattribué.

### Restauration transactionnelle

Charger un état instantané construit une machine neuve et ne remplace la machine active qu'en cas de succès complet. Un fichier tronqué, corrompu ou d'une autre version laisse la partie en cours intacte et jouable, quel que soit l'endroit où la lecture échoue.

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
7. se voir attribuer un `storageId` **neuf**, jamais une valeur déjà employée;
8. documenter clairement ses limites.

Voir [[Contribution]] avant de proposer une évolution importante.
