# Architecture de RavenEmu — contrat de l'application native

> **Statut : l'application native est la seule application du dépôt.**
> Le 6 novembre 2026, le propriétaire a fait fusionner la migration dans
> `main` et retirer la bêta (`androidApp/`, `shared/`, `iosApp/`, `native/`),
> iOS compris. Ce document est le **contrat architectural à respecter pour
> toute évolution**. Les agents lisent obligatoirement [AGENTS.md](AGENTS.md),
> puis la présente architecture. [docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md)
> retrace la migration et tient la liste des écarts de parité restants.
>
> Le code de la bêta reste consultable dans l'historique git, au commit
> `de1c9cc` (dernier état de `main` avec la bêta) :
> `git show de1c9cc:shared/src/commonMain/kotlin/com/ravenemu/shared/theme/RavenColors.kt`.
>
> **L'application garde le design et le frontend propres à RavenEmu.**
> Des portions des pilotes AAudio/GLES2 de RetroArch sont adaptées avec
> [leurs licences et attributions](legal/attributions/retroarch.md).
> Elle suit le modèle frontend
> natif + ABI Libretro tout en préservant **RavenCore, l'interface RavenEmu,
> les sauvegardes, les utilisateurs et les fonctions déjà présentes**.

## Organisation du dépôt

- `frontend/` : frontend C et ABI Libretro, indépendant des pilotes matériels.
- `android/` : application Java organisée en sous-packages `ui/library`, `ui/player`, `ui/settings`, `ui/common`, `data/library`, `data/saves`, `settings`, `bridge` et `platform` sous `com.ravenemu.app`, avec adaptateur JNI C dans `src/main/cpp/jni/`.
- `android/src/main/cpp/hardware/` : pilotes AAudio, GLES2 et entrées.
- `android/src/main/cpp/media/` : capture des miniatures d'états.
- `tests/hardware/` : tests unitaires des pilotes natifs, séparés de `frontend/tests/`.
- `legal/attributions/` : notices de provenance des sources externes adaptées, conservées avec leurs licences.
- `policy/` : tests JVM des règles du dépôt (workflows, symboles JNI, permissions).
- `scripts/` : contrôles de l'organisation des sources, de l'optimisation native et de l'archive des sources.
- `docs/` : site statique ; `wiki/` : documentation utilisateur.

L'APK de développement `com.ravenemu.dev` est indépendant de la bêta `com.ravenemu.preview`, qui peut rester installée sur les téléphones : ses données privées ne sont ni lues ni migrées automatiquement. Le changement d'identifiant ne vaut pas accord pour publier une version.

## 1. Principes immuables

1. **Moteur** : la logique d'émulation GB/GBC/GBA reste dans **RavenCore en
   C++20**, exposée par des cores Libretro distincts. L'hôte ne réimplémente
   jamais le CPU, les PPU, l'APU ou les détails des consoles.
2. **Frontend** : le propriétaire d'une session d'émulation et de l'ABI
   Libretro est un **frontend RavenEmu en C**, indépendant des interfaces
   des plateformes. Il charge et décharge les cores et connecte audio, vidéo,
   entrées, sauvegardes et options. **Un seul propriétaire par session.**
3. **Android** : l'application est écrite en **Java pour le code
   applicatif Android**, avec **JNI/NDK** vers le frontend C. Ne pas ajouter
   **Kotlin applicatif, Compose ou module Kotlin partagé** à
   l'application Android. Les `*.gradle.kts` existants sont seulement
   des scripts de construction.
4. **Interfaces** : garder l'expérience et la **charte de RavenEmu**
   validées sur la bêta, sans remplacement par une interface RetroArch générique.
   La bibliothèque, ses pages et jaquettes, la fiche de jeu, le lecteur,
   les commandes, les réglages et les menus doivent être conservés.
5. **Pilotes** : rendu et planification audio/vidéo/entrées du côté natif C
   autant que possible, avec adaptateurs de plateforme limités aux services
   nécessaires (fenêtre, audio, manette, permissions, cycle de vie).
6. **iOS** : la bêta iOS a été retirée sur décision du propriétaire. Un
   futur **hôte iOS natif** se brancherait sur la même API C du frontend, en
   conservant l'expérience RavenEmu. Ne pas annoncer de version iOS avant
   qu'elle existe et soit publiée.
7. **Continuité** : aucune régression silencieuse sur les données, les
   formats de sauvegardes, les jeux, les options ou l'identité applicative.
   Une nouvelle version ne remplace ni n'efface jamais automatiquement les
   données d'une installation existante.
8. **Provenance** : utiliser l'ABI Libretro sans affirmer utiliser le
   frontend officiel de RetroArch. Tout emprunt de source, pilote, shader ou
   ressource tierce doit respecter sa licence et garder les attributions.

## 2. Dépendances et séparation des couches

```text
                 Android : interface Java                 iOS : hôte natif
                 bibliothèque, menus,                     (futur, non commencé)
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
| `android/src/main/java/` | UI Android **Java**, bibliothèque, gestion fichiers, permissions, états de navigation et cycle de vie |
| `android/src/main/cpp/` | Pont **JNI**, rendu natif, audio AAudio, commandes et intégration des cores |
| `android/src/main/res/` | Thème, icônes et ressources fidèles à RavenEmu ; adaptation aux densités et insets système |
| `RavenCore` (dépôt indépendant) | Cores **C++20** GB/GBC/GBA, adaptés à l'ABI Libretro, révisions immuables et licences suivies |
| `policy/` et `.github/workflows/` | Tests des frontières, de l'architecture, de la compilation et de la compatibilité |
| `docs/MIGRATION_NATIVE.md` | Historique de la migration et écarts de parité restants, **pas** une autre architecture |
| Bêta retirée (`androidApp/`, `shared/`, `iosApp/`, `native/`) | Supprimée de `main` ; consultable au commit `de1c9cc` comme référence de design et de compatibilité, jamais comme cible |

Les noms de répertoires `android/` et `frontend/` sont définitifs ; un
renommage ou une réorganisation demande l'accord du propriétaire. La
cible est définie par la séparation des responsabilités.

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

- **Source visuelle de référence**, dans l'historique git au commit
  `de1c9cc` (`git show de1c9cc:<chemin>`) :
  `shared/src/commonMain/kotlin/com/ravenemu/shared/RavenEmuApp.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryComponents.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryDialogs.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/player/GbVideoScreen.kt`
  et `shared/src/commonMain/kotlin/com/ravenemu/shared/theme/RavenColors.kt`.
  Les lire pour **reproduire la présentation en Java**, pas pour importer
  du code Compose/Kotlin dans l'application.
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

- `com.ravenemu.dev` est **isolé** de la bêta `com.ravenemu.preview`,
  qui peut rester installée. Ne jamais accéder à ses données privées ni
  changer implicitement un application ID.
- Index et ROMs privés par jeu : nom de fichier strictement validé,
  identité ROM + plateforme (SHA-256 ou format compatible documenté),
  plafonds de taille, détection des corruptions, écriture atomique.
  Utiliser le sélecteur de documents Android plutôt que l'accès large
  au stockage.
- La SRAM est sauvée aux transitions critiques et selon une politique
  de sauvegarde sûre. Les `save states` indiquent leur version et leur
  compatibilité avec le core ; tout échec conserve les données d'origine.
- Les anciennes sauvegardes, préférences et bibliothèques de la bêta
  ne s'importent qu'**explicitement**, avec contrôles
  d'intégrité, possibilité de retour arrière, tests de round-trip et
  accord du propriétaire. Ne jamais effacer les anciens fichiers au
  premier lancement.
- Ni jeux commerciaux, ni BIOS, ni ressources graphiques protégées
  ne sont incorporés par défaut. Respect des consentements et des
  licences, notamment pour les services et jaquettes distants.

## 7. Parité avec la bêta retirée

L'application doit préserver ou rétablir **réellement**, et non seulement
montrer dans un menu, les capacités qu'offrait la bêta :

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

iOS a été retiré sur décision du propriétaire (6 novembre 2026) : ce
n'est pas un écart de parité, mais une plateforme à reconstruire.

Toute différence doit figurer dans une **liste d'écarts documentée**, avec
un choix explicite du propriétaire si une fonction est abandonnée.
Une interface qui affiche une option sans fonctionnalité réelle n'est
**pas** considérée comme paritaire.

## 8. Critères d'une publication et évolution future

La fusion dans `main` et le retrait de la bêta ont été décidés par le
propriétaire le 6 novembre 2026. Une **version publique** de
l'application n'est publiée **que si** :

1. Le propriétaire accepte l'interface sur captures et **appareil réel** ;
   barres Android, clavier, densités, orientations et navigation ont été
   vérifiés. L'apparence et les actions de référence sont conservées.
2. Les tests C/Libretro, RavenCore C++20, JNI/Java, APK ARM64 et règles
   du dépôt réussissent ; tests de ROMs **libres/homebrew** et tests
   manuels de sessions longues (audio, vidéo, entrée, veille, reprise,
   sauvegarde/restauration) sont documentés.
3. La compatibilité des données et la stratégie de retour arrière ont été
   exercées sur une copie, sans perte de ROMs, SRAM, états ou paramètres.
4. Les capacités listées en section 7 sont fonctionnelles ou font l'objet
   d'une exception **approuvée et tracée**.
5. Les licences tierces, vérifications de sécurité et modalités de
   distribution sont vérifiées ; les identifiants applicatifs restent
   conformes au plan de publication approuvé.
6. **Aucune release, aucun tag de version ni changement du README sans
   décision explicite du propriétaire.** Une CI verte n'est pas une
   autorisation de publication.

Les agents **doivent continuer à respecter ces
mêmes frontières**, maintenir les tests et ne pas réintroduire du Kotlin
applicatif, une API moteur parallèle, une interface différente ou une
régression de sauvegardes sans décision explicite du propriétaire.
Si un changement d'architecture est nécessaire, le proposer, documenter
son impact, obtenir l'accord, puis modifier `ARCHITECTURE.md` et les tests
correspondants **dans le même changement**.

---

# Références techniques

## Dépendances épinglées

[frontend/CMakeLists.txt](frontend/CMakeLists.txt) épingle RavenCore par le
SHA complet d'un commit (`RAVEN_CORE_REVISION`), jamais par une branche ou
une étiquette. Publier une version de RavenCore ne change pas cette révision :
la mettre à jour est un changement de RavenEmu, avec ses tests et la mise à
jour des licences embarquées (`android/src/main/assets/licenses/`).
L'application ne passe que par les cores Libretro de RavenCore ; l'ancienne
API C `ravenemu_*` de la bêta n'est pas utilisée. RetroAchievements utilise
le SDK officiel rcheevos, épinglé par SHA dans
[frontend/cmake/rcheevos.cmake](frontend/cmake/rcheevos.cmake).

## Câble link sans fil : protocole à reprendre

L'application native n'a pas encore de câble sans fil : c'est un écart de
parité. La bêta Android et la version PC (RavenEmuDSK) parlent le protocole
ci-dessous ; une implémentation native doit le reprendre pour rester
compatible. Les noms de classes sont ceux de la bêta (commit `de1c9cc`).

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
- Les archives de sauvegarde ne contiennent aucune ROM.
- Le paquet Java est `com.ravenemu.app`, l'identifiant Android `com.ravenemu.dev`. Les classes à méthodes `native` donnent leur paquet aux symboles JNI de `android/src/main/cpp/` (`Java_com_ravenemu_app_<paquet>_<Classe>_<méthode>`) ; `AndroidPolicyTest` vérifie que chaque méthode a son symbole, et inversement.
