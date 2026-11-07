# Consignes de RavenEmu : application native

> Instructions de référence pour les agents et contributeurs travaillant sur
> RavenEmu. **Lire d'abord [ARCHITECTURE.md](ARCHITECTURE.md), contrat
> et source de vérité obligatoire**, puis [docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md)
> et [android/README.md](android/README.md).
>
> Le 6 novembre 2026, le propriétaire a fait fusionner la migration native dans
> `main` et retirer la bêta (`androidApp/`, `shared/`, `iosApp/`, `native/`),
> iOS compris. Son code reste consultable dans l'historique git, au commit
> `de1c9cc`. **Ne pas confondre l'architecture native et une refonte de
> RavenEmu** : l'application conserve **l'identité, l'interface, les jeux et
> les données de RavenEmu**.

## Protection du README officiel et du logo — règle permanente

**Interdiction pour les agents (y compris CorvusSoftware, Codex et les agents CI) de modifier, régénérer, reformater, déplacer ou remplacer ces fichiers sans demande explicite du propriétaire ciblant précisément cette action :**

- `README.md` : présentation officielle publiée du dépôt, promue le 6 novembre 2026 depuis la maquette `README_RELEASE.md` (supprimée le 7 novembre 2026, comme les mentions d'iOS, sur demande du propriétaire) ; **intouchable** sans nouvelle demande, et **jamais actualisé automatiquement**, même lors des changements de version, badge, workflow ou fonctionnalités.
- `assets/brand/ravenemu-logo.webp` : visuel officiel de référence ; ne pas recadrer, retoucher, convertir ou substituer sans validation explicite.

**Une CI verte ou une fusion ne constitue PAS une autorisation de publication.** Les agents ne doivent jamais réécrire `README.md`, activer des affirmations de parité, créer une release ou modifier le logo de leur propre initiative. La décision finale et toute mise à jour du README appartiennent exclusivement à GhostPunishR.

Lorsqu'une tâche nécessite un changement de documentation, modifier les documents techniques concernés (`docs/`, `CHANGELOG.md`, `BUILDING.md`, etc.) **sans toucher au README protégé** ; signaler au propriétaire les ajustements de README éventuellement nécessaires. Le fichier `.github/CODEOWNERS` réclame une revue du propriétaire ; son effet bloquant dépend de l'activation des règles de protection de branche GitHub.

## Règle obligatoire : architecture finale

**Tous les agents doivent respecter [ARCHITECTURE.md](ARCHITECTURE.md).** Ce document définit la cible définitive,
les frontières des modules, le cycle de vie des sessions Libretro, les
responsabilités Java / C / C++20, la conformité visuelle, les formats de
sauvegarde et les critères de publication. Il s'applique à toute nouvelle
fonctionnalité et à tout correctif.

Ordre de référence, en cas de contradiction :

1. **Demandes explicites du propriétaire et protection des données** ;
2. **`ARCHITECTURE.md`** : règles et architecture finale ;
3. **`AGENTS.md`** : consignes de travail et contraintes des agents ;
4. **`docs/MIGRATION_NATIVE.md`** : historique de la migration et écarts de parité ;
5. **Code de la bêta retirée** (historique git, commit `de1c9cc`) : utile comme
   référence de compatibilité et de design, sans imposer Compose/Kotlin.

Ne jamais faire évoluer le code **contre** `ARCHITECTURE.md` pour gagner du
temps. Si une exception ou un changement de structure paraît nécessaire,
exposer le besoin au propriétaire, attendre son accord, et mettre à jour
`ARCHITECTURE.md`, la documentation et les tests dans le même changement.

Les sections **1 à 8** d'`ARCHITECTURE.md` sont le contrat normatif ; la
partie « Références techniques » le complète (épinglages, protocole du câble
sans fil à reprendre, contrats de données).

## Objectif produit

- RavenEmu suit une architecture inspirée de **RetroArch Plus** :
  frontend natif, cores séparés par l'ABI publique **Libretro**, pilotes natifs
  pour l'image, le son et les entrées.
- Le frontend C reste propre à RavenEmu. Le portage ciblé autorisé des pilotes
  AAudio et GLES2 reprend désormais des portions de RetroArch : voir
  [leur provenance et leurs licences](legal/attributions/retroarch.md).
  Ne pas présenter ces portions comme le frontend complet de RetroArch.
  Tout nouvel emprunt exige une vérification de licence, la conservation
  des copyrights et des attributions, ainsi que les sources correspondantes.
- **Garder RavenCore** comme moteur d'émulation en **C++20**, en commençant par
  Game Boy, Game Boy Color et Game Boy Advance. Ne pas remplacer RavenCore par
  un autre émulateur par commodité.
- Préserver les fonctions attendues de RavenEmu : bibliothèque et jaquettes,
  sauvegardes, paramètres, affichage du jeu, menus, contrôles, skins, câble
  link et RetroAchievements. Les fonctionnalités manquantes (par exemple le
  câble sans fil) sont des travaux de parité, pas une permission de les
  supprimer. iOS a été retiré sur décision du propriétaire.

## Contrat de langages et de responsabilités

| Couche | Technologie voulue | Responsabilité |
| --- | --- | --- |
| Frontend émulation | **C** | Hôte Libretro, sessions, callbacks et orchestration native |
| Cores RavenCore | **C++20** | Exécution GB/GBC/GBA via l'ABI Libretro |
| Android | **Java + JNI + NDK** | Interface, stockage, activités, cycle de vie et accès plateforme |
| Vidéo / audio / commandes | **C natif** et pilotes Android | GLES, AAudio, contrôleurs et autres pilotes natifs |
| iOS | Retiré le 6 novembre 2026 | Ne pas annoncer de version iOS ; un futur hôte se brancherait sur l'API C du frontend |

**Aucun code applicatif Kotlin ni Compose dans `android/`.**
Ne pas introduire de fichiers `.kt`, de plugin Compose ni de module Kotlin
partagé pour reproduire l'interface. Les fichiers `.gradle.kts` sont des
**scripts de configuration Gradle**, et non une autorisation à écrire
l'application en Kotlin. Les tests JVM de `policy/` sont la seule exception
Kotlin, hors application. Les sources Kotlin de la **bêta retirée** peuvent
être **consultées comme références visuelles** dans l'historique git.

## Fidélité à l'interface RavenEmu

- L'application native **n'est pas une refonte graphique**. Reproduire en Java
  l'interface déjà validée par le propriétaire : palette sombre/violette,
  barre « Tous les jeux », recherche, boutons paramètres/ajout/options,
  grille/liste de jaquettes, navigation GB/GBC/GBA, fiche de jeu et bouton
  « Jouer », lecteur, menu de pause, réglages et commandes.
- Références visuelles, à lire avec `git show de1c9cc:<chemin>` :
  `shared/src/commonMain/kotlin/com/ravenemu/shared/RavenEmuApp.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryComponents.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryDialogs.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/player/GbVideoScreen.kt`
  et `shared/src/commonMain/kotlin/com/ravenemu/shared/theme/RavenColors.kt`.
  **Les lire pour reproduire le design, pas pour porter Compose dans l'application.**
- Couleurs de référence : fond `#0B0C12`, surface `#191B28`,
  accent `#A78BFA`, texte `#F5F2FF`, texte atténué `#AAA2BC`.
- **Respecter les barres du téléphone** : heure, icônes d'état, encoches,
  boutons de navigation Android ou gestes, clavier et changement d'orientation.
  Utiliser les insets Android réels plutôt que des offsets fixes ; garder
  le titre en dessous de la barre d'état et le pied de page/les contrôles
  au-dessus de la navigation du système.
- Une ressemblance approximative ne suffit pas : signaler clairement
  les écarts de parité restants au lieu de déclarer l'interface identique.

## Sécurité des données et des branches

- Développer sur une branche dédiée et passer par une **pull request vers
  `main`**. Ne pas pousser directement sur `main`, fusionner ou lancer une
  publication sans demande explicite du propriétaire.
- L'application est le module **`android/`**, à l'identifiant
  **`com.ravenemu.dev`**, indépendant de la bêta `com.ravenemu.preview`
  qui peut rester installée sur les téléphones.
- Ne pas lire, migrer, effacer ou écraser automatiquement les ROMs,
  sauvegardes, paramètres ou autres données de la bêta. Toute migration
  de données doit être explicite, réversible et testée.
- Les ROMs et jaquettes sont choisies par l'utilisateur. Vérifier les tailles,
  intégrités et chemins, utiliser le stockage privé et les écritures atomiques.
  Ne jamais intégrer de ROM commerciale, BIOS ou jaquette protégée.

## Qualité technique et validation obligatoire

1. Conserver l'ABI Libretro correcte et isoler les accès aux cores sur
   le fil d'émulation : éviter les appels concurrents à `retro_run`,
   `retro_unload_game` ou à la même session.
2. Pour chaque tranche, fournir **du code effectif**, des tests ciblés,
   une documentation cohérente et vérifier les workflows GitHub Actions.
   Ne pas annoncer un résultat CI avant qu'il soit réellement terminé.
3. Vérifier en CI le frontend C, les cores RavenCore, l'APK Android ARM64,
   les tests Java/JVM et les règles de dépôt (workflow
   `.github/workflows/ci-applications.yml`).
4. Vérifier sur **appareil physique** l'interface, la navigation Android
   (trois boutons **et** gestes), portrait/paysage, clavier/encoches, son,
   vidéo, commandes, chargement et sauvegardes avant toute publication.
   **La CI verte ne prouve pas à elle seule qu'une application est jouable.**
5. Corriger d'abord les régressions et les retours de captures d'écran,
   puis ajouter de nouvelles fonctions. Ne pas élargir le périmètre si
   la parité avec RavenEmu est encore incomplète.

## État et périmètre actuel

Le frontend C est dans `frontend/`. L'application native est dans
`android/` : écrans Java, JNI, rendu GLES2, AAudio, bibliothèque
GB/GBC/GBA, sauvegardes, jaquettes locales, skins Delta, câble link local,
codes de triche et RetroAchievements. Le câble sans fil, l'import des états
de la bêta et la validation complète sur téléphones restent à faire ; la
liste des écarts est tenue dans `docs/MIGRATION_NATIVE.md`.

**Priorité des décisions : intention du propriétaire > fidélité à la bêta
> intégrité des données > architecture Java/C/C++20 > nouveautés.**
