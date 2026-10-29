# Intention de la migration native RavenEmu

> Instructions de référence pour les agents et contributeurs travaillant sur
> `migration/native-libretro`. **Lire d'abord
> [ARCHITECTURE.md](ARCHITECTURE.md), contrat final et source de vérité
> obligatoire**, puis [docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md)
> et [androidNative/README.md](androidNative/README.md).
>
> **Ne pas confondre migration technique et refonte de RavenEmu.** Le résultat
> recherché conserve **l'identité, l'interface, les jeux et les données de RavenEmu**
> tout en remplaçant progressivement sa couche d'exécution par une architecture
> native de type frontend Libretro.

## Règle obligatoire : architecture finale

**Tous les agents, y compris après la fin de la migration, doivent respecter
[ARCHITECTURE.md](ARCHITECTURE.md).** Ce document définit la cible définitive,
les frontières des modules, le cycle de vie des sessions Libretro, les
responsabilités Java / C / C++20, la conformité visuelle, les formats de
sauvegarde et les critères de bascule. Il s'applique aussi aux nouvelles
fonctionnalités et correctifs **après publication**, pas uniquement à la PR #39.

Ordre de référence, en cas de contradiction :

1. **Demandes explicites du propriétaire et protection des données** ;
2. **`ARCHITECTURE.md`** : règles et architecture finale ;
3. **`AGENTS.md`** : consignes de travail et contraintes des agents ;
4. **`docs/MIGRATION_NATIVE.md`** : étapes, statut et écarts de migration ;
5. **Code hérité** : utile comme référence de compatibilité et de design,
   sans imposer Compose/Kotlin à la nouvelle application.

Ne jamais faire évoluer le code **contre** `ARCHITECTURE.md` pour gagner du
temps. Si une exception ou un changement de structure paraît nécessaire,
exposer le besoin au propriétaire, attendre son accord, et mettre à jour
`ARCHITECTURE.md`, la documentation et les tests dans le même changement.

**Ne pas présenter la section historique** d'`ARCHITECTURE.md` sur
`shared/`, `androidApp/` et l'ancienne API RavenCore comme la cible finale :
les sections **1 à 8** en tête du document sont le contrat normatif.

## Objectif produit

- Faire évoluer RavenEmu vers une architecture inspirée de **RetroArch Plus** :
  frontend natif, cores séparés par l'ABI publique **Libretro**, pilotes natifs
  pour l'image, le son et les entrées.
- **Ne pas prétendre réutiliser les sources de RetroArch** : l'architecture et
  l'ABI Libretro sont communes, mais le frontend C de cette branche est propre
  à RavenEmu. Toute réutilisation future de code tiers exige une vérification
  de licence, les avis de copyright et les attributions appropriées.
- **Garder RavenCore** comme moteur d'émulation en **C++20**, en commençant par
  Game Boy, Game Boy Color et Game Boy Advance. Ne pas remplacer RavenCore par
  un autre émulateur par commodité.
- Préserver les fonctions attendues de RavenEmu : bibliothèque et jaquettes,
  sauvegardes, paramètres, affichage du jeu, menus, contrôles, skins, câble
  link, RetroAchievements et expérience iOS. Les fonctionnalités manquantes
  sont des travaux de parité, pas une permission de les supprimer.

## Contrat de langages et de responsabilités

| Couche | Technologie voulue | Responsabilité |
| --- | --- | --- |
| Frontend émulation | **C** | Hôte Libretro, sessions, callbacks et orchestration native |
| Cores RavenCore | **C++20** | Exécution GB/GBC/GBA via l'ABI Libretro |
| Android | **Java + JNI + NDK** | Interface, stockage, activités, cycle de vie et accès plateforme |
| Vidéo / audio / commandes | **C natif** et pilotes Android | GLES, AAudio, contrôleurs et autres pilotes natifs |
| iOS | À conserver et adapter par étapes | Ne pas casser le projet existant ni annoncer une parité fictive |

**Aucun code applicatif Kotlin ni Compose dans `androidNative/`.**
Ne pas introduire de fichiers `.kt`, de plugin Compose ni de dépendance
`:shared` pour reproduire l'interface. Les fichiers `.gradle.kts` sont des
**scripts de configuration Gradle**, et non une autorisation à écrire
l'application en Kotlin. Les sources Kotlin de l'application **historique**
restent inchangées et peuvent être **consultées comme références visuelles**.

## Fidélité à l'interface RavenEmu

- La migration **n'est pas une refonte graphique**. Reproduire en Java
  l'interface déjà validée par le propriétaire : palette sombre/violette,
  barre « Tous les jeux », recherche, boutons paramètres/ajout/options,
  grille/liste de jaquettes, navigation GB/GBC/GBA, fiche de jeu et bouton
  « Jouer », lecteur, menu de pause, réglages et commandes.
- Références visuelles : `shared/src/commonMain/kotlin/com/ravenemu/shared/RavenEmuApp.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryComponents.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/library/LibraryDialogs.kt`,
  `shared/src/commonMain/kotlin/com/ravenemu/shared/player/GbVideoScreen.kt`
  et `shared/src/commonMain/kotlin/com/ravenemu/shared/theme/RavenColors.kt`.
  **Les lire pour reproduire le design, pas pour porter Compose dans la migration.**
- Couleurs de référence : fond `#0B0C12`, surface `#191B28`,
  accent `#A78BFA`, texte `#F5F2FF`, texte atténué `#AAA2BC`.
- **Respecter les barres du téléphone** : heure, icônes d'état, encoches,
  boutons de navigation Android ou gestes, clavier et changement d'orientation.
  Utiliser les insets Android réels plutôt que des offsets fixes ; garder
  le titre en dessous de la barre d'état et le pied de page/les contrôles
  au-dessus de la navigation du système.
- Une ressemblance approximative ne suffit pas : signaler clairement
  les écarts de parité restants au lieu de déclarer l'interface identique.

## Sécurité des données et de la version bêta

- Travailler uniquement dans la branche **`migration/native-libretro`**,
  via la PR brouillon **#39**. Ne pas fusionner, modifier `main` ou lancer
  une publication sans demande explicite.
- Garder la bêta (`androidApp/`, `shared/`, `iosApp/`, `native/`)
  opérationnelle. Le module de prévisualisation est **`androidNative/`**
  et utilise l'identifiant indépendant **`com.ravenemu.nativepreview`**.
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
   les tests Java/JVM et les règles de dépôt. Préserver la CI bêta Android/iOS.
4. Vérifier sur **appareil physique** l'interface, la navigation Android
   (trois boutons **et** gestes), portrait/paysage, clavier/encoches, son,
   vidéo, commandes, chargement et sauvegardes avant toute bascule produit.
   **La CI verte ne prouve pas à elle seule qu'une application est jouable.**
5. Corriger d'abord les régressions et les retours de captures d'écran,
   puis ajouter de nouvelles fonctions. Ne pas élargir le périmètre si
   la parité avec RavenEmu est encore incomplète.

## État et périmètre actuel

Le frontend C est dans `frontend/`. L'application native expérimentale
est dans `androidNative/` : écrans Java, JNI, rendu GLES2, AAudio,
bibliothèque GB/GBC/GBA, sauvegardes et jaquettes locales. Ce sont des
**jalons de migration**, pas une substitution de la bêta. Les fonctions
avancées, les réglages complets, le rendu parfaitement identique et la
validation sur téléphones sont encore à terminer.

**Priorité des décisions : intention du propriétaire > fidélité à la bêta
> intégrité des données > architecture Java/C/C++20 > nouveautés.**
