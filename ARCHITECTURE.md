# Architecture de RavenEmu — contrat final après migration native

> **Statut : cible à atteindre, et non fonctionnalités déjà livrées.**
> Ce document sert de **contrat architectural à respecter pendant la migration
> et après sa finalisation**. Les agents lisent obligatoirement
> [AGENTS.md](AGENTS.md), puis la présente architecture avant toute évolution.
> [docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md) détaille le chemin de
> transition et les écarts restant à combler.
>
> **La migration préserve le design et le frontend propres à RavenEmu.**
> Des portions des pilotes AAudio/GLES2 de RetroArch sont adaptées avec
> [leurs licences et attributions](third_party/retroarch/README.md).
> Elle adopte le modèle frontend
> natif + ABI Libretro tout en préservant **RavenCore, l'interface RavenEmu,
> les sauvegardes, les utilisateurs et les fonctions déjà présentes**.

## 1. Principes immuables à la fin de la migration

1. **Moteur** : la logique d'émulation GB/GBC/GBA reste dans **RavenCore en
   C++20**, exposée par des cores Libretro distincts. L'hôte ne réimplémente
   jamais le CPU, les PPU, l'APU ou les détails des consoles.
2. **Frontend** : le propriétaire d'une session d'émulation et de l'ABI
   Libretro est un **frontend RavenEmu en C**, indépendant des interfaces
   Android/iOS. Il charge et décharge les cores et connecte audio, vidéo,
   entrées, sauvegardes et options. **Un seul propriétaire par session.**
3. **Android** : l'application migrée est écrite en **Java pour le code
   applicatif Android**, avec **JNI/NDK** vers le frontend C. Ne pas ajouter
   **Kotlin applicatif, Compose ou de dépendance `:shared`** à
   l'application Android migrée. Les `*.gradle.kts` existants sont seulement
   des scripts de construction.
4. **Interfaces** : retrouver l'expérience et la **charte de RavenEmu
   existante**, sans remplacement par une interface RetroArch générique.
   La bibliothèque, ses pages et jaquettes, la fiche de jeu, le lecteur,
   les commandes, les réglages et les menus doivent être conservés.
5. **Pilotes** : rendu et planification audio/vidéo/entrées du côté natif C
   autant que possible, avec adaptateurs de plateforme limités aux services
   nécessaires (fenêtre, audio, manette, permissions, cycle de vie).
6. **iOS** : préserver la version actuelle pendant la transition ; la cible
   est un **hôte iOS natif** branché à l'interface C commune, en conservant
   l'expérience RavenEmu. Ne pas promettre une migration iOS ou la suppression
   de la couche héritée avant que sa parité soit prouvée.
7. **Continuité** : aucune régression silencieuse sur les données, les
   formats de sauvegardes, les jeux, les options ou l'identité applicative.
   L'application expérimentale ne remplace jamais automatiquement la bêta.
8. **Provenance** : utiliser l'ABI Libretro sans affirmer utiliser le
   frontend officiel de RetroArch. Tout emprunt de source, pilote, shader ou
   ressource tierce doit respecter sa licence et garder les attributions.

## 2. Dépendances et séparation des couches

```text
                 Android : interface Java                 iOS : hôte natif
                 bibliothèque, menus,                     (après adaptation)
                 paramètres, stockage
                            |                                |
                    adaptateur Android                 adaptateur iOS
                       Java / JNI                       API C / FFI
                            |                                |
                            +---------------+----------------+
                                            |
                                   frontend/  [C]
                            API RavenEmu / hôte Libretro
                     gestion sessions, frames, entrée, SRAM, états
                            |               |                |
                       pilote vidéo     pilote audio     pilotes entrée
                       GLES natif C     AAudio natif C   manettes / tactile
                            |               |                |
                            +---------------+----------------+
                                            |
                             ABI publique Libretro [C]
                                            |
                            +---------------+---------------+
                            |               |               |
                      RavenCore GB     RavenCore GBC    RavenCore GBA
                           [C++20]         [C++20]         [C++20]
```

**Dépendances autorisées :** les plateformes appellent l'API C du frontend ;
le frontend appelle uniquement les cores par le contrat Libretro ;
RavenCore ne dépend pas des vues Java, de JNI, d'Android ou des écrans iOS.
Le frontend ne dépend pas du stockage Android ni de sa représentation
graphique. Une fonctionnalité propre à une plateforme reste dans son
adaptateur et ne contourne pas les responsabilités de ces couches.

**Interdictions :** remplacer RavenCore par un autre moteur sans validation
du propriétaire, appeler directement depuis l'interface Java des détails
internes C++ des cores, créer deux gestionnaires concurrents pour une même
session, ou maintenir deux formats de sauvegarde incompatibles sans
mécanisme explicite de transition.

## 3. Répertoires et responsabilité finale

| Répertoire / bloc | Architecture cible |
| --- | --- |
| `frontend/include/` | **Contrat public C** stable, paramètres et résultats documentés ; versionner l'ABI avant évolution cassante |
| `frontend/src/` | Hôte Libretro C, chargement dynamique, sessions, sérialisation, callbacks, erreurs, synchronisation |
| `androidNative/src/main/java/` | UI Android **Java**, bibliothèque, gestion fichiers, permissions, états de navigation et cycle de vie |
| `androidNative/src/main/cpp/` | Pont **JNI**, rendu natif, audio AAudio, commandes et intégration des cores |
| `androidNative/src/main/res/` | Thème, icônes et ressources fidèles à RavenEmu ; adaptation aux densités et insets système |
| `RavenCore` (dépôt indépendant) | Cores **C++20** GB/GBC/GBA, adaptés à l'ABI Libretro, révisions immuables et licences suivies |
| `iosApp/` et adaptateur iOS | Application iPhone à préserver ; après portage validé, hôte plateforme utilisant le même frontend C |
| `androidApp/`, `shared/`, `native/` | **Référence et application bêta historique** pendant la migration ; suppression/remplacement seulement sur décision explicite après parité |
| `policy/` et `.github/workflows/` | Tests des frontières, de l'architecture, de la compilation et de la compatibilité |
| `docs/MIGRATION_NATIVE.md` | État des jalons et tests restant à faire, **pas** une autre architecture finale |

Les noms de répertoires `androidNative/` et `frontend/` sont ceux de la
branche de migration ; une réorganisation de publication est possible
**uniquement après** validation de la parité et préservation de la bêta.
La cible est définie par la séparation des responsabilités, non par une
renommée prématurée des dossiers.

## 4. Cycle de vie, concurrence et API Libretro

- Chaque session possède son core et ses ressources ; initialisation,
  chargement ROM, `retro_run`, accès SRAM et sérialisation, déchargement
  et fermeture sont **sérialisés** sur le fil d'émulation désigné.
  Les callbacks ne manipulent pas les vues Android.
- Les commandes tactiles et manette passent par des événements ou un
  état atomique, sans bloquer le thread UI ; les accès de données
  partagées sont synchronisés. Ne pas exécuter d'E/S longues sur le
  thread GL, audio temps réel ou UI.
- Pause, reprise, arrière-plan, changement de ROM, rotation et destruction
  de surface doivent arrêter/reprendre proprement les ressources graphiques
  et sonores, préserver la SRAM et ne pas laisser tourner deux sessions.
- Le frontend gère les erreurs de core, les changements de format vidéo,
  les fréquences d'images et les taux d'échantillonnage déclarés ; ne pas
  supposer une cadence exacte de 60 Hz ni une fréquence audio universelle.
- Le rendu natif doit respecter l'aspect et le cadrage du jeu ;
  les menus ne doivent pas détruire l'état GL du core. Les pilotes sont
  remplaçables sans modifier RavenCore.

## 5. Contrat visuel, navigation Android et accessibilité

- **Source visuelle de référence** :
  `shared/src/commonMain/kotlin/com/ravenemu/shared/RavenEmuApp.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryComponents.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryDialogs.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/player/GbVideoScreen.kt`
  et `shared/src/commonMain/kotlin/com/ravenemu/shared/theme/RavenColors.kt`.
  Les lire pour **reproduire la présentation en Java**, pas pour importer
  du code Compose/Kotlin dans l'application migrée.
- Palette de base : fond `#0B0C12`, surface `#191B28`, accent
  `#A78BFA`, texte `#F5F2FF`, secondaire `#CBC4DC`,
  atténué `#AAA2BC`, contour `#3C4052`.
- Écran bibliothèque : titre et actions correctement alignés, barre de
  recherche, navigation par console, grille/liste, jaquettes facultatives,
  détails et action « Jouer » ; en cours de jeu : image, menu, commandes,
  sauvegardes et retour à la bibliothèque sans perte de session.
- **Aucun contrôle sous les interfaces système du téléphone** :
  les insets réels `systemBars`, `displayCutout` et `ime` gouvernent
  les marges ; tester barres d'état, boutons de navigation Android,
  navigation par gestes, portrait, paysage et clavier. Les marges ne
  doivent pas s'accumuler lors des relayouts.
- Les éléments tactiles ont une zone suffisante, un contraste adéquat,
  des descriptions accessibles, et restent navigables au contrôleur.
  Reprendre les fonctionnalités d'origine au lieu d'installer des
  fenêtres de test génériques comme interface définitive.

## 6. Stockage, identité et garanties des sauvegardes

- Pendant le développement, `com.ravenemu.nativepreview` est **isolé**
  de la bêta `com.ravenemu.preview`. Ne jamais accéder à ses données
  privées ni renommer implicitement son application ID.
- Index et ROMs privés par jeu : nom de fichier strictement validé,
  identité ROM + plateforme (SHA-256 ou format compatible documenté),
  plafonds de taille, détection des corruptions, écriture atomique.
  Utiliser le sélecteur de documents Android plutôt que l'accès large
  au stockage.
- La SRAM est sauvée aux transitions critiques et selon une politique
  de sauvegarde sûre. Les `save states` indiquent leur version et leur
  compatibilité avec le core ; tout échec conserve les données d'origine.
- À la sortie de migration, conserver ou **importer explicitement** les
  anciennes sauvegardes, préférences et bibliothèques avec contrôles
  d'intégrité, possibilité de retour arrière, tests de round-trip et
  accord du propriétaire. Ne jamais effacer les anciens fichiers au
  premier lancement.
- Ni jeux commerciaux, ni BIOS, ni ressources graphiques protégées
  ne sont incorporés par défaut. Respect des consentements et des
  licences, notamment pour les services et jaquettes distants.

## 7. Parité des fonctionnalités avant toute bascule

La version finale doit préserver ou rétablir **réellement**, et non seulement
montrer dans un menu, les capacités de RavenEmu actuellement disponibles :

- Émulation **GB, GBC, GBA**, image, audio, tactile, manettes, options de
  console et contrôles de performance/cadence.
- Bibliothèque, import de fichiers/dossiers pris en charge, jaquettes,
  recherche, gestion des jeux et identité des sauvegardes.
- SRAM, save states et emplacements, import/export des sauvegardes,
  continuité des anciens fichiers.
- Menus, paramètres réellement appliqués, options graphiques/sonores,
  réglages de manette et skins Delta.
- Liaison locale et sans fil, RetroAchievements et contraintes Hardcore,
  toutes fonctions historiques effectivement proposées dans la bêta.
- iOS et parcours Apple : conserver une version utilisable, définir le
  portage avant retrait de l'ancienne couche.

Toute différence doit figurer dans une **liste d'écarts documentée**, avec
un choix explicite du propriétaire si une fonction est abandonnée.
Une interface qui affiche une option sans fonctionnalité réelle n'est
**pas** considérée comme paritaire.

## 8. Critères de fin de migration et évolution future

La migration est considérée **finalisée uniquement si** :

1. Le propriétaire accepte l'interface sur captures et **appareil réel** ;
   barres Android, clavier, densités, orientations et navigation ont été
   vérifiés. L'apparence et les actions de référence sont conservées.
2. Les tests C/Libretro, RavenCore C++20, JNI/Java, APK ARM64 et CI bêta
   Android/iOS réussissent ; tests de ROMs **libres/homebrew** et tests
   manuels de sessions longues (audio, vidéo, entrée, veille, reprise,
   sauvegarde/restauration) sont documentés.
3. La compatibilité des données et la stratégie de retour arrière ont été
   exercées sur une copie, sans perte de ROMs, SRAM, états ou paramètres.
4. Les capacités listées en section 7 sont fonctionnelles ou font l'objet
   d'une exception **approuvée et tracée**.
5. Les licences tierces, vérifications de sécurité et modalités de
   distribution sont vérifiées ; les identifiants applicatifs restent
   conformes au plan de publication approuvé.
6. **Aucune fusion sur `main` ni remplacement de la bêta sans décision
   explicite du propriétaire.** Une CI verte n'est pas une autorisation de
   publication.

Après validation finale, les agents **doivent continuer à respecter ces
mêmes frontières**, maintenir les tests et ne pas réintroduire du Kotlin
applicatif, une API moteur parallèle, une interface différente ou une
régression de sauvegardes sans décision explicite du propriétaire.
Si un changement d'architecture est nécessaire, le proposer, documenter
son impact, obtenir l'accord, puis modifier `ARCHITECTURE.md` et les tests
correspondants **dans le même changement**.

---

# Architecture de la bêta existante — référence historique

RavenEmu partage une interface Compose Multiplatform entre Android et iOS. Le projet Gradle est à la racine ; les moteurs C++20 vivent dans [RavenCore](https://github.com/GhostPunishR/RavenCore).

| Module | Responsabilité |
| --- | --- |
| `shared/` | Interface Compose, modèles, navigation, réglages et logique Kotlin commune |
| `androidApp/` | Activité, session de jeu, audio, stockage privé, imports, manettes, skins et RetroAchievements |
| `iosApp/` | Hôte SwiftUI, session série, stockage, audio, spécification XcodeGen et adaptateur Swift |
| `native/` | CMake, pont JNI Android et module API C pour Apple |
| `policy/` | Tests JVM des règles de CI et des chemins du dépôt |
| `docs/` | Sources du site statique |
| `wiki/` | Documentation et sources du wiki |

`settings.gradle.kts` inclut `shared`, `androidApp` et `policy`. Toutes les commandes Gradle partent de la racine. Les documents `TERMS.md`, `PRIVACY.md` et `LICENSE` y sont lus pour générer les textes légaux intégrés au module partagé.

> **Cette partie décrit le code hérité présent avant la migration.** Les
> détails Compose/Kotlin, JNI historique et RavenCore API C ci-dessous ne
> sont **pas** le contrat de l'application migrée. Ils sont conservés pour
> l'analyse de compatibilité jusqu'à la validation de la nouvelle architecture.

## Organisation de `shared/`

L'interface commune suit le même découpage sous `com.ravenemu.shared`, les tests en miroir :

| Paquet | Rôle |
| --- | --- |
| (racine) | `RavenEmuApp`, l'écran d'entrée, et sur iOS `IosEntry` (les classes que Swift appelle). |
| `theme` | Couleurs, glyphes et icônes. |
| `library` | Catalogue (`GamePlatform`, `GameEntry`), import de ROM, écrans et dialogues de la bibliothèque. |
| `player` | Écran de jeu, commandes tactiles, images et mesures. |
| `audio` | File PCM. |
| `saves` | Emplacements d'état, revue d'import de sauvegarde. |
| `retro` | Écrans et état de RetroAchievements. |
| `link` | Deux jeux reliés sur un téléphone. |
| `wireless` | Affichage du câble sans fil. |
| `settings` | Préférences (`Settings`), leur écran, entrées des skins Delta. |
| `about` | À propos, documents légaux et licences ; `LegalTexts` y est généré par `:shared:generateLegalTexts`. |

Swift voit les classes Kotlin sous leur seul nom, sans paquet : ranger les paquets ne change pas l'API vue d'iOS.

## Organisation de `androidApp/`

Les sources Kotlin sont rangées par rôle sous `com.ravenemu.android`, les tests en miroir :

| Paquet | Rôle |
| --- | --- |
| (racine) | `MainActivity`, seule à la racine : son nom complet est l'identité de l'application pour le lanceur Android. |
| `engine` | Le moteur natif (`NativeCore`) et la session de jeu (`AndroidGbSession`, `GbButtonLatch`). |
| `player` | Le lecteur à l'écran : boucle d'émulation, cadence, manette, vibration et capteurs de cartouche. |
| `audio` | Sortie son, rééchantillonnage, carillon des succès. |
| `library` | Bibliothèque, import de ROM et de dossiers, jaquettes. |
| `saves` | Sauvegardes de cartouche, états instantanés, transferts `.sav`/`.srm`/ZIP. |
| `retro` | RetroAchievements : compte, SDK rcheevos, Hardcore, journal hors ligne. |
| `link` | Deux jeux sur un téléphone, reliés par un câble local. |
| `wireless` | Câble link sans fil entre deux téléphones. |
| `settings` | Préférences Android. |
| `deltaskin` | Skins de contrôleur Delta. |

Les classes à fonctions natives (`NativeCore`, `NativeLink`, `NativeRemoteLink`, `NativeAchievementsBridge`) donnent leur paquet aux symboles JNI de `native/android/` : `Java_com_ravenemu_android_<paquet>_<Classe>_<fonction>`. `AndroidPolicyTest` vérifie que chaque fonction `external` a son symbole, et inversement.

## Frontière native

La [configuration CMake](native/CMakeLists.txt) épingle RavenCore au commit `15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc` (version 0.14.0) et à son API C aux noms `ravenemu_*`. Mettre à jour le dépôt RavenCore ne modifie pas automatiquement cette révision.

Android utilise `NativeCore.kt` et `native/android/bridge_jni.cpp`. Swift utilise `RavenNativeCore.swift` et `native/apple/module.modulemap`. Les deux passent par la même API C : ROM, pixels ARGB8888, audio PCM16, boutons, mémoire et sauvegardes. Le header est celui du SDK RavenCore épinglé, utilisé tel quel. Libretro n'est pas l'interface d'émulation de l'application ; son catalogue de jaquettes reste un service facultatif.

RetroAchievements utilise le SDK officiel rcheevos, également épinglé par SHA, et le pont maintenu dans `native/android/`.

## Câble link sans fil (Android)

Deux téléphones du même réseau local se relient par le câble distant de RavenCore (`ravenemu_remote_link_*`, [docs/remote_link.md de RavenCore](https://github.com/GhostPunishR/RavenCore/blob/15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc/docs/remote_link.md)). RavenCore échange les données du câble ; l'application les transporte et décide quand brancher.

| Pièce | Rôle |
| --- | --- |
| `NativeRemoteLink.kt` | Le câble natif rattaché au jeu en cours ; `AndroidGbSession` le crée et le détruit entre deux images. Ces fichiers sont dans le paquet `wireless`. |
| `WirelessLink.kt` | Décide de tout, sur un seul fil : recherche, choix, liaison, pause, débranchement. |
| `WirelessLinkDiscovery.kt` | Annonce et recherche DNS-SD (`NsdManager`, service `_ravenemulink._tcp`). |
| `WirelessLinkConnection.kt` | Une connexion TCP : un fil lit, un autre écrit, battement de cœur. |
| `WirelessLinkProtocol.kt` | Trames, messages de contrôle et règle de choix, testés sur la JVM. |

- **Quand.** `ravenemu_link_wanted` dit si le jeu s'est servi de son port link dans les trois dernières secondes. Tant que c'est le cas (plus dix secondes de marge), le téléphone s'annonce et cherche. Le reste du temps, il n'annonce rien. Réglage coupé, aucune vérification ne tourne.
- **Découverte.** Sur Android 14 et plus, chaque téléphone trouvé est suivi par `registerServiceInfoCallback` (adresses, attributs, départ). Avant, `resolveService` résout un service à la fois.
- **Android 17.** En ciblant le SDK 37, l'accès au réseau local devient la permission `ACCESS_LOCAL_NETWORK` : `LocalNetworkAccess` la fait demander une fois par jeu, quand le jeu veut se relier. En ciblant moins, INTERNET suffit et Google demande de ne pas la déclarer ; `AndroidPolicyTest` impose la déclaration exactement à partir du SDK 37.
- **Qui.** Un seul partenaire compatible (même version, même famille de consoles) : le téléphone au plus petit identifiant l'appelle. Plusieurs : le joueur choisit. Deux connexions entre les mêmes téléphones : les deux côtés gardent celle qu'a ouverte le plus petit identifiant, qui est aussi le joueur 0 (parent GBA).
- **Trames TCP.** `[canal u8][taille u8][contenu]`. Canal 0 : les messages de RavenCore, tels quels. Canal 1 : présentation (version, famille, identifiant, pause, modèle, jeu), pause, battement de cœur (chaque seconde sans autre envoi) et départ (avec ou sans refus). Cinq secondes sans rien recevoir ferment la connexion.
- **Fils.** RavenCore envoie depuis le fil d'émulation : l'envoi ne fait que remplir une file. Les messages reçus vont au câble natif depuis le fil de lecture, puis un fil de service les fait lire à la console entre deux images, ou pendant une pause, sous le verrou de la session.
- **Une image peut attendre l'autre console.** Le fil de l'interface ne prend donc plus le verrou natif : les boutons s'appliquent avant l'image suivante, les caractéristiques du jeu sont gardées à part, et la sauvegarde de `onPause` abandonne après 300 ms. `onStop` débranche le câble, ce qui libère une image en attente, puis sauvegarde.
- **Pause.** Le menu du jeu, ou une interruption, envoie une pause à l'autre téléphone tant que le câble sert. Celui-ci n'avance plus d'image, mais sa console répond toujours aux transferts.
- **Interdits.** Charger un état est refusé tant que le câble est branché, dans l'interface et dans la session.

## Contrats à préserver

- Les moteurs ignorent l'interface et les services des plateformes.
- Les appels à une même instance native sont sérialisés.
- Android conserve bibliothèque, jaquettes et sauvegardes dans son espace privé, avec le SHA-256 de la ROM comme identité.
- Les imports utilisent le sélecteur Android sans accès global au stockage.
- Les ZIP de sauvegarde contiennent un manifeste et des empreintes, aucune ROM ; les archives historiques restent importables.
- Les paquets sont `com.ravenemu.android` et `com.ravenemu.shared`, l'identifiant Android `com.ravenemu.preview`. Le déplacement du dépôt ne change pas cette identité ni les clés de stockage.

## État des plateformes

Android raccorde le lecteur, le rendu, l'audio, les commandes, la bibliothèque, les skins et les sauvegardes. Les performances GBC/GBA et les déblocages RetroAchievements nécessitent encore des essais sur téléphone.

iOS raccorde une première session : `IosHost` détient les états Compose sur le thread principal, `RavenRuntime` détient les handles sur une file série, `RavenLibraryStore` conserve les copies et écrit atomiquement, `RavenAudioOutput` borne la file audio. Les frames immuables passent par NSData vers Compose ; un seul envoi d'image peut attendre le thread principal. Les réglages des services non raccordés sont masqués. Voir [la première tranche iOS](iosApp/README.md) ; les essais réels et la distribution iPhone restent à effectuer. Une compilation ne prouve pas une partie jouable sur appareil. Voir [BUILDING.md](BUILDING.md), [CONTRIBUTING.md](CONTRIBUTING.md) et [CHANGELOG.md](CHANGELOG.md).
