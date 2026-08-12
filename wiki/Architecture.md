# Architecture

RavenEmu V2 sépare physiquement les moteurs, l'interface native, le moteur Kotlin,
les services Android et les features. Les chemins Gradle correspondent aux dossiers.

## Couches

| Couche | Type | Responsabilité |
|---|---|---|
| `app/android` | Android | Coque UI et composition finale |
| `cores/common` | C++20 | Contrat et primitives communes |
| `cores/gb` | C++20 | Matériel commun GB/GBC et différences DMG explicites |
| `cores/gbc` | C++20 | Façade CGB et extraction progressive du matériel spécifique |
| `cores/gba` | C++20 | Game Boy Advance |
| `native/api` | C++20 | Contrat natif générique |
| `native/jni` | Java/C++ | Frontière JNI uniquement |
| `engine/*` | Kotlin/JVM | API, runtime, session, état, save, audio, diagnostics |
| `platform/android/*` | Android | Audio, rendu, entrées, stockage, vibration, lifecycle |
| `features/*` | JVM/Android | Library, player, settings, skins, savestates, diagnostics |

## Principes

### Moteurs indépendants

Le moteur GBA ne dépend pas du moteur Game Boy. Chaque implémentation C++ expose le même contrat natif, puis son adaptateur Kotlin implémente le contrat porté par `engine/api`.

### C++ testable sur l'hôte

Les moteurs ne dépendent pas d'Android. CMake construit et teste leur bibliothèque statique sur l'hôte ; la CI compile également le pont JNI avec les vrais en-têtes Java. Les contrats, métadonnées et implémentations Kotlin de référence restent testés sur JVM sans émulateur Android.

### Frontière JNI étroite

Les objets Kotlin ne traversent pas le moteur : JNI transporte des handles, des scalaires et des tableaux primitifs. Les fournisseurs de production créent les adaptateurs C++ ; les anciennes implémentations Kotlin ne vivent plus que dans les sources de test, comme références de régression. La session Android, le renderer, les entrées et DeltaSkin continuent d'utiliser uniquement `EmulatorCore`.

### Sélection par fabrique

L'application détecte la console d'une ROM et demande le moteur correspondant. Les écrans Android n'instancient pas directement les cœurs.

### Identité produit et modèle matériel

`ConsoleType` désigne une identité de bibliothèque et de stockage, pas le modèle
physique sélectionné pour une exécution : `engine/runtime` couvre encore la
Game Boy et la Game Boy Color sous la même identité persistée.

Ce que déclare la cartouche (monochrome, compatible couleur, ou couleur exigée) est une métadonnée distincte, lue à l'octet `0x0143` de l'en-tête et portée par `GameBoyCartridgeMode`. L'extension du fichier ne décide de rien : des `.gbc` contiennent des cartouches monochromes, des `.gb` des cartouches couleur.

La fabrique C++ choisit séparément le modèle physique `automatic`, `dmg` ou
`cgb`. Après lecture de l'en-tête, la machine possède un mode effectif explicite
DMG, CGB natif ou compatibilité DMG sur CGB. La façade `gbc_raven_core` force le
dernier choix matériel au lieu de rediriger sans information vers la fabrique
automatique.

Les formats persistés désignent la console par un identifiant **figé** (`ConsoleType.storageId`), jamais par son rang de déclaration : ajouter ou retirer une console ne doit pas réinterpréter les fichiers déjà enregistrés par les utilisateurs. Un identifiant retiré n'est jamais réattribué.

### Restauration transactionnelle

Charger un état instantané construit une machine neuve et ne remplace la machine active qu'en cas de succès complet. Un fichier tronqué, corrompu ou d'une autre version laisse la partie en cours intacte et jouable, quel que soit l'endroit où la lecture échoue.

### Données locales

Le stockage passe par des interfaces dédiées. Les moteurs reçoivent les octets nécessaires sans connaître les URI Android.

### Boucle déterministe

Chaque moteur avance selon un budget de cycles et produit un framebuffer, des échantillons audio et des données de sauvegarde par l'API commune.

Dans le cœur GB/GBC, chaque accès CPU et chaque cycle interne est ordonnancé à
une frontière de M-cycle. Le bus avance les périphériques par dots et laisse les
DMA prendre le bus entre deux micro-opérations. Le PPU possède un fetcher/FIFO
dont l'état intermédiaire fait partie des états instantanés.

Le port série et le port infrarouge dépendent uniquement des interfaces communes
`LinkEndpoint` et `InfraredEndpoint`. Le transport local, Android, réseau ou
Bluetooth appartient à l'hôte, jamais au cœur.

## Ajouter une console

Un nouveau moteur doit:

1. vivre dans une implémentation C++ isolée sous `cores/`, avec ses organes séparés;
2. exposer son adaptateur via `engine/api` et `engine/runtime`;
3. rester indépendant des moteurs existants;
4. fournir des tests synthétiques natifs;
5. ajouter sa détection à la bibliothèque;
6. déclarer ses capacités à l'application;
7. se voir attribuer un `storageId` **neuf**, jamais une valeur déjà employée;
8. documenter clairement ses limites.

Voir [[Contribution]] avant de proposer une évolution importante.
