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

## Troisième tranche : audio et sauvegardes natives

- Pilote AAudio en C (PCM16 stéréo), tampon circulaire non bloquant ;
  rééchantillonnage linéaire du taux Libretro vers le taux de sortie AAudio.
- Extension de l'interface C Libretro : SRAM (`retro_get_memory_data`),
  sérialisation et désérialisation, tests du chemin aller-retour sur un faux core.
- Écriture atomique de la SRAM dans l'espace privé du module de test, identité
  SHA-256 fondée sur la ROM et le système ; autosauvegarde environ toutes les
  30 secondes, aux pauses et aux changements de ROM.
- Boutons d'état instantané manuel ; aucune migration automatique des anciennes
  données de RavenEmu, aucun jeu ni BIOS livré.
- À valider : tests instrumentés AAudio, latence, état sur reprise et crash,
  compatibilité des états GB/GBC/GBA, contrôle mémoire et tests sur appareil.

## Quatrième tranche : bibliothèque privée et menu C

- `androidNative/RomLibrary.java` copie les ROMs choisies dans un catalogue
  privé (`native-library/`) indexé par SHA-256 et fichier JSON atomique.
  Les copies sont revérifiées avant exécution, et l'import d'une ROM déjà
  présente ne crée pas de doublon. Limites initiales : 256 jeux, 64 MiB par ROM.
- Le menu en pause est dessiné directement par `native_menu.c` avec OpenGL ES 2,
  texte bitmap C et navigation haut/bas/A ; le sélecteur système Android et la
  liste accessible des jeux restent des services Java de la plateforme.
- Une règle de tests `policy` accepte explicitement `frontend/` en plus
  de `native/` dans les workflows CMake ; les restrictions de chemins et le
  contrôle des actions GitHub demeurent actifs.
- L'application de test conserve `com.ravenemu.nativepreview` et ne modifie ni
  la bibliothèque ni les sauvegardes de l'application principale.
- Restent à tester sur téléphones : commandes tactiles/manette du menu,
  rendu des lettres sur différentes résolutions, import/reprise après fermeture,
  mémoire, perf, audio et données.

## Cinquième tranche : recherche et métadonnées hors ligne

- Recherche sans accent sur les titres du catalogue et les titres de cartouche,
  filtre par console et tri A-Z / Z-A / console, accessibles depuis l'interface
  native Android du module de test.
- Lecture sûre des en-têtes GB/GBC (titre 0x134..0x143, drapeau CGB) et GBA
  (titre 0xA0..0xAB), sans exécuter de données ni interroger de serveur.
- Index de bibliothèque étendu avec `cartridgeTitle` facultatif ; les index
  JSON créés par la quatrième tranche restent lisibles sans migration.
- Tests JVM des en-têtes tronqués/non ASCII, de la recherche, des filtres et
  du tri ; la compilation ARM64 et la politique du dépôt restent vérifiées.
- Jaquettes et recherches de métadonnées en ligne restent hors périmètre ;
  les téléphones physiques doivent encore valider navigation et performance.
