# Migration native de RavenEmu

Branche de travail : `migration/native-libretro`, dérivée de `main`.
Objectif : frontend C natif + cores C++20 par l'ABI Libretro, couche Android
Java/JNI et pilotes vidéo/audio/commandes natifs, selon l'architecture de
RetroArch Plus, sans remplacer les moteurs maison RavenCore.

## Jalons et critères d'acceptation

1. **Chargeur natif autonome** : ouvrir un fichier core, vérifier sa version
   d'API, brancher vidéo/audio/entrée, charger un contenu puis exécuter des
   frames. Test automatique avec un core fictif, sans ROM commerciale.
2. **RavenCore Libretro** : bâtir les trois `.so` depuis un SHA immuable,
   vérifier leur chargement et leurs métadonnées, puis lancer des tests sur
   des ROMs de test libres.
3. **Couche Android Java/JNI** : charger les bibliothèques, connecter le cycle
   de vie et une session (un seul fil d'émulation par core), respecter le
   stockage privé et les autorisations Android.
4. **Pilotes C** : rendu OpenGL ES, audio natif, contrôleurs/tactile,
   menus natifs et gestion des shaders.
5. **Parité** : bibliothèque, imports, stockage, sauvegardes, skins, options,
   RetroAchievements, câble link local et distant.
6. **Validation** : tests des saves, ANR, performance GB/GBC/GBA, ARM64,
   CI, signature et mises à jour sur appareil. Ensuite seulement bascule.

## Contrats non négociables

- Ne jamais réécrire ou effacer les données existantes en phase de migration.
  Préserver `com.ravenemu.preview` et les clés/chemins de sauvegarde jusqu'à
  une migration de données testée.
- Préserver le projet actuel (`androidApp/`, `shared/`, `iosApp/`,
  `native/`) et la publication de `main` pendant la transition.
- Conserver RavenCore en C++20, avec sa provenance et ses licences.
- Éviter de mélanger l'API C RavenCore historique et Libretro dans une même
  instance de jeu : une seule interface fait autorité par session.
- Protéger l'accès à chaque instance Libretro sur le fil d'émulation ;
  aucun appel concurrent à `retro_run` et `retro_unload_game`.
- Conserver les notices légales de tout composant RetroArch réutilisé.
- Ne pas prétendre à une version Android jouable avant tests sur appareil.

## Première tranche

`frontend/` constitue la première implantation autonome du chargeur.
La CI de cette branche teste cette brique sans modifier la CI de production.
Le module distinct `androidNative/` raccorde Java/JNI, les cores Libretro ARM64\net un premier rendu natif OpenGL ES 2, sans modifier `androidApp/`.\nLa sortie audio, les sauvegardes persistantes et les services de la version\nbêta ne sont pas encore raccordés. Voir [androidNative/README.md](../androidNative/README.md).

## Deuxième tranche : prévisualisation Android

- `:androidNative:assembleDebug` compile un APK au nom de package distinct
  `com.ravenemu.nativepreview`, ARM64 uniquement ; la CI doit vérifier la présence
  des quatre bibliothèques natives.
- Java n'assure que le cycle de vie Android, le sélecteur de documents et les
  boutons. Les callbacks Libretro et la conversion/présentation des frames GLES2
  sont dans le code C. Les moteurs restent les trois DLL Libretro de RavenCore.
- L'import est limité aux fichiers demandés par l'utilisateur, avec plafond de
  64 MiB. Aucune migration ni sauvegarde du catalogue existant n'est effectuée.
- Validation matérielle encore requise : démarrage d'une ROM de test libre,
  couleurs et orientation, pause/reprise, contrôleurs et performance.
