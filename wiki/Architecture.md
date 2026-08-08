# Architecture

RavenEmu sépare les moteurs d'émulation des composants Android.

## Modules

| Module | Type | Responsabilité |
|---|---|---|
| `android/app` | Android | Écrans, navigation et session d'émulation |
| `core/emulation-api` | Kotlin/JVM | Contrats communs entre application et moteurs |
| `cores/CGBRavenCore` | C++20 | CPU, bus, vidéo, audio et cartouches Game Boy / Game Boy Color |
| `cores/GBARavenCore` | C++20 | CPU, bus, vidéo, audio, DMA, BIOS et cartouches Game Boy Advance |
| `cores/shared` | C++20 | Contrat `Core` et utilitaires communs aux deux cœurs |
| `core/native-bridge` | Java/JNI | Handles natifs et transport de tableaux primitifs |
| `core/deltaskin` | Kotlin/JVM | Manifeste, validation ZIP, stockage, disposition et entrées du format `.deltaskin` |
| `core/gameboy-core` | Kotlin/JVM | Adaptateur et métadonnées Game Boy/Game Boy Color |
| `core/gba-core` | Kotlin/JVM | Adaptateur et métadonnées Game Boy Advance |
| `core/rom-library` | Kotlin/JVM | En-têtes, empreintes, identification et index |
| `android/storage` | Android | Sélecteur de documents, sauvegardes, états et pochettes |
| `android/renderer` | Android | Affichage du framebuffer |
| `android/input` | Android | Commandes tactiles et manettes |
| `android/settings` | Android | Préférences et profils |

## Principes

### Moteurs indépendants

Le moteur GBA ne dépend pas du moteur Game Boy. Chaque implémentation C++ expose le même contrat natif, puis son adaptateur Kotlin implémente `emulation-api`.

### C++ testable sur l'hôte

Les moteurs ne dépendent pas d'Android. CMake construit et teste leur bibliothèque statique sur l'hôte ; la CI compile également le pont JNI avec les vrais en-têtes Java. Les contrats, métadonnées et implémentations Kotlin de référence restent testés sur JVM sans émulateur Android.

### Frontière JNI étroite

Les objets Kotlin ne traversent pas le moteur : JNI transporte des handles, des scalaires et des tableaux primitifs. Les fournisseurs de production créent les adaptateurs C++ ; les anciennes implémentations Kotlin ne vivent plus que dans les sources de test, comme références de régression. La session Android, le renderer, les entrées et DeltaSkin continuent d'utiliser uniquement `EmulatorCore`.

### Sélection par fabrique

L'application détecte la console d'une ROM et demande le moteur correspondant. Les écrans Android n'instancient pas directement les cœurs.

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

1. vivre dans une implémentation C++ isolée sous `cores/`, un dossier par organe;
2. exposer un adaptateur qui implémente `emulation-api`;
3. rester indépendant des moteurs existants;
4. fournir des tests synthétiques natifs;
5. ajouter sa détection à la bibliothèque;
6. déclarer ses capacités à l'application;
7. se voir attribuer un `storageId` **neuf**, jamais une valeur déjà employée;
8. documenter clairement ses limites.

Voir [[Contribution]] avant de proposer une évolution importante.
