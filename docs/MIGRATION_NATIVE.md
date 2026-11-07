# Migration native de RavenEmu

> **Bascule du 6 novembre 2026.** Sur décision du propriétaire, la migration est
> fusionnée dans `main` et la bêta (`androidApp/`, `shared/`, `iosApp/`,
> `native/`) est retirée, iOS compris. Le reste de ce document retrace la
> migration tranche par tranche ; ses mentions de la bêta décrivent l'état
> d'alors. Voir [Bascule et écarts restants](#bascule-et-écarts-restants).
>
> Réorganisation du 29 octobre 2026 : `android/` (application, JNI et pilotes matériels), `frontend/` (C et Libretro), `tests/hardware/` (tests matériels), `legal/attributions/` (provenance). Le nouvel APK de développement `com.ravenemu.dev` est isolé des données de la bêta et de l'ancienne prévisualisation, sans migration automatique.

Branche de travail pendant la migration : `migration/native-libretro`, fusionnée dans `main` le 6 novembre 2026.
Objectif : frontend C natif + cores C++20 par l'ABI Libretro, couche Android
Java/JNI et pilotes vidéo/audio/commandes natifs, selon l'architecture de
RetroArch Plus, sans remplacer les moteurs maison RavenCore.

## Bascule et écarts restants

Le 6 novembre 2026, le propriétaire a demandé de fusionner la migration jusqu'à
`main`, de supprimer la bêta, iOS compris, et d'aligner le site, la
documentation et le README sur l'application native.

- **Retirés** : `androidApp/` et `shared/` (bêta Android Kotlin/Compose),
  `iosApp/` (application iOS), `native/` (ponts JNI et Swift de la bêta),
  le workflow `ci-android.yml`. Leur code reste dans l'historique git, au
  commit `de1c9cc`, dernier état de `main` avec la bêta.
- **CI** : un seul workflow, `ci-applications.yml` (« RavenEmu / Applications ») :
  règles du dépôt (`policy/`), frontend C et pilotes, APK ARM64 et tests Java.
  Il ne publie plus de préversion `android-beta-N`.
- **Règles du dépôt** : `AndroidPolicyTest` vérifie maintenant les méthodes
  `native` de l'application Java contre les symboles JNI de
  `android/src/main/cpp/`, et la permission d'accès au réseau local selon le
  `targetSdk` de `android/`.
- **Données** : l'application garde l'identifiant `com.ravenemu.dev`. Une bêta
  `com.ravenemu.preview` déjà installée n'est ni lue ni modifiée.
- **RavenCore** épinglé à la version 0.15.2 (cœur GBA de 1,2 à 1,5 fois
  plus rapide et cœur GB/GBC environ 1,7 fois depuis la 0.15.1, au bit près ;
  codes de triche lus comme l'appareil et comme dans RetroArch depuis la 0.15.0).

Écarts de parité restants avec la bêta, à traiter comme du travail à faire :

- **Câble link sans fil** entre deux téléphones : absent de l'application
  native. Le protocole à reprendre est décrit dans
  [ARCHITECTURE.md](../ARCHITECTURE.md#câble-link-sans-fil--protocole-à-reprendre).
- **Import des états instantanés de la bêta** : les sauvegardes de cartouche
  s'importent, pas les états (décision du propriétaire attendue).
- **Validation sur téléphone** de l'ensemble (navigation trois boutons et
  gestes, portrait et paysage, son, manettes, sauvegardes, RetroAchievements),
  et les écarts notés dans chaque tranche ci-dessous.
- **Publication** : variante signée, identifiant publié et workflow de
  publication restent à décider ([RELEASING.md](../RELEASING.md)).
- **iOS** : retiré sur décision du propriétaire, à reconstruire sur l'API C
  du frontend si un jour il est demandé.

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
  `native/`) et la publication de `main` pendant la transition. (Levé le
  6 novembre 2026 par la décision du propriétaire.)
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
Le module distinct `android/` raccorde Java/JNI, les cores Libretro ARM64
et un premier rendu natif OpenGL ES 2, sans modifier `androidApp/`.
L'audio AAudio, les sauvegardes privées et la bibliothèque ont été ajoutés
par les tranches suivantes. Les services avancés de la bêta restent à porter.
Voir [android/README.md](../android/README.md).

## Deuxième tranche : prévisualisation Android

- `:android:assembleDebug` compile un APK au nom de package distinct
  `com.ravenemu.dev`, ARM64 uniquement ; la CI doit vérifier la présence
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

- `android/RomLibrary.java` copie les ROMs choisies dans un catalogue
  privé (`native-library/`) indexé par SHA-256 et fichier JSON atomique.
  Les copies sont revérifiées avant exécution, et l'import d'une ROM déjà
  présente ne crée pas de doublon. Limites initiales : 256 jeux, 64 MiB par ROM.
- Le menu en pause est dessiné directement par `native_menu.c` avec OpenGL ES 2,
  texte bitmap C et navigation haut/bas/A ; le sélecteur système Android et la
  liste accessible des jeux restent des services Java de la plateforme.
- Une règle de tests `policy` accepte explicitement `frontend/` en plus
  de `native/` dans les workflows CMake ; les restrictions de chemins et le
  contrôle des actions GitHub demeurent actifs.
- L'application de test conserve `com.ravenemu.dev` et ne modifie ni
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

## Sixième tranche : jaquettes locales et bibliothèque illustrée

- Sélection d'une image par l'utilisateur depuis le sélecteur SAF Android ;
  aucune source distante ni jaquette propriétaire intégrée.
- Décodage borné : fichier source inférieur ou égal à 8 MiB, dimensions
  comprises entre 16 et 4096 px et 16 Mpixels au maximum ; normalisation en
  PNG de 512 px de côté au plus (768 px depuis les retours d'essai, voir plus
  bas) avec écriture atomique, dans le dossier privé
  `native-library/covers/` identifié uniquement par SHA-256 de la ROM.
- Liste Android désormais illustrée par miniatures décodées hors UI et mises
  en cache avec une limite de 32 images. Les jeux sans jaquette restent
  accessibles avec leur titre et leur console.
- Appui long sur un jeu pour importer/remplacer ou supprimer la jaquette ;
  cette opération ne modifie ni les ROMs, ni les fichiers de sauvegarde.
- Tests JVM des identités de fichiers, chemins et facteurs d'échantillonnage.
  Le décodage image, les permissions et la reprise d'Activity demandent encore
  des tests instrumentés puis sur appareil physique.

## Septième tranche : fidélité à l'interface RavenEmu sans Kotlin

- Exigence explicite : **aucun code applicatif Kotlin ou Compose** dans le
  module `android`. Le début d'intégration de
  `NativeHomeActivity.kt` et ses dépendances Compose a été annulé.
- Nouveau `NativeHomeActivity.java` comme lanceur : reprise des couleurs
  exactes de `RavenColors.kt` (sans dépendance Kotlin), barre supérieure
  réglages / titre / ajouter / options, recherche, grille/liste de jaquettes,
  pages GB/GBC/GBA, pied de page et fiche jeu avec bouton « Jouer ».
- `RavenLibraryAdapter.java` affiche la grille et la liste en Java, avec
  lecture asynchrone des jaquettes ; le lecteur JNI est ouvert via la clé
  SHA-256 d'une ROM importée et conserve sa couche native C / C++20.
- `MainActivity.java` reprend la barre du jeu et les couleurs d'origine.
- La parité complète reste à réaliser pour les réglages, les skins Delta,
  les options du menu de pause et les contrôles détaillés ; ne pas
  présenter ces écrans comme identiques tant que ce n'est pas validé.
- Les `.gradle.kts` existants sont uniquement les scripts de build du dépôt,
  pas une implémentation d'interface ou de logique d'émulation.

## Huitième tranche : respect des barres du téléphone

- Capture d'appareil : la barre d'état (heure, réseau, batterie) empiétait sur
  la barre supérieure « Tous les jeux » et le pied de page était masqué par
  la navigation Android à trois boutons.
- `SystemBarInsets.java` protège `NativeHomeActivity.java` et le lecteur
  `MainActivity.java` en réservant les insets `systemBars`, `displayCutout`
  et `ime` sur Android 11+, ainsi que les insets historiques sur Android 8–10.
- Le frontend C/GLES2 dessine maintenant à l'intérieur de la zone du lecteur
  en respectant les marges parent ; les commandes tactiles restent au-dessus
  de la barre de navigation du système.
- Tests JVM : barres statut/navigation, navigation paysage + encoche, clavier,
  calcul idempotent, valeurs incorrectes.
- À tester visuellement sur téléphone : navigation par gestes / 3 boutons,
  portrait/paysage, écran avec encoche, recherche avec clavier.
- Aucune source Kotlin/Compose ajoutée au module Android natif.

## Neuvième tranche : recréation de la bibliothèque et contrôles de régression

- Création du décor de fenêtre avant le contrôleur d'insets (Android 30+),
  pour ouvrir l'Activity avant `setContentView` sans accès à un décor absent.
- Recherche, console, grille/liste et cible du sélecteur de jaquette restaurées
  lors de la recréation de l'Activity ; le retour SAF garde le bon jeu.
- Un import terminé après la fermeture ne soumet plus un rechargement à
  l'exécuteur arrêté de l'ancien écran. Les données déjà importées sont conservées.
- Fiche de jeu défilable en paysage et titre de bibliothèque sur une seule ligne.
- Tests d'Activity en Java avec Robolectric, sans Kotlin applicatif, Compose
  ou dépendance `:shared`. Le workflow de migration est aussi déclenché pour
  une PR qui ne modifie que `android/` ou `settings.gradle.kts`.
- Tests visuels physiques, grandes polices, encoches et navigation par gestes
  ou trois boutons restent à effectuer. La fiche n'est pas encore une
  reproduction complète du panneau glissant de la bêta.

## Dixième tranche : fiche glissante et commandes tactiles

- Fiche Java `GameDetailsSheet` ouverte en état déplié, sans arrêt à mi-hauteur,
  avec défilement imbriqué, fermeture par glissement/Retour/extérieur/Fermer
  et restauration du jeu sélectionné après recréation. Les ouvertures rapides
  restent limitées à une fiche. Material Components Android Views fournit
  le comportement du panneau ; le thème conserve la palette RavenEmu et ses
  licences Apache 2.0 sont incluses dans l'APK de prévisualisation.
- `GameControlsView` remplace la rangée de boutons du prototype : croix
  directionnelle glissante avec diagonales, A/B décalés, SELECT/START et L/R
  pour la GBA, disposition portrait/paysage et appuis multi-doigts.
- Les masques tactiles et manette sont combinés. Annulation, perte de focus,
  pause, menu et changement de jeu libèrent les commandes ; les activations
  d'accessibilité sont bornées dans le temps.
- Le pilote C atomique `native_input` retient un appui bref jusqu'à la prochaine
  image émulée et présente un instantané cohérent aux callbacks Libretro.
  Il ne consomme pas les appuis lors d'un rendu sans émulation. Tests C inclus
  dans le workflow natif, avec les tests Java de gestes et de multi-touch.
- Aucune migration des données bêta, source Kotlin applicative ou dépendance
  Compose/`:shared`. Le menu de pause complet, les réglages, skins, link et
  RetroAchievements restent à porter. Fidélité visuelle, gestes système,
  grandes polices, orientation, son et commandes demandent encore une
  validation sur appareils physiques avant toute bascule produit.

## Onzième tranche : menu de pause et quatre emplacements d'états

- Le prototype de menu bitmap GLES2 est remplacé par le panneau glissant
  Java Views de RavenEmu : titre du jeu, quatre emplacements, date, carte et
  actions Sauver/Charger/Supprimer/Reprendre. Le même comportement de panneau
  que la fiche de bibliothèque est réutilisé, avec défilement et manette A/B.
- Écrasement et suppression sont confirmés. L'état de chargement et les
  opérations en cours empêchent les doubles activations ; les résultats et
  erreurs sont affichés dans le panneau, avec annonce d'accessibilité.
- Les raisons de pause (menu, opération, arrière-plan, focus) sont combinées.
  Le pont C bloque les frames émulées et l'audio ensemble ; fermer le panneau
  ne reprend pas une Activity en arrière-plan ni un chargement inachevé.
- Les quatre états privés utilisent des enveloppes atomiques versionnées,
  liées à la ROM et à la révision/identité du core, avec date et SHA-256.
  Une incompatibilité/corruption refuse le chargement tout en conservant le
  fichier ; les autres emplacements restent disponibles.
- Le quick-save de prévisualisation antérieur reste lisible au premier
  emplacement après confirmation de compatibilité inconnue. Son remplacement
  écrit un nouvel état ; sa suppression logique atomique empêche son retour
  sans modifier le fichier historique ni la SRAM. Aucune donnée bêta n'est lue.
- Captures/restaurations d'états sur le fil GL ; accès SRAM de cycle de vie
  après sa suspension. Lectures/écritures sur une file de stockage commune
  aux lecteurs, afin qu'une recréation lise la dernière
  SRAM après sa sauvegarde en attente. Un état courant est capturé avant
  restauration pour revenir en arrière si le core refuse le fichier.
  Une lecture de ROM terminée en arrière-plan attend la reprise avant
  d'ouvrir le core ; les callbacks d'un lecteur détruit sont abandonnés.
- Tests de stockage, confirmations, pause et intégration du lecteur ; la
  validation physique demeure nécessaire. Miniatures, annulation d'un
  chargement réussi, réglages, RA et link restent des écarts de parité.
  La rotation restaure panneau/emplacement et SRAM, sans promettre la
  continuité automatique de l'état CPU exact du jeu.

## Douzième tranche : miniatures d'états et annulation du chargement

- La carte d'emplacement Java affiche la miniature enregistrée avec son état,
  centrée, au bon ratio et sans filtrage. Une image absente/illisible conserve
  le badge console ; les changements d'emplacement et de liste invalident
  l'ancien aperçu pour ne pas montrer celui d'une autre sauvegarde.
- Capture native C bornée à 160 pixels de côté depuis le tampon vidéo RGBA,
  sur le fil GL. Encodage PNG, vérification des dimensions et décodage sur
  l'exécuteur de stockage, sans lecture de framebuffer GLES. Une restauration
  invalide la capture jusqu'au prochain callback vidéo frais du core.
- Enveloppe d'état v2 : charge utile et image sont remplacées atomiquement dans
  le même fichier. L'image optionnelle est bornée à 256 Kio, avec une empreinte
  indépendante ; une image endommagée ne rend pas l'état valide inutilisable.
  Les enveloppes v1, marqueurs et anciens quick-saves restent lisibles sans
  migration automatique. L'identité de compatibilité du core reste inchangée.
- « Annuler le chargement » restaure le point capturé avant le dernier
  chargement réussi, pour la session/ROM/core courants. Un seul point borné
  est conservé en mémoire et consommé après réussite ; un échec tente le retour
  à la partie courante. La fermeture/recréation ou le changement de ROM
  invalident ce point, sans modifier les fichiers d'emplacements.
- Pause cumulée, doubles appuis, arrière-plan, fermeture du lecteur et
  génération du panneau restent protégés pendant chargement/annulation.
  Tests C de pixels/dimensions/ratios et tests Java des formats, images,
  commandes du panneau et parcours du lecteur sont intégrés à la CI.
- Validation physique encore requise avant bascule. Réglages, RA/link,
  hôte iOS natif et continuité CPU exacte après rotation restent des écarts
  de parité documentés ; aucune donnée de la bêta n'est lue ou migrée.

## Treizième tranche : portage ciblé AAudio/GLES2 de RetroArch

À la demande explicite du propriétaire, les pilotes natifs adaptent des
portions des sources officielles RetroArch, révision immuable
`7986022baf6822677ad2cec4bc215069e89bd8ce` :

- AAudio : callback non bloquant avec silence en sous-alimentation,
  configuration PCM16 stéréo à fréquence native, buffers de deux bursts et
  fermeture bornée. File SPSC C11 propre à l'adaptateur, purge exécutée par
  son consommateur lors des changements pause/reprise. Le convertisseur
  linéaire RavenEmu est conservé.
- GLES2 : shaders opaques amont, compilation/liaison, allocation et mise à
  jour de texture adaptées. Une image de même taille utilise un sous-upload ;
  une image dupliquée réutilise la texture. Orientation, aspect, pixels CPU
  pour les miniatures et reprises après erreur/contexte sont préservés.
- Copyrights amont et avis datés conservés dans les fichiers GPLv3 ou
  ultérieure ; AGPLv3 de RavenEmu/RavenCore inchangée. Écran Java local
  **Licences et crédits**, accessible depuis À propos, avec textes complets
  et lien de source sur la révision construite. Les autres licences des
  composants inclus restent applicables.
- Archive des sources RavenEmu/RavenCore, licences, scripts et publications
  des dépendances jointe par la CI au même artifact que l'APK. Manifeste
  d'intégrité et instructions pour reconstruire hors Git. Tests du paquet
  vérifiant le pin du core, les modifications suivies, l'exclusion des
  fichiers privés non suivis et le rejet des liens/chemins sortants.

[Provenance, périmètre et distribution](../legal/attributions/retroarch.md).
RavenCore reste le moteur ; ce portage ne reprend pas le frontend complet,
les resamplers sinc ou la planification de RetroArch. L'asservissement audio,
la reconnexion audio, la mesure de latence et la validation sur matériel
réel restent nécessaires. Cette tranche reste dans la PR #39 brouillon,
sans modification des modules ou des données de la bêta.

## Quatorzième tranche : cadence native selon les timings Libretro

- Suppression du seuil Java `now + 16 666 667 ns`, qui perdait le reste de
  période et pouvait sauter des images sur les écrans 60/90/120 Hz.
  Le frontend C calcule maintenant le nombre d'images dues selon le FPS
  annoncé par RavenCore et le temps monotone, en conservant ce reste.
- Le rendu et l'émulation sont distingués : zéro image due réutilise la
  texture ; plusieurs images dues exécutent les entrées et l'audio de chacune,
  puis présentent la dernière image disponible. Maximum trois images par
  rendu ; un retard excessif est abandonné pour borner le travail.
- Le temps de pause n'est pas rattrapé. Changement de ROM, transitions de
  pause, surface recréée et tentative de restauration réinitialisent l'époque.
  Un timing non pris en charge refuse le chargement. JNI renvoie le nombre
  réellement exécuté ; l'autosauvegarde de SRAM attend une image du core.
- Tests C déterministes à plusieurs FPS, rafraîchissements de 29,97 à 144 Hz,
  jitter et durée d'une heure simulée, valeurs invalides, grands timestamps,
  recul et saut d'horloge, retards bornés et reprises. Test Java du passage
  systématique au pilote natif et de l'autosauvegarde conditionnelle.

La fréquence est lue au chargement ; les changements AV Libretro en cours
de partie restent à porter. Cette horloge est propre à RavenEmu, sous sa
licence AGPL ; elle ne reprend
pas de code de planification RetroArch. Les appels au core restent sérialisés
sur GL. Ce jalon ne fournit ni synchronisation sur l'horloge audio ni réglages
vidéo avancés, et ne remplace pas les mesures FPS/latence et les tests de
sessions sur appareil. La bêta et ses données restent inchangées.

## Quinzième tranche : réglages audio et vidéo persistants

- Pages Java Audio/Vidéo avec les groupes, libellés et couleurs de la bêta,
  barres Android protégées, défilement et restauration de la navigation.
  Accès par la bibliothèque et le menu de pause ; le retour des paramètres
  ne ferme pas ce menu et ne reprend pas la partie avant l'action dédiée.
- Préférences propres à la prévisualisation : son activé, volume 0–100 %,
  pixels entiers, luminosité −50..50 et contraste 50–150 %. Lecture cohérente,
  bornes, récupération par champ des types corrompus, enregistrement lors
  d'une interaction seulement. Le reset vidéo conserve les réglages audio.
- Application JNI sur GL au retour au premier plan, avant retrait de sa
  raison de pause, avec gardes arrière-plan/destruction. Le gain PCM est
  indépendant de la pause ; les changements purgent la file logicielle sans
  arrêter l'émulation ni écrire le curseur de lecture du callback audio.
  Le tampon matériel déjà soumis peut finir de jouer.
- Réglages vidéo dans le pilote GLES2 existant : pixels entiers avec repli
  au cadrage proportionnel si 1× ne tient pas, luminosité/contraste selon la
  formule de la bêta, par uniforms. La texture reste réutilisée ; le défaut
  conserve directement l'image d'origine. Les miniatures restent brutes.
- Tests Java de persistance, types invalides, commandes, navigation, insets
  et retour dans une partie en pause. Tests C de gain signé, mute, purges,
  reprises et concurrence ; tests simulés de viewport/uniforms et test de
  pixels par contexte GLES logiciel réel, activé en CI.

Les shaders et adaptations des pilotes conservent leurs notices GPL ;
les nouvelles pages et préférences relèvent de l'AGPL RavenEmu. Les profils
de couleurs, corrections LCD, mélange d'images et autres réglages sont encore
des écarts de parité, ainsi que les services RA/link/skins et l'hôte iOS natif.
Le chemin shader `mediump` peut présenter une petite différence d'arrondi
selon le GPU. Les validations sur téléphone restent nécessaires avant bascule.
Aucune préférence, sauvegarde ou donnée de la bêta n'est lue ou modifiée.

## Seizième tranche : options des cores et réglages d'image

- Le frontend C sert les options déclarées par les cores Libretro : options v2
  (catégories), v1 et variables historiques « Libellé; a|b|c ».
  `GET_CORE_OPTIONS_VERSION` répond 2, `GET_VARIABLE` donne la valeur choisie
  (le défaut du core sinon) et `GET_VARIABLE_UPDATE` signale une seule fois un
  changement fait pendant la partie. Une redéclaration garde les valeurs encore
  proposées ; la fermeture du core oublie ses options.
- API publique C (`raven_frontend_option_count`, `raven_frontend_get_option`,
  `raven_frontend_option_value`, `raven_frontend_set_option`) : une clé
  inconnue ou une valeur non proposée est refusée et la valeur courante reste.
  Appels sur le fil d'émulation, comme tous les appels au core. Au plus
  128 options et 128 valeurs par option, chaînes copiées par l'hôte.
- JNI : `openRom` applique les options avant `retro_load_game` ;
  `setCoreOptions` les change en cours de partie, sur le fil GL. Les clés que
  le core ouvert ne déclare pas sont ignorées et journalisées.
- Page Java **Vidéo** reprenant les groupes et textes de la bêta :
  **Profil de couleurs** des jeux Game Boy, **Correction des couleurs LCD**
  (Game Boy Color, Game Boy Advance) et **Mélange d'images** (désactivé,
  mélange de deux images, rémanence). Réglages propres au module de
  développement, appliqués au chargement puis au retour au premier plan ;
  « Rétablir l'affichage d'origine » les remet aussi à zéro. Ces traitements
  sont ceux de RavenCore (`ravenemu_gb_palette`,
  `ravenemu_gbc|gba_color_correction`, `ravenemu_*_frame_blending`), sans
  post-traitement Java ni GLES ajouté, et ne changent ni la SRAM ni le format
  des états.
- Tests C : faux core sous ses trois déclarations (valeur choisie avant le
  chargement, changement en partie, refus des clés/valeurs inconnues, oubli à
  la fermeture), et vrais cores RavenCore : options déclarées par chaque
  console, palette d'une ROM Game Boy générée par le test (ni jeu ni BIOS)
  vérifiée au pixel au chargement et en cours de partie. Sous ASan/UBSan en
  local. Tests Java : correspondance réglages → clés des cores, persistance et
  valeurs invalides, page Vidéo, options passées à l'ouverture et au retour
  des réglages.

Écarts restants : le profil « DMG vert » de la bêta, un filtre de luminance
vert plus sombre, n'a pas d'équivalent exact ; le module propose à la place
les trois palettes de RavenCore (Original, identique à celui de la bêta,
Pocket gris et Gris neutre). Les options **Console** (jeux Game Boy en
couleurs, GBC d'une GBA, BIOS GBA et son import), les options de cartouche
(type de sauvegarde, RTC, capteur solaire), les directions opposées et les
capteurs restent à exposer : elles changent le core choisi ou la
compatibilité des états, ou demandent l'import d'un fichier système. La
validation sur téléphone reste nécessaire ; aucune donnée de la bêta n'est
lue ou modifiée.

## Dix-septième tranche : page Console

- Page Java **Console** reprenant celle de la bêta (groupes, textes et
  choix) : **Jeux Game Boy en couleurs** et leurs quatorze **couleurs**
  (celles de la boot ROM, jeu par jeu, ou une combinaison de touches),
  **Game Boy Color d'une Game Boy Advance** et le **BIOS** de la GBA
  (celui de RavenCore, le vrai, le vrai sans animation), avec le choix, le
  remplacement et le retrait du fichier du BIOS.
- `ConsoleSettings` choisit le core : un jeu Game Boy en couleurs s'ouvre sur
  le core GBC de RavenCore, qui le colore lui-même
  (`ravenemu_gbc_dmg_colors`). `ravenemu_gbc_hardware` et
  `ravenemu_gba_bios` sont passés avant `retro_load_game` et attendent le
  lancement suivant ; les couleurs changent aussitôt, même en jeu. La SRAM
  reste celle du jeu, quel que soit le core.
- BIOS : choisi avec le sélecteur de documents Android, refusé s'il ne fait
  pas exactement 16 Kio, copié par écriture atomique dans le stockage privé
  (`gba_bios.bin`, le dossier système Libretro où le core le cherche).
  Aucun BIOS n'est fourni. Sans fichier, le core reçoit « BIOS de RavenCore »
  et un message invite à le choisir dans Paramètres → Console.
- Identité des états : le core reste la première garantie (RavenGB et RavenGBC
  ont des identités différentes, et le core GBA refuse un état pris avec un
  autre BIOS). L'identité gagne en plus `|gbc-on-gba`, ou `|gba-bios-` suivi
  des 8 premiers octets du SHA-256 du BIOS ; un état pris dans un autre mode
  apparaît donc incompatible au lieu d'échouer au chargement. Avec les
  réglages par défaut, l'identité ne change pas et les états existants restent
  utilisables.
- Tests C : le core GBC charge la ROM Game Boy générée par le test, avec le
  matériel GBA et les couleurs choisies avant le chargement, puis des couleurs
  changées en partie, vérifiées au pixel (aussi sous ASan/UBSan). Tests Java :
  préférences et valeurs invalides, choix du core et identités, options
  passées au core au chargement et en partie, page Console, sélecteur et
  refus d'un fichier qui n'est pas un BIOS, sur un fichier synthétique de
  16 Kio.

Écarts restants : quand les jeux Game Boy sont en couleurs, le **Profil de
couleurs** de la page Vidéo ne s'applique plus (il est propre au core Game
Boy) ; les options de cartouche (type de sauvegarde, RTC, capteur solaire),
les directions opposées et les capteurs restent à exposer. Aucune donnée de la
bêta n'est lue ; la validation sur téléphone reste nécessaire.

## Dix-huitième tranche : page Interface, commandes tactiles

- Page Java **Interface** reprenant la partie commandes de la bêta (groupes,
  textes, bornes et valeurs par défaut) : **Retour haptique** (désactivé par
  défaut), **Taille des commandes** de 80 à 115 %, **Espacement vertical** de
  20 à 80 dp, **Style des boutons** classique ou contraste élevé, et
  **Réinitialiser les commandes**.
- `ControlSettings` garde ces choix avec les autres préférences du module ;
  une valeur hors bornes est ramenée dans ses bornes, une valeur mal formée
  revient à son défaut sans toucher aux autres.
- `GameControlsView` suit la géométrie de `GbTouchControls` de la bêta : la
  croix (moitié de la largeur, 132 dp au plus) et A/B (60 dp, entre 48 et
  69 dp) suivent la taille ; SELECT, START, L et R gardent la leur ;
  l'espacement sépare les rangées en portrait. En paysage, la superposition
  compacte grandit avec la taille mais garde son espacement fixe. Le style
  contraste élevé reprend les dégradés et le contour blanc de la bêta ; le
  retour haptique (`LONG_PRESS`) se déclenche quand un appui commence, sur un
  bouton ou en entrant dans une direction de la croix. Le lecteur applique
  les réglages à chaque retour au premier plan ; un changement de taille ou
  d'espacement relâche les boutons tenus.
- Tests Java : préférences et bornes, géométrie au pixel aux tailles et
  espacements extrêmes avec et sans L/R, relâchement au changement de taille,
  retour haptique seulement s'il est choisi, page Interface et
  réinitialisation.

Écarts restants : le résumé de la catégorie dit « Commandes tactiles et
style », sans « retour arrière » (voir la tranche suivante) ; la vibration et
les capteurs de la cartouche et les skins Delta de la page Interface restent à
porter. La validation sur téléphone (sensation du retour haptique, encoches,
navigation par gestes) reste nécessaire.

## Dix-neuvième tranche : déplacement libre des boutons

Choix du propriétaire : le **retour arrière** et l'**avance rapide** de la
bêta ne sont pas portés dans l'application native. Ce sont des écarts voulus,
pas des oublis ; la page Interface ne les annonce pas.

- Le menu du jeu gagne **Déplacer les boutons** : le jeu reste en pause, une
  barre « Faites glisser les boutons · Rétablir · Terminé » remplace la ligne
  d'état, et chaque commande (croix, A, B, SELECT, START, L, R) se déplace au
  doigt, encadrée en pointillés. Un glissement n'appuie sur rien. **Terminé**,
  le retour Android ou la mise en arrière-plan gardent la disposition ;
  **Rétablir** remet les places d'origine. La bêta annonçait cette fonction
  « plus tard ».
- `ControlLayout` garde, pour le portrait et pour le paysage, le décalage en
  dp de chaque commande depuis sa place d'origine (au dixième de dp) ; une
  valeur abîmée redonne la disposition d'origine. Le décalage s'ajoute à la
  géométrie de la page Interface (taille, espacement), et la vue garde chaque
  commande entière à l'intérieur de sa zone : en portrait la zone des
  commandes sous l'image, en paysage toute la zone de jeu, image comprise, la
  vue des commandes la couvrant désormais (transparente, elle laisse passer
  les touchers hors des commandes).
- Accessibilité : en mode déplacement, chaque commande propose « Déplacer à
  gauche / à droite / vers le haut / vers le bas » (8 dp) ; les actions de
  la croix reviennent ensuite.
- Paramètres → Interface explique où déplacer les boutons, et
  **Réinitialiser les commandes** remet aussi leurs places d'origine, dans les
  deux orientations.
- Tests Java : stockage par orientation et valeurs abîmées, glissement au
  pixel et bornes de la zone, glissement sans appui puis appui à la nouvelle
  place, disposition appliquée puis rétablie, actions d'accessibilité,
  parcours complet depuis le menu du jeu (pause, Terminé, retour Android,
  rechargement au retour au premier plan), réinitialisation depuis Interface.

Écarts restants : en portrait, les commandes ne montent pas sur l'image ; la
validation sur téléphone (précision du glissement, encoches, gestes de
navigation près des bords) reste nécessaire.

## Retours de capture : paramètres et écran de jeu

Corrections demandées par le propriétaire d'après une capture sur téléphone.

- **Paramètres en double** : le bouton ⚙ de la bibliothèque ouvrait une
  liste flottante (Général, Audio, Vidéo, Console, Interface, À propos) dont
  quatre entrées n'affichaient qu'un texte d'attente, puis la vraie page avec
  ses propres catégories. Il ouvre désormais directement la vraie page, sur
  ses catégories dans l'ordre de la bêta. **Général** reprend la page de la
  bêta (pause automatique ; câble link, jaquettes en ligne et diagnostic
  attendent leur portage, comme la bêta les masque sans eux). **À propos**
  donne l'auteur, ouvre les licences et crédits dans l'application et le code
  source de RavenEmu et RavenCore. Le bouton du menu de jeu devient
  « Paramètres ».
- **Écran de jeu** aligné sur le lecteur de la bêta : fond noir, image calée en
  haut de sa zone et centrée en largeur (le reste de l'espace va vers les
  commandes), titre centré verticalement dans la barre, plus de ligne d'état
  sous la barre (elle débordait sur deux lignes). Les erreurs apparaissent en
  message bref ; les opérations d'emplacements restent annoncées dans le menu.
- Tests : pilote GLES2 (fond noir, place de l'image, aussi au pixel par EGL en
  local), bibliothèque sans liste flottante, ordre des catégories et absence
  de texte d'attente, pages Général et À propos, menu de jeu.

Écarts restants : les conditions d'utilisation et la politique de
confidentialité s'ouvrent en ligne (dépôt GitHub), pas encore dans le lecteur
intégré de l'application. RetroAchievements a sa catégorie depuis la tranche
RA-2. La validation sur téléphone reste nécessaire.

## Tranche A : image calée sur l'écran et son sans craquement

Première tranche des emprunts à RetroArch choisis par le propriétaire.

- **Calage sur l'écran** (`frontend/src/frame_pacer.c`, code RavenEmu) : quand
  l'écran bat à n fois la cadence du jeu, à 5 % près, une image est émulée
  toutes les n synchronisations ; le jeu tourne alors 0,46 % plus vite à
  60 Hz, comme dans RetroArch et la bêta. La fréquence annoncée par Android
  n'est retenue qu'après 30 intervalles mesurés en accord (nombre entier de
  synchronisations, moyenne à 2 % près) ; 8 désaccords de suite, ou une
  moyenne qui s'écarte de 3 %, rendent la main à l'horloge de la console. Un
  téléphone qui passe à 90 ou 120 Hz sans prévenir ne fait donc jamais
  accélérer le jeu. Java transmet la fréquence au démarrage et à chaque
  changement, demande un mode d'affichage que le jeu divise (60 ou 120 Hz
  plutôt que 90) et garde l'écran allumé pendant la partie.
- **Contrôle dynamique du débit** (`audio_pipeline.c`, code RavenEmu d'après
  l'algorithme publié par l'auteur de RetroArch) : la vitesse du jeu est
  appliquée au rééchantillonneur, puis chaque écriture corrige le rapport d'au
  plus ±0,5 % vers une file à moitié pleine, mesurée au milieu du lot écrit.
  Les échantillons jetés (file pleine) et les silences (file vide) qui
  craquaient disparaissent.
- **Reprise de la sortie audio** (rappel d'erreur adapté d'`aaudio.c`,
  RetroArch 715d38f) : un casque débranché ou un appareil Bluetooth perdu
  marque le stream perdu ; le fil d'émulation le rouvre en gardant la pause,
  et réessaie environ une fois par seconde tant qu'aucune sortie n'est prête.
- Tests : `raven_frame_pacer` (cadences, une heure à 60 Hz sans image répétée
  ni sautée, 120 Hz, 90 Hz, fréquences annoncées fausses, rafraîchissement
  manqué, changement de fréquence ; mutation de la garde vérifiée),
  `raven_native_audio` (correction bornée, horloge d'appareil plus rapide ou
  plus lente de 0,2 % sans silence ni saturation, vitesse d'écran, perte et
  réouverture), Java (`DisplayModesTest`, fréquence transmise, mode demandé,
  écran allumé).

Écarts restants : la sensation de fluidité, la latence audio réelle et le
comportement des écrans à fréquence variable restent à vérifier sur téléphone.

## Tranche B : manettes et messages du core

- **Manettes** (`GamepadMapper.java`, logique du `GamepadMapper` de la bêta) :
  croix en touches ou en axes (`AXIS_HAT_X/Y`, comme l'envoient beaucoup de
  manettes Bluetooth), stick gauche vers la croix au-delà de la moitié de sa
  course, le chapeau l'emportant sur le stick ; A, B, Start, Select, L1/L2 et
  R1/R2. Un bouton tenu par deux sources reste appuyé jusqu'à la dernière. La
  touche Home ouvre le menu du jeu ; pendant une pause, les événements restent
  au système. Le clic des sticks (avance rapide et retour arrière dans la bêta)
  n'est pas repris, au choix du propriétaire.
- **Messages du core** (frontend C) : `RETRO_ENVIRONMENT_SET_MESSAGE` et
  `SET_MESSAGE_EXT` (sauf journal seul ou niveau débogage), version 1
  annoncée par `GET_MESSAGE_INTERFACE_VERSION`. `raven_frontend_take_message`
  rend chaque message une fois, en UTF-8 valide sans caractère de 4 octets
  (un octet invalide, un émoji ou un caractère coupé deviennent « ? »), ce
  que `NewStringUTF` exige. Le lecteur l'affiche en message bref : capteur de
  lumière absent, code de triche refusé, BIOS manquant…
- Tests : messages du faux core (simple, étendu, journal, débogage, octets
  invalides) ; Java (`GamepadMapperTest`, touches, chapeau et stick dans le
  lecteur, touche Home, message affiché une seule fois).

## Tranche C : lissage net et grille LCD

- **Effet d'écran** (page Vidéo, défaut « Aucun » comme la bêta) : **Lissage
  net**, des pixels nets et réguliers à toute taille (une GBA agrandie 4,5×
  n'a plus de colonnes inégales), et **Grille LCD**, la trame d'un écran de
  console portable. Le pilote GLES2 les dessine dans son shader unique, d'après
  `sharp-bilinear` (Themaister) et `lcd3x` (Gigaherz), deux shaders du domaine
  public de libretro/glsl-shaders `cba01a4`
  ([crédits](../legal/attributions/libretro-shaders.md)). Le lissage net passe
  la texture au filtre bilinéaire matériel ; les autres effets gardent le
  filtre au plus proche. Le réglage survit aux changements de contexte GL et
  « Rétablir l'affichage d'origine » le remet à « Aucun ».
- Tests : faux GLES2 (filtre et uniforms par effet, effet gardé après un
  nouveau contexte), rendu réel par EGL (une seule colonne mélangée à 2,5×
  au lieu d'aucune au plus proche ; chaque colonne de la grille atténue une
  couleur différente, et les lignes alternent), Java (préférence, page
  Vidéo, passage au pilote).

## Tranche D : vibration et capteurs de la cartouche

- **Frontend C** : `raven_frontend_callbacks` gagne trois rappels facultatifs,
  `rumble`, `sensor_state` et `sensor_input`. L'hôte ne répond à
  `GET_RUMBLE_INTERFACE` et `GET_SENSOR_INTERFACE` que si la plateforme les
  fournit ; un frontend sans vibreur ni capteurs reste valide.
- **Pilote de capteurs** (`hardware/input/sensor_driver.c`, code RavenEmu,
  NDK `ASensorManager`) : accéléromètre, gyroscope et capteur de lumière, mis
  en marche seulement à la demande du core, sur le fil GL qui les lit (file
  d'événements sur son `ALooper`, vidée une fois par image émulée, sans
  regroupement). L'accélération et la rotation suivent l'écran tel qu'il est
  tourné, comme `CartridgeSensors` de la bêta (`setDisplayRotation`, demi-tour
  compris). Un capteur absent est refusé : RavenCore affiche alors son message
  (« pas de capteur de lumière, soleil à 50 % »). Le menu de pause les éteint,
  pour la batterie ; la fermeture du jeu libère la file.
- **Vibration** (`CartridgeRumble.java`, logique de la bêta) : le core signale
  le moteur allumé ou éteint ; après chaque image dessinée, un moteur allumé
  prolonge une impulsion de 40 ms (au plus une par image à 60 Hz), un moteur
  éteint laisse finir la sienne, et quitter le jeu l'arrête aussitôt. Jamais
  pendant une pause. Permission `VIBRATE`, comme la bêta.
- **Paramètres → Interface** reprend les textes et valeurs par défaut de la
  bêta (`CartridgeSettings`) : **Vibration de la cartouche** (activée),
  groupe **Capteurs de la cartouche** avec **Capteurs du téléphone** (activés),
  **Soleil automatique (Boktai)** (activé, grisé sans les capteurs) et
  **Soleil (Boktai)** par dizaines (50 %, grisé quand le capteur de lumière
  règle le soleil). `CoreOptions` les traduit en `ravenemu_*_device_sensors`
  et `ravenemu_gba_solar_light` (`sensor` ou le niveau), changés en partie
  sans rouvrir le jeu. « Réinitialiser les commandes » n'y touche pas, comme
  dans la bêta.
- Tests : faux core (interfaces demandées, capteur activé à 60 Hz, moteur
  allumé puis éteint selon l'accéléromètre, arrêt et extinction au
  déchargement) ; `raven_native_sensors` sur un faux NDK (file créée sur le
  looper du fil, période et absence de regroupement, quatre rotations,
  capteur absent ou refusé, pause, plafond de 1 kHz, fermeture et nouvelle
  file) ; Java (`CartridgeSettingsTest`, `CoreOptionsTest`,
  `CartridgeRumbleTest`, page Interface, vibration selon le réglage et la
  pause, rotation transmise).

Écarts restants : le sens des inclinaisons, la sensibilité du gyroscope, le
seuil de lumière de Boktai et le ressenti de la vibration restent à vérifier
sur téléphone, avec les jeux concernés. Les manettes ne vibrent pas encore et
leurs capteurs ne sont pas lus : seuls ceux du téléphone servent.

## Piste 7 : images préventives (latence)

- **Frontend C** (`libretro_host.c`, idée des « preemptive frames » de
  RetroArch, code RavenEmu) : `raven_frontend_set_preemptive_frames` (0 à 3)
  garde l'état du core avant chacune des n dernières images, dans un anneau
  alloué à la taille des états. Quand la croix ou les boutons du port 0
  changent, l'image revient n images en arrière et les rejoue avec les
  nouveaux boutons, sans image ni son (`GET_AUDIO_VIDEO_ENABLE` à 0, que
  RavenCore utilise pour sauter son rendu), en gardant les états de la
  nouvelle chronologie. Un appui d'une image reste un appui d'une image. Les
  états chargés, la SRAM restaurée, un nouveau jeu et la pause effacent
  l'historique ; un core aux états incomplets (`SET_SERIALIZATION_QUIRKS`)
  n'est jamais rejoué.
- **Garde de coût** (`raven_replay_guard_overloaded`, `frame_pacer.c`) : une
  image rejouée qui dépasse les trois quarts de la période du jeu est un
  dépassement ; trois sur huit rejeux, et le pont JNI retire une image
  préventive pour ce jeu, avec un message bref.
- **Paramètres → Général → Latence** : « Images préventives », désactivées
  par défaut, 1, 2 ou 3 images (`LatencySettings`), appliquées sans rouvrir
  le jeu.
- Mesures locales (x86, ROM de test vides) : un état GB fait 204 Kio et se
  sauvegarde en 0,05 ms, un état GBA 682 Kio en 0,13 ms. Sur téléphone, le
  coût dominant sera celui des images rejouées à chaque changement de touche.
- Tests : `raven_frontend_preempt` (faux core à deux images de retard : la
  réaction arrive 1 ou 2 images plus tôt, et 3 fait sauter l'image ; une
  image et un son par appel quoi qu'on rejoue ; chronologie identique à une
  partie aux appuis avancés de n images ; historique effacé après un état,
  la SRAM, la pause, un changement de réglage ou un nouveau jeu ; quirks),
  `raven_frontend_real_cores` (GB, GBC et GBA de RavenCore finissent dans le
  même état octet pour octet que la référence), `raven_frame_pacer` (garde),
  Java (`LatencySettingsTest`, page Général, réglage transmis au lecteur).
  Dix mutations de l'implémentation sont toutes détectées.

Écarts restants : le gain ressenti, le coût sur téléphone et le bon nombre
d'images par jeu restent à mesurer. Le mode Hardcore de RetroAchievements,
pas encore porté, devra vérifier sa compatibilité avec les images préventives.

## Piste 9 : rééchantillonneur sinc

- **Choix de mise en œuvre** : le sinc de libretro-common (MIT) a été
  examiné, en version flottante et entière, sur la révision d48b3aa de
  RetroArch, vérifiée octet pour octet contre le dépôt officiel. Il entraîne
  environ 2 700 lignes d'en-têtes de libretro-common, dont un fichier sans
  aucune mention de licence (`sinc_resampler_internal.h`). Le filtre est donc
  écrit pour RavenEmu (`hardware/audio/sinc_resampler.c`, AGPLv3), d'après la
  méthode classique d'interpolation à bande limitée : sinc fenêtré par Kaiser
  (β = 8, 16 passages par zéro de chaque côté), table polyphasée de 256 lignes
  interpolées, normalisée pour qu'une constante reste exacte. La bande passe à
  90 % de la plus basse des deux fréquences de Nyquist, et le noyau s'élargit
  quand le core émet plus vite que le téléphone ne joue.
- **Pipeline audio** : `raven_audio_set_resampler` choisit sinc (par défaut)
  ou linéaire ; les deux gardent exactement la même cadence de sortie, donc le
  même contrôle dynamique du débit et la même vitesse d'écran. Le filtre est
  construit à l'ouverture du flux (hors du rappel AAudio) et remis à zéro à la
  pause. Le début d'un flux tient sa première trame : pas de clic.
- **Paramètres → Audio → Qualité du son** : « Sinc (défaut) » ou « Linéaire »
  (`PlaybackSettings.resampler`), appliqué sans rouvrir le jeu.
- **Mesures locales** : une note de 10 kHz à 32 768 Hz vers 48 kHz garde un
  rapport signal/bruit de 88,7 dB, contre 13,8 dB en linéaire ; une note de
  30 kHz venue d'un core à 131 072 Hz se replie à 18 kHz à −98 dB, contre
  −1,5 dB en linéaire. Coût : 2,2 ms par seconde de son (0,2 % d'un cœur
  x86), 5,4 ms pour 131 072 Hz ; retard ajouté : 17 trames d'entrée, environ
  0,5 ms.
- Tests : `raven_native_sinc` (constante exacte et cadence linéaire sur cinq
  rapports et des lots irréguliers, images et repliement, saturation sans
  retournement, retard exact, remise à zéro, canaux indépendants, entrées
  invalides ; huit mutations détectées), `raven_native_audio` (cadence, niveau
  et gain dans le pipeline, choix gardé à la réouverture), Java
  (`PlaybackSettingsTest`, page Audio, réglage transmis au lecteur).

Écarts restants : l'écoute et le coût sur téléphone restent à vérifier.

## Paramètres en sous-pages

Demande du propriétaire : à l'intérieur de chaque catégorie, les réglages
étaient empilés sans ordre. Chaque catégorie liste maintenant ses sous-pages,
dans le style des lignes de catégories :

| Catégorie | Sous-pages |
| --- | --- |
| Général | Émulation (pause automatique), Latence (images préventives) |
| Audio | Son du jeu (son, volume), Qualité du son (rééchantillonnage) |
| Vidéo | Couleurs (profil Game Boy, correction LCD), Rendu (mélange d'images, effet d'écran), Taille et réglages (pixels entiers, luminosité, contraste) ; « Rétablir l'affichage d'origine » reste sur la page Vidéo, pour les trois |
| Console | Game Boy et GBC (couleurs, GBC d'une GBA), Game Boy Advance (BIOS et son fichier) |
| Interface | Commandes tactiles (haptique, taille, espacement, style, réinitialisation), Cartouche (vibration, capteurs, soleil de Boktai) |

À propos garde sa liste. Le retour remonte d'un niveau (sous-page, catégorie,
catégories, puis fermeture) ; la page et sa position de défilement survivent à
la recréation. « Vibration de la cartouche » quitte les commandes tactiles pour
la sous-page Cartouche. Aucune préférence ne change de nom ni de valeur.

Tests : parcours de toutes les sous-pages dans l'ordre, retour d'un niveau par
la barre et par Android, sans écriture ; tests existants adaptés aux nouveaux
chemins (réinitialisation de la vidéo depuis sa catégorie, BIOS sur la
sous-page Game Boy Advance, recréation sur une sous-page).

Écart assumé avec la bêta, qui montrait chaque catégorie sur une seule page.

## RetroAchievements, tranche RA-1 : le cœur C

Le chantier RetroAchievements (parité avec la bêta, `ARCHITECTURE.md` §7) est
découpé en trois tranches : **RA-1**, le cœur dans le frontend C ; **RA-2**,
le réseau Java, le compte et la page Paramètres → RetroAchievements, le
bandeau de succès ; **RA-3**, les verrous du mode Hardcore dans le lecteur,
la liste des succès, les classements et le journal hors ligne.

RA-1 :
- **SDK** : rcheevos (MIT), révision `1433173` de la bêta, avec ses deux
  correctifs de sûreté appliqués à une copie (`frontend/cmake/rcheevos.cmake`,
  [provenance](../legal/attributions/rcheevos.md)).
- **Hôte Libretro** : `RETRO_ENVIRONMENT_SET_MEMORY_MAPS` est gardé (copie des
  descripteurs, oubliée au déchargement) et `SET_SUPPORT_ACHIEVEMENTS` accepté.
- **Module** `frontend/src/achievements.c` (code RavenEmu, d'après
  l'adaptateur de la bêta) : compte par mot de passe ou jeton, identification
  du jeu par son empreinte, évaluation après chaque image montrée, Hardcore,
  résumé ; files de requêtes (pour le réseau de la plateforme) et
  d'événements (pour l'interface), textes du serveur sur une ligne et bornés.
- Tests : `raven_frontend_achievements`, avec le vrai core Game Boy de
  RavenCore et une ROM construite par le test (voir `frontend/README.md`).
  Le succès se débloque à la 60ᵉ image, en normal et en Hardcore ; ASan/UBSan
  propres.

## RetroAchievements, tranche RA-2 : compte, réseau et bandeau

- **Pont JNI** `android/src/main/cpp/jni/achievements_bridge.c` et
  `bridge/NativeAchievements.java` : instances par poignée (une poignée
  détruite ne retrouve rien), textes en octets UTF-8 dans les deux sens.
  L'instance du lecteur est liée au jeu du pont : `bridge.c` la nourrit après
  chaque image montrée (jamais les images préventives), la garde vivante en
  pause, la remet à zéro au chargement d'un état et la ferme avant de
  décharger le jeu. La ROM est hachée pendant l'appel (console connue).
- **Réseau** `platform/AchievementsHttp.java` : HTTPS vers
  `retroachievements.org` et ses sous-domaines seulement, sans redirection,
  réponses bornées (8 Mio, images 512 Kio et 512 px), User-Agent de la bêta
  (`platform/RetroUserAgent.java`, versions de RavenEmu, RavenCore 0.14.0 et
  rcheevos lues dans le build). Deux fils réseau ; les réponses reviennent au
  fil GL à l'image suivante, celles d'un jeu fermé sont ignorées.
- **Compte** `settings/AchievementAccount.java` : jeton seul, scellé en
  AES-GCM par une clé du Keystore Android (`platform/KeystoreSealer.java`),
  écriture atomique dans `noBackupFilesDir` ; aucune donnée de la bêta lue.
- **Paramètres → RetroAchievements**, première catégorie comme dans la bêta :
  formulaire de connexion (le mot de passe est vidé à l'envoi), carte du
  compte, Réessayer, Déconnexion, notifications de la bêta
  (`settings/AchievementSettings.java`), « Mode Hardcore indisponible ».
- **Lecteur** : `ui/player/PlayerAchievements.java` (fil GL),
  `AchievementOverlay.java` (bandeau et carte de progression de la bêta,
  badges chargés en tâche de fond), `AchievementNotices.java` (textes),
  `UnlockChime.java` (carillon de la bêta). Permission `INTERNET` ajoutée ;
  sans compte, aucune requête.
- **Licences** : MIT de rcheevos dans l'APK et les crédits ; l'archive de
  sources inclut le checkout rcheevos compilé, vérifié contre l'épinglage.
- **Tests** : `raven_jni_achievements` (`tests/jni/`, JVM de l'hôte avec
  `-Xcheck:jni` : UTF-8 non ASCII dans les deux sens, poignées, succès
  débloqué sur le vrai core GB, nouvelle tentative après un 503, ancienne
  poignée sans effet sur le jeu suivant) ; scénario C « jeton et jeu demandés
  ensemble » et ROM libérée dès le retour de `load_game` (ASan) ; Robolectric
  : compte scellé et illisible ailleurs, réglages, page de connexion (mot de
  passe stocké nulle part, refus, service injoignable, déconnexion),
  bandeau (file, durées, réglages, position), lecteur avec et sans compte ;
  JUnit : textes, User-Agent, carillon.

## RetroAchievements, tranche RA-3

**RA-3a, journal hors ligne** (portage de `AwardJournal.kt`, `AwardPolicy.kt`
et `AwardSyncTracker.kt` de la bêta) :
- chaque requête de déblocage construite par rcheevos (Softcore, HTTPS vers
  `retroachievements.org` exactement) est écrite dans le journal **avant**
  d'être envoyée, et en sort quand le service l'accepte (ou répond qu'il l'a
  déjà) ;
- le journal (`settings/AwardJournal.java`, `noBackupFilesDir`) est scellé
  par le Keystore et lié à son fichier (données associées) : le fichier du
  compte ne s'ouvre pas comme journal, ni l'inverse ; une seule tentative
  gardée par déblocage, 256 au plus ;
- à la session suivante du même compte, une fois connecté, ce qu'une session
  précédente a laissé est renvoyé avec le nouveau jeton (signature et délai
  de rcheevos intacts), de 5 s en 5 s doublées jusqu'à 2 min ; les déblocages
  de la session en cours restent à rcheevos ;
- la déconnexion efface le journal et révoque ceux encore ouverts, comme la
  bêta ; si le journal est indisponible ou plein, le bandeau le dit une fois.

**RA-3b, menu de jeu** (portage de `RetroAccountCard.kt`,
`RetroAchievementsContent.kt` et `RetroLeaderboardsContent.kt`) :
- module C : liste des succès dans l'ordre de rcheevos (regroupés par
  progression), classements, vingt scores (les meilleurs ou autour du
  joueur, événements `scores` et `scores_error`, requête remplacée
  abandonnée), Rich Presence, résultat de classement
  (`leaderboard_scoreboard`) ; copies, libérées avec le jeu ;
- pont JNI et `NativeAchievements` : `entries`, `boards`, `fetchScores`,
  `scores`, `richPresence` ; instantané lu sur le fil GL
  (`ui/player/AchievementsSnapshot.java`), déblocages en attente comptés
  dans le journal hors du fil GL ;
- carte RetroAchievements sous le titre du menu de jeu ; elle ouvre le
  panneau de la bêta dans le même menu : compte (avatar, points, jeu, Rich
  Presence, déblocages en attente), Réessayer, Déconnexion, succès du jeu
  (Tous, À obtenir, Obtenus ; recherche ; tri par ordre du jeu, progression
  ou date ; dix puis quinze de plus ; fiche avec « Voir sur le site »),
  classements (meilleurs scores, autour de moi, réessayer, lien du site),
  « Mode Softcore · SDK officiel rcheevos » ; sans compte, la carte mène à
  Paramètres → RetroAchievements ; un compte connecté, changé ou déconnecté
  dans les paramètres s'applique au jeu en cours dès le retour, comme dans
  la bêta ;
- l'état d'envoi de chaque succès suit la bêta : débloqué localement, en
  attente d'envoi, confirmé par RetroAchievements.

**RA-3c, indicateurs** (portage de `RetroIndicatorState.kt`) : les défis en
cours et les suivis de classement restent en bas de l'image, quatre lignes
au plus, chacun jusqu'à son propre masquage par rcheevos ; une valeur de
suivi se met à jour sur place ; en bas, les bandeaux passent au-dessus ; une
remise à zéro Hardcore ou la fermeture du jeu les efface.

**RA-3d, verrous du mode Hardcore** (portage de `RetroHardcorePolicy.kt` et
`HardcoreGate.kt`) : comme dans la bêta, le Hardcore attend l'approbation de
RavenEmu par l'équipe RetroAchievements ; tant qu'elle manque
(`settings/HardcorePolicy.approved()`), aucun interrupteur n'est montré et
rien ne l'active. Une fois approuvé :
- la session démarre dans son mode avant la connexion (pas de remise à zéro
  au chargement) ; activé pendant un jeu, il le redémarre
  (`raven_frontend_reset`, nouveau, puis rcheevos) ;
- aucune image ne tourne pendant la connexion et l'identification de la ROM ;
  sans réseau, la partie attend et réessaie (5 s doublées, 1 min au plus),
  avec le message de la bêta ;
- aucun état n'est chargé, l'annulation d'un chargement comprise ;
- chaque pause du menu est demandée à rcheevos, qui la refuse trop tôt après
  la précédente (« Mode Hardcore : pause indisponible pour le moment. ») ;
- les requêtes de déblocage Hardcore entrent alors dans le journal ;
- `retro_reset` est un symbole facultatif pour l'hôte : sans lui, pas de redémarrage.

Ces verrous sont testés en simulant l'approbation (doublure Robolectric de
`HardcorePolicy.approved()`), et le pont JNI démarre réellement une session
Hardcore sur le core GB dans `raven_jni_achievements`.

Écarts restants (RA-3 terminée) : progression des succès dans les états de
jeu (un chargement d'état remet les succès à zéro ; la bêta ne la sauvegardait
pas non plus), avatars des classements, validation sur téléphone avec un vrai
compte, et la demande d'approbation Hardcore à RetroAchievements, qui revient
au propriétaire.

Les écarts que RA-2 laissait sont ainsi traités : verrous du mode Hardcore
et son activation (en attente d'approbation), liste des succès, classements,
indicateurs de défi et de suivi, carte du compte dans le menu de jeu, journal
hors ligne. La recréation du lecteur (rotation) reconnecte le compte et
ré-identifie le jeu.

## Correctif : code natif optimisé dans l'APK de développement

Constat du propriétaire : tous les jeux (GB, GBC, GBA) étaient lents dans
l'APK de développement, déjà avant RetroAchievements. La CI produit cet APK
par `:android:assembleDebug`, et le NDK compile alors le code natif en `-O0`.
La bêta avait rencontré le même problème et le corrigeait dans
`native/CMakeLists.txt` ; la correction manquait dans `android/`.

- Mesure sur l'hôte, vrai core GBA de RavenCore, 600 images : 14,7 ms par
  image en `-O0` contre 2,4 ms en `-O2`, pour 16,7 ms de budget. Un téléphone,
  plusieurs fois plus lent par cœur, passe sous les 60 images/s en `-O0`.
  Le sinc ne pèse presque rien (0,8 % d'un cœur en `-O0`).
- `android/src/main/cpp/CMakeLists.txt` ajoute `-O2` en Debug avant d'inclure
  le frontend : le frontend, rcheevos, les cores RavenCore et les pilotes en
  héritent ; les symboles de débogage restent. Les sources de RavenCore ne
  changent pas.
- `scripts/check_native_optimization.py` (et ses tests) lit les bases de
  compilation laissées par Gradle sous `android/.cxx` : la CI échoue si une
  commande finit sans `-O2`/`-O3` ou si l'une des cibles natives manque.

À vérifier sur téléphone : vitesse réelle des jeux GB, GBC et GBA.

## Sauvegardes, lot S-1 : import et export depuis la fiche du jeu

Portage de `GbSaveTransfer` et `GbSaveTransfersUi` de la bêta 0.8.1, pour la
sauvegarde de la cartouche (SRAM).

- **Fiche du jeu**, section « Sauvegardes » de la bêta : « Exporter » propose
  `.sav` (la plupart des émulateurs) ou `.srm` (RetroArch), sous le nom de la
  ROM ; « Importer » lit un `.sav`, un `.srm` ou une archive `.zip` RavenEmu.
- **Revue avant écriture**, avec les textes de la bêta : jeu visé, contenu,
  avertissement pour un fichier seul qui ne nomme pas sa ROM, données
  remplacées. Rien n'est écrit avant « Confirmer l'import ».
- **Archives de la bêta** (`RavenEmu-GB-Saves` v1 et la génération V3, paquet
  d'un jeu ou archive de bibliothèque avec un dossier par jeu) : liées au
  SHA-256 des octets de la ROM, manifeste et sommes de contrôle vérifiés,
  noms, dossiers et tailles bornés (archive piégée refusée). Seule la SRAM est
  importée : les états de l'archive sont vérifiés puis nommés comme non
  importés, car ceux de la bêta ne portent pas l'identité du core qui les a
  écrits.
- **Ordre des écritures** : l'exécuteur de sauvegarde du lecteur devient
  `SaveStorage.IO`, partagé avec la bibliothèque. Le lecteur met sa dernière
  SRAM en file dans `onPause`, avant que la bibliothèque reprenne la main :
  un import la suit toujours et n'est jamais écrasé par elle.
- **Taille différente** : un `.sav` d'un autre émulateur peut porter une autre
  fin d'horloge, ou aucune. Le lecteur ne refuse plus le jeu : il lit le
  fichier comme RavenCore le lit, ses premiers octets sur la SRAM neuve du
  core, l'annonce, puis la réécrit à la taille du core. Un jeu sans SRAM
  garde le fichier sans le lire. Une restauration refusée à taille égale
  arrête toujours le jeu en conservant le fichier.
- Tests : `SaveTransferTest` (fichiers bruts, archives d'un jeu et de
  bibliothèque, autre ROM, fichier altéré, chemins, états seuls, adaptation
  de taille, nom d'export), `HomeActivityTest` (export `.srm`, export sans
  sauvegarde, revue puis confirmation, refus et annulation, archive, reprise
  après recréation), `PlayerActivityTest` (taille différente, jeu sans SRAM,
  exécuteur partagé).

Écarts restants : les états de la bêta ne sont pas importés (leur conversion
demanderait la décision du propriétaire). Validation sur téléphone à faire.

## Sauvegardes, lot S-2 : export de toutes les sauvegardes

Menu ⋮ de la bibliothèque, « Exporter toutes les sauvegardes (.zip) », comme
dans la bêta.

- **Choix du propriétaire** : l'archive contient la SRAM seule, au format de
  la bêta (`RavenEmu-GB-Saves` v1), pour qu'elle se réimporte dans cette
  application **et** dans la bêta (retour arrière). Les états de jeu natifs
  restent sur le téléphone : ceux de la bêta sont d'un autre format.
- Disposition de la bêta (`exportLibrary`) : un dossier par jeu,
  « nom de la ROM (8 premiers caractères de son SHA-256) », suffixé « 2 »,
  « 3 »… en cas de doublon, avec son manifeste en premier, puis `battery.sav`.
  Le SHA-256 est celui des octets de la ROM, l'identifiant de la bêta.
- Nom proposé : `RavenEmu-sauvegardes-AAAA-MM-JJ.zip`. Sans aucune
  sauvegarde, rien n'est demandé : « Aucun jeu de la bibliothèque n'a de
  sauvegarde à exporter. »
- Même exécuteur `SaveStorage.IO` que le lecteur : l'archive suit la dernière
  SRAM écrite.
- Tests : `SaveTransferTest` (disposition exacte des entrées, relecture de
  chaque jeu par son SHA-256, dossiers en doublon, refus sans sauvegarde),
  `HomeActivityTest` (menu, sélecteur, relecture de l'archive écrite).

Validation sur téléphone, et import réel de l'archive dans la bêta, à faire.

## Bibliothèque, lot S-3 : import d'un dossier

Menu ⋮, « Importer un dossier », après « Vue grille/liste » comme dans la
bêta (`GbFolderImporter`, `FolderImportSummaryDialog`).

- Sélecteur de dossier Android (`ACTION_OPEN_DOCUMENT_TREE`) : l'autorisation
  vaut pour cet import ; les ROM sont copiées dans la bibliothèque privée,
  aucune permission persistante n'est gardée (`platform/DocumentTree`).
- Parcours en largeur des sous-dossiers, aux limites de la bêta : profondeur
  8, 128 dossiers, 4 000 entrées, 256 ROM examinées, 256 Mio lus, 256 jeux
  dans la bibliothèque. Un dossier déjà vu (boucle) n'est relu qu'une fois.
  Le dossier choisi doit s'ouvrir ; un sous-dossier illisible est compté.
- Chaque ROM suit les règles du bouton « + » (extension, taille, empreinte
  par console) ; un doublon est ignoré, la partie et les sauvegardes ne sont
  jamais touchées. La bêta validait chaque ROM avec un core temporaire ;
  l'hôte natif n'ouvre qu'une instance Libretro par processus, il vérifie donc
  les en-têtes que RavenCore exige (marqueur `0x96` d'une ROM GBA, en-tête
  d'une ROM Game Boy) et compte le reste comme ROM invalides.
- Ligne de progression sous la recherche (« Analyse du dossier… »,
  « Importation du dossier… N ROM examinées »), puis le bilan de la bêta :
  jeux ajoutés, doublons, ROM invalides, illisibles, autres fichiers, dossiers
  parcourus, arrêt sur limite.
- Tests : `FolderImportTest` (comptes sur un dossier fictif, doublon, limites
  de profondeur et d'entrées, boucle, dossier illisible, en-têtes),
  `HomeActivityTest` (menu, sélecteur, bilan, dossier illisible).

Écart : la bêta ne propose pas de sélection de plusieurs fichiers avec « + » ;
elle n'est pas ajoutée. Validation sur téléphone à faire.

## Codes de triche, lots T-1 et T-2

Portage de `GbCheatList`, `GbCheatStore`, `GbCheatsUi` et `GbCheatsPanel` de
la bêta 0.8.1 ; les cores RavenCore lisent déjà les codes par Libretro
(`retro_cheat_set`).

- **T-1, frontend C et JNI** : `raven_frontend_set_cheats` remplace la liste
  (`retro_cheat_reset` puis `retro_cheat_set`, symboles facultatifs). RavenCore
  nomme un code refusé dans un message : le frontend rend l'indice du premier
  refusé et sa raison, et garde ce message hors de l'écran pour qu'il
  s'affiche une seule fois, sous le formulaire. `NativeBridge.setCheats` et
  `cheatRefusal` (fil GL) copient les codes un à un. Tests : faux core, et
  vrais cores GB, GBC et GBA (un code GameShark ou Codebreaker change la
  mémoire du jeu, un code illisible est refusé) ; ASan/UBSan propre.
- **T-2, menu de jeu** : « Codes de triche » (ou « · N actif(s) ») après
  « Annuler le chargement », comme dans la bêta, ouvre la page : liste avec
  interrupteur et « Supprimer », formulaire « Nom (facultatif) » et « Code »
  avec l'aide de la bêta sur les formats. Un code ajouté est d'abord soumis au
  core : refusé, il reste dans le formulaire avec la raison de RavenCore ;
  accepté, il est gardé, actif.
- **Fichier** : `native-cheats/<clé>.cheats`, au format de RavenEmu PC
  (« 1⇥nom⇥code » par ligne, comme la bêta), écrit en entier et
  atomiquement, supprimé quand la liste est vide ; lu au chargement du jeu,
  un code devenu illisible est annoncé.
- **Hardcore** : aucun code ne tourne tant que le Hardcore est demandé ou
  actif, la liste reste visible, interrupteurs et ajout désactivés ; le
  verrou est suivi à chaque image et la liste réappliquée à sa levée.
- Tests : `CheatListTest`, `CheatRepositoryTest`, `PlayerActivityTest`
  (codes enregistrés appliqués et comptés, ajout accepté ou refusé avec sa
  raison, désactivation et suppression, Hardcore).

Écart : la bêta acceptait d'ajouter un code en Hardcore (sans l'exécuter) ;
l'application native ne peut le vérifier qu'en le donnant au core, l'ajout
y est donc désactivé. Validation sur téléphone à faire.

## Icônes RavenEmu, lot I-1

Demande du propriétaire : les icônes faisaient « brouillon », et la catégorie
Console des Paramètres n'avait pas d'icône à elle (comme dans la bêta, elle
reprenait celle d'« À propos »).

- `ui/common/RavenIcon` : un jeu d'icônes dessinées pour RavenEmu sur une
  grille de 24, un trait de 2 aux bouts et angles arrondis et quelques
  détails pleins, toutes dans la couleur d'accent violette (demande du
  propriétaire : plus aucune icône blanche ou grise ; seul le doré marque un
  succès débloqué) ; même rendu sur tout téléphone et à toute densité, jamais
  un caractère d'une police. L'onglet actif se distingue par son fond et sa
  taille.
- Remplacements : `⚙`, `+`, `⋮` de la bibliothèque ; la loupe `⌕` de la
  recherche ; `‹` et `⋮` du lecteur ; `‹` des Paramètres, des licences et des
  pages du menu de jeu ; les chevrons `›` et liens `↗` ; les marques radio
  `●`/`○` ; l'étoile `★` du bandeau des succès (dorée pour un déblocage) ; le
  trait `━━` des jaquettes absentes, devenu le pictogramme de la console.
- Onglets de la bibliothèque : les pictogrammes de console de la bêta
  (`PageIndicator`, `ConsoleIcons`) au lieu de « ● GB GBC GBA » : case de
  36 dp, fond violet à 16 % et icône plus grande pour la page active, écran
  en couleur de la GBC atténué sur une page inactive.
- Catégories des Paramètres : trophée, réglages, haut-parleur, écran,
  **console portable pour Console**, manette pour Interface, information.
- Les descriptions d'accessibilité restent celles d'avant.
- Tests : `RavenIconTest` (chaque glyphe dessiné dans sa couleur et dans son
  cadre, écran GBC en couleur et atténué, radio pleine ou vide),
  `HomeActivityTest` (barre, recherche et onglets sans caractère),
  `PlaybackSettingsActivityTest` (une icône par catégorie, Console comprise).

Écart voulu par le propriétaire : ces icônes modernisent celles de la bêta,
qui n'est pas modifiée. Validation sur téléphone à faire.

## Boutons tactiles redessinés et bouton Menu, lot B-1

Demande du propriétaire : les boutons en jeu faisaient « brouillon », et en
Game Boy / Game Boy Color le menu, que la #42 avait mis entre Select et
Start, devait aller en **haut, au centre** des commandes.

- `GameControlsView` dessine toutes les commandes dans la palette de
  l'application : face sombre (`#2B2D40` → `#191B28`), contour d'accent à
  60 %, lettres, flèches et pictogrammes dans l'accent `#A78BFA` ; la partie
  appuyée se remplit d'accent et son libellé passe en sombre. Le style
  « contraste élevé » éclaircit la face et trace le contour en blanc, comme
  avant.
- Croix d'un seul tenant (un seul contour au lieu de deux barres superposées),
  une flèche au milieu de chaque branche, un moyeu discret ; seules les
  branches appuyées s'allument, diagonales comprises. A/B ronds, Select/Start
  en pastilles fines (la zone tactile reste de 84 × 40 dp), L/R en pastilles.
- **Bouton Menu** de la #42 (« Menu du jeu »), redessiné en pastille à trois
  traits (`RavenIcon.Glyph.MENU`) : en portrait, en haut au centre des
  commandes, entre L et R en GBA et, en GB/GBC, dans le haut libre entre la
  croix et A, sans rangée de plus ; jamais entre Select et Start. Sur un
  écran étroit il se décale hors de la croix, puis s'amincit (24 dp au
  moins). Il ouvre le menu du jeu, n'envoie aucun bouton à la console et
  reste fixe pendant « Déplacer les boutons » (comme dans la #42 :
  `ControlLayout` ne garde que les sept commandes de jeu). Masqué en
  paysage, où le haut du centre appartient à l'image ; le `⋮` de la barre
  reste dans les deux orientations.
- Tests : `GameControlsViewTest` (place du menu en GB/GBC, sur écran étroit
  et en GBA, aucun masque console, masqué en paysage et sans action, rendu :
  seule la branche ou le bouton appuyé prend l'accent), `PlayerActivityTest`
  (le bouton ouvre le menu et met le jeu en pause).

Écart voulu par le propriétaire : le dessin et le bouton Menu diffèrent de
`GbTouchControls` de la bêta, qui n'est pas modifiée. Validation sur téléphone
à faire (lisibilité, place du menu sur les petits écrans).

## Skins Delta, lot K

Portage en Java des skins Delta de la bêta 0.8.1 (`androidApp/.../deltaskin`),
au choix du propriétaire. Aucun skin n'est fourni : l'utilisateur importe les
siens.

- **Données** (`data/skins`) : `DeltaSkin` lit `info.json` comme la bêta
  (propriétés obligatoires, types stricts, clés inconnues ignorées) ;
  `DeltaSkinValidator` garde ses limites (GB/GBC ou GBA, au moins une
  représentation iPhone portrait, cadres dans `mappingSize` à 1 % près,
  nombres finis, PDF nommé à la racine, entrées inconnues mises de côté) ;
  `DeltaSkinArchive` relit l'archive sous les mêmes limites (25 Mio, 64
  entrées, 50 Mio décompressés, taux de compression, chemins, liens
  symboliques, archives imbriquées, doublons, un seul `info.json`, PDF d'une
  page au ratio de la représentation) puis prépare l'installation dans un
  dossier temporaire privé, les PDF portrait sous des noms fixes ;
  `DeltaSkinRepository` installe d'un bloc dans `files/delta-skins/<SHA-256 de
  l'identifiant>`, remplace en gardant l'ancien dossier jusqu'à relecture du
  nouveau, et n'affiche qu'un skin qui se relit en entier. Le choix par console
  (GB/GBC partagent le leur) est dans les préférences de l'application ; les
  skins de la bêta ne sont jamais lus.
- **Paramètres → Interface → Skin Delta** : texte de la bêta, « Importer un
  fichier .deltaskin » (sélecteur Android, copie bornée dans le cache privé),
  liste avec « Utiliser » / « Retirer » et « Supprimer » (confirmation), messages
  de la bêta. Un skin importé est choisi pour sa console. Tout se fait hors du
  fil d'interface.
- **Lecteur, en portrait** : le skin choisi pour la console du jeu remplace les
  commandes tactiles. Comme dans la bêta, son PDF est dessiné en bas, à la
  largeur de l'écran, et l'image du jeu se place au-dessus, centrée à son
  format. La page est rendue par `PdfRenderer` hors du fil d'interface et
  gardée en cache. Toucher, glisser et plusieurs doigts suivent la bêta : cadre
  dessiné prioritaire sur les zones étendues, croix découpée en 3 × 3 pour les
  diagonales, bouton relâché avec son dernier doigt, entrée `menu` qui ouvre le
  menu du jeu, marque discrète sur la partie appuyée, retour haptique des
  réglages. Un skin qui ne se dessine pas est retiré pour sa console
  (« Skin invalide : commandes classiques restaurées »). En paysage, les
  commandes classiques restent (la bêta n'affiche les skins qu'en portrait) ;
  « Déplacer les boutons » ne s'applique pas à un skin.
- Tests : `DeltaSkinTest` (manifeste et validation), `DeltaSkinArchiveTest`
  (préparation, quinze refus dont chemins, lien symbolique, archive imbriquée
  ou déguisée, bombe de compression), `DeltaSkinRepositoryTest` (installation,
  doublon, conflit, remplacement, suppression, dossier abîmé), `DeltaSkinInputTest`
  (mise en page, choix standard ou bord à bord, toucher, plusieurs doigts,
  menu), `PlaybackSettingsActivityTest` (page Skin Delta, refus d'un fichier
  qui n'est pas un .deltaskin), `PlayerActivityTest` (le skin remplace les
  commandes, A et le menu du skin, repli sur les commandes classiques).

Écarts restants : les bandeaux RetroAchievements restent dans la zone de
l'image au lieu de toute la zone du skin ; les représentations paysage et les
écrans multiples (`screens`, `gameScreenFrame`) sont lus et vérifiés mais pas
utilisés, comme dans la bêta. Validation sur téléphone à faire avec de vrais
skins (rendu des PDF, précision du toucher, bord à bord sur un écran haut).

## Câble link local, lots L-1 et L-2

Portage du câble link de la bêta (`LinkCable`, `LinkPartnerDialog`,
`LinkPlayerScreen`) : deux jeux de la bibliothèque tournent ensemble sur le
téléphone, reliés par un câble virtuel, pour les échanges et combats entre
ses propres sauvegardes. RavenCore le fait déjà par son sous-système Libretro
`link` ; aucun code de RavenCore n'est modifié.

- **Frontend C (L-1)** : `raven_frontend_load_linked` charge les deux jeux par
  `retro_load_game_special` après lecture de `SET_SUBSYSTEM_INFO`,
  `raven_frontend_players` dit 1 ou 2, et chaque joueur a sa SRAM
  (`raven_frontend_*_player_battery`). Les images préventives restent coupées
  avec deux jeux. Testé sur les vrais cores GB, GBC et GBA (image double, SRAM
  de chaque joueur, état, retour à un jeu seul) et sous ASan/UBSan.
- **Pont JNI (L-1)** : `openLinked`, `setLinkFocus` (le joueur montré : sa
  moitié de l'image, la manette et les commandes tactiles sur son port, la
  vibration et les capteurs), `savePlayerBattery` / `restorePlayerBattery`.
- **Bibliothèque (L-2)** : la fiche du jeu gagne « Jouer à deux (câble link) »
  ; la fenêtre « Jouer à deux » de la bêta liste les jeux compatibles (Game Boy
  et Game Boy Color ensemble, ou deux Game Boy Advance ; jamais le même jeu,
  une cartouche n'a qu'une sauvegarde), avec son texte et ses messages.
- **Lecteur (L-2)** : la barre dit « Câble link · Joueur N » et le nom du jeu
  ; le bouton « Joueur M » (icône dessinée à deux flèches, au lieu du « ⇄ »
  de la bêta) passe à l'autre joueur, qui continue de tourner pendant ce
  temps. Les deux jeux tournent dans le core GBA, ou dans le core GBC dès que
  l'un en a besoin (jeu GBC ou « Jeux Game Boy en couleurs »). Chaque
  sauvegarde est lue et réécrite pour son propre jeu (à l'ouverture, toutes les
  30 s et à la pause), une sauvegarde d'une autre taille lue comme pour un jeu
  seul. Comme dans la bêta, pas de menu de jeu : ni ⋮, ni bouton Menu des
  commandes, ni touche Home ; donc ni états, ni codes de triche, ni
  RetroAchievements, ni skin Delta en mode câble.
- Tests : `LinkCableTest` (compatibilités, partenaires, core choisi),
  `HomeActivityTest` (fenêtre des partenaires et lancement),
  `PlayerActivityTest` (deux jeux ouverts, sauvegarde de chacun restaurée et
  réécrite, bascule de joueur, pas de menu, jeux incompatibles refusés).

Écarts restants : le son est toujours celui du joueur 1, car le sous-système
de RavenCore ne fait entendre que lui ; la bêta faisait entendre le joueur
montré. Il faudrait une option dans RavenCore (PR à part), à décider par le
propriétaire. Validation sur téléphone à faire avec deux vraies cartouches
(échange Pokémon, par exemple).

## Thèmes RavenEmu et NVIDIA, lot TH

Demande du propriétaire : un système de thème, avec le thème RavenEmu actuel
et un thème NVIDIA aux couleurs de RetroArch ; les icônes et les boutons des
consoles prennent eux aussi la couleur du thème. La bêta n'avait que les
couleurs de RavenEmu : c'est un ajout voulu par le propriétaire.

- **Palette** (`ui/common/RavenTheme.java`) : un seul endroit pour les couleurs
  de l'interface, par rôle (fond, surface, carte, accent clair et profond,
  texte, ligne choisie, interrupteurs, faces des commandes). **RavenEmu** garde
  les valeurs de la bêta (`RavenColors.kt`). **NVIDIA** reprend les valeurs du
  thème « NVIDIA Shield » du menu MaterialUI de RetroArch
  (`menu/drivers/materialui.c`) : fond 121212, surface 1D1D1D, barres 242424,
  icônes 7AB547, ligne choisie 255D00, texte DEDEDE, indications 999999,
  interrupteurs 85BB5C sur 498515 et 33691E sur 003D00. Seules des valeurs de
  couleur sont reprises, aucun code. Les quelques rôles que MaterialUI ne
  couvre pas (boutons en relief, colonne de la liste, faces des commandes) sont
  des gris pris entre les siens.
- **Styles Android** : `RavenEmuNativeTheme.Nvidia` et
  `RavenGameSheetTheme.Nvidia` donnent les mêmes couleurs aux widgets du
  système (curseur, dialogues, feuilles).
- **Tout suit le thème** : bibliothèque, fiche du jeu, dialogues, paramètres,
  licences, barre du lecteur, menu de jeu, succès, codes de triche, bandeaux
  RetroAchievements, toutes les icônes dessinées et les commandes tactiles
  (contour, lettres, flèches, partie pressée, bouton Menu). Gardent leur
  couleur : l'or d'un succès débloqué, le rouge des erreurs, l'écran coloré du
  pictogramme Game Boy Color, l'image du jeu, les skins Delta (leurs propres
  images) et l'icône de l'application.
- **Paramètres → Interface → Thème** : « RavenEmu (défaut) » ou « NVIDIA »
  (préférence `interface.theme`). Les paramètres se redessinent aussitôt sur la
  même page, la bibliothèque au retour. **Un jeu déjà ouvert garde ses couleurs
  jusqu'à sa fermeture** : recréer le lecteur relancerait le jeu et perdrait la
  partie non sauvegardée. Chaque écran lit le thème de l'Activity qui l'a créé
  (`RavenTheme.of`), donc jamais deux palettes mélangées.
- Tests : `RavenThemeTest` (valeurs de la bêta et de RetroArch, contraste du
  texte d'au moins 4,5:1 dans chaque thème, préférence absente ou invalide,
  thème de l'Activity pour ses dialogues), `PlaybackSettingsActivityTest`
  (page Thème, recréation sur la même page, interrupteurs NVIDIA),
  `HomeActivityTest` (bibliothèque, icônes et fiche recolorées au retour),
  `PlayerActivityTest` (nouvelle partie dans le thème choisi, partie en cours
  jamais recréée), `GameControlsViewTest` (bouton A pressé en vert).

Écarts restants : validation sur téléphone à faire (lisibilité au soleil,
captures des deux thèmes). NVIDIA et Shield sont des marques de NVIDIA
Corporation ; RavenEmu n'est ni affilié à NVIDIA ni approuvé par elle, le nom
désigne seulement la palette.

## Fondu audio de démarrage, lot F-1

Portage du fondu de la bêta (`AndroidGbAudioOutput.fadeIn`) dans le pipeline
audio C (`android/src/main/cpp/hardware/audio/audio_pipeline.c`) : un cosinus
surélevé (0,5 − 0,5 cos πt) sur les 180 premières millisecondes de son, à la
fréquence du périphérique, avant le volume, arrondi comme dans la bêta. Il
commence au premier échantillon qui n'est pas du silence, pour que les images
muettes du début d'un jeu ne le consomment pas, et repart à chaque ouverture
du flux, à chaque reprise après une pause et à la réouverture d'un appareil
perdu. Le carillon des succès n'est pas atténué, comme dans la bêta. La page
**Audio → Son du jeu** reprend la note de la bêta : « Le fondu de démarrage de
180 ms reste inchangé. »

- `raven_audio_set_startup_fade(ms)` : 180 par défaut, 0 le coupe (tests aux
  valeurs exactes) ; le choix survit à la fermeture du flux.
- Tests : scénario `the_start_fade_rises_from_the_first_sound` (silence
  intact, départ à zéro, montée régulière sans marche, quart et moitié du
  niveau, niveau plein après 8 640 images à 48 kHz, nouveau fondu après une
  pause et pour un nouveau jeu, volume appliqué après le fondu, fondu coupé) ;
  deux mutations détectées (pas de fondu à la reprise, pas de fondu du tout).

Écart restant : écoute sur téléphone à faire.

## Conditions d'utilisation et confidentialité dans l'application, lot F-2

Comme la bêta (`LegalMarkdown`, `DocumentReader`), **Paramètres → À propos →
Conditions d'utilisation** et **Politique de confidentialité** s'ouvrent dans
un lecteur intégré (`ui/settings/LegalDocumentActivity`) au lieu de GitHub.

- **Source unique** : la tâche Gradle `legalDocuments`
  (`android/build.gradle.kts`) copie `TERMS.md` et `PRIVACY.md` du dépôt dans
  les assets de l'APK (`legal/`) à chaque construction, par l'API des
  variantes d'AGP ; aucune copie n'est tenue à la main.
- **Lecture** : `LegalMarkdown` (Java, sans Android) reprend le sous-ensemble
  Markdown de la bêta (titres, paragraphes, puces, **gras**, `code`, liens
  http(s)) ; le premier titre va dans la barre, la date d'entrée en vigueur
  devient une légende. Le lecteur suit la mise en forme de la bêta (titres de
  niveau 1-2 dans l'accent, puces « • » dans l'accent, lignes de 21 sp, liens
  soulignés qui ouvrent le navigateur ou disent qu'aucune application ne peut
  les ouvrir) et les couleurs du thème choisi.
- Tests : `LegalMarkdownTest` (cas de la bêta, titres en double, niveaux
  bornés, liens non web laissés en texte, documents du dépôt) ;
  `LegalDocumentActivityTest` (assets identiques aux fichiers du dépôt,
  titres, puces, liens actifs, thème NVIDIA, document inconnu) ;
  `PlaybackSettingsActivityTest` (À propos ouvre le lecteur).

## Petits écarts, lot E-1 : avatars des classements

Chaque ligne d'un classement RetroAchievements (menu du jeu → succès →
classements) montre l'avatar du joueur à gauche du rang, du nom et du score.
L'adresse vient du SDK (`rc_client_leaderboard_entry_get_user_image_url`,
hôte d'images de RetroAchievements), passe par `raven_achievements_score.avatar`,
le pont JNI (six champs par score) et `NativeAchievements.Score.avatar` ;
l'image se charge comme les badges (`BadgeCache`, HTTPS vers
retroachievements.org et ses sous-domaines seulement). La bêta n'en montrait
pas : c'est un ajout demandé par le propriétaire.

- Tests : `test_achievements.c` (adresse des deux joueurs) ;
  `AchievementsPanelTest` (avatar demandé pour sa ligne).

## Petits écarts, lot E-2 : bandeaux des succès sur tout le skin

Avec un skin Delta, les bandeaux, indicateurs et progressions de
RetroAchievements couvrent toute la zone du skin, comme dans la bêta
(`RetroOverlays` dans la boîte du skin), et non plus la seule image ; avec
les commandes classiques, ils reviennent sur l'image. La surcouche ne prend
aucun toucher : les boutons du skin restent actifs sous elle.

- Tests : `PlayerActivityTest` (surcouche au-dessus du skin, sur toute sa
  taille, un toucher sur la scène atteint encore le bouton A ; retour sur
  l'image quand le skin ne peut pas se dessiner).

## Petits écarts, lot E-3 : skins Delta en paysage

La bêta n'affichait les skins qu'en portrait ; ils servent maintenant aussi en
paysage quand ils ont une représentation paysage.

- **Import** : les PDF des quatre représentations iPhone (portrait et
  paysage, standard et bord à bord) sont gardés sous des noms fixes
  (`standard-landscape.pdf`, `edge-to-edge-landscape.pdf`) ; ceux du paysage
  étaient déjà vérifiés (cadres, rapport du PDF) et sont maintenant conservés.
  Les métadonnées les notent (`standardLandscapeAsset`,
  `edgeToEdgeLandscapeAsset`, champs facultatifs, même schéma). **Un skin
  importé avant cette version n'a pas ses PDF paysage : il faut le
  réimporter** pour l'avoir en paysage.
- **Mise en page** (`DeltaSkinLayout.calculateLandscape`) : le skin entier est
  ajusté à la scène et centré ; l'image du jeu va dans la sortie du premier
  écran du skin (`screens[0].outputFrame`), sinon dans `gameScreenFrame`,
  sinon sur tout le skin, au format de la console. Un écran large prend la
  représentation bord à bord quand le skin l'a.
- **Dessin** : l'image du jeu reste visible à travers le skin à l'endroit de
  son écran, sauf pour un skin déclaré `translucent`, dessiné par-dessus. Le
  cache des pages distingue maintenant le portrait du paysage.
- **Lecteur** : en paysage, le skin remplace les commandes tactiles comme en
  portrait (boutons, bouton menu, bandeaux des succès sur tout le skin). Un
  skin sans paysage **reste choisi** : les commandes classiques le remplacent
  en paysage, sans message d'erreur, et il revient en portrait.
- Tests : `DeltaSkinArchiveTest` (PDF paysage gardé, mauvais rapport refusé),
  `DeltaSkinRepositoryTest` (relecture, PDF paysage manquant refusé),
  `DeltaSkinInputTest` (cadre de l'écran, `gameScreenFrame`, skin entier,
  centrage sur écran large, boutons), `PlayerActivityTest` (paysage : PDF
  paysage dessiné, image dans le cadre, bouton A ; skin portrait seul gardé
  derrière les commandes classiques) ; une mutation détectée.

Écart restant : rendu à vérifier sur téléphone avec de vrais skins paysage.

## Petits écarts, lot E-4 : boutons déplaçables sur l'image en portrait

En portrait, **Menu du jeu → Déplacer les boutons** permet maintenant de
mettre un bouton sur l'image, comme en paysage. La vue des commandes couvre
toute la scène (image comprise, fond transparent) ; à leurs places d'origine,
les commandes restent en bas, et l'image garde sa place au-dessus d'un espace
noir de leur hauteur (`GameControlsView.footprint()`, qui suit la taille,
l'espacement et les gâchettes L/R). Les dispositions déjà enregistrées ne
bougent pas : leurs décalages partent des mêmes places.

- Tests : `GameControlsViewTest` (espace sous l'image de la hauteur des
  commandes et plus haut à 115 %, commandes en bas d'une scène plus haute,
  fond transparent, bouton glissé jusqu'en haut de la scène) ;
  `PlayerActivityTest` (vue des commandes sur toute la scène, A monté
  au-dessus de l'image et gardé).

Écart restant : précision du glissement à vérifier sur téléphone.

## Petits écarts, lot E-5 : vibration et capteurs de la manette

La dernière entrée utilisée décide : une touche ou un stick d'une manette la
choisit, un toucher sur l'écran rend la main au téléphone ; une manette
débranchée aussi (`InputManager.InputDeviceListener`), et la pause oublie la
manette jusqu'à la prochaine entrée. La bêta n'utilisait que le téléphone.

- **Vibration** (`CartridgeRumble`) : la manette qui a un moteur vibre au
  rythme de la cartouche (mêmes impulsions de 40 ms), sinon le téléphone ;
  changer de source arrête aussitôt l'impulsion en cours.
- **Capteurs** (`ControllerMotion`, Android 12 et plus) : l'accéléromètre et
  le gyroscope de la manette, quand elle en a et que **Capteurs du
  téléphone** est activé, remplacent ceux du téléphone. Java lit leurs
  événements ; le fil d'émulation les donne au pilote C avant chaque image
  (`NativeBridge.feedControllerMotion` → `raven_sensors_feed_controller`),
  sans verrou. Le pilote rend leurs valeurs le long de la manette (sans
  rotation de l'écran), seulement quand le core les demande et que le jeu
  tourne ; un capteur que le téléphone n'a pas peut être activé si la manette
  l'a. La lumière (Boktai) reste celle du téléphone.
- Tests : `test_sensor_driver.c` (valeurs de la manette, sans rotation, pause,
  gyroscope absent du téléphone, retour au téléphone, fermeture du jeu) ;
  `CartridgeRumbleTest` (manette au lieu du téléphone, bascule et arrêt) ;
  `ControllerMotionTest` (dernières valeurs, capteur absent, arrêt) ;
  `PlayerActivityTest` (valeurs données avant chaque image, une fois de plus
  pour rendre la main au téléphone).

Écarts restants : sens des axes d'une manette et ressenti de la vibration à
vérifier avec de vraies manettes (DualSense, DualShock 4, Switch Pro).

## Petits écarts, lot E-6 : progression des succès dans les états

Un état de sauvegarde garde maintenant l'avancement de chaque succès
RetroAchievements (le runtime de rcheevos) ; le charger le remet tel qu'il
était, au lieu de tout recommencer (la bêta recommençait aussi).

- **Format** (`android/src/main/cpp/media/state_progress.c`) : l'état du core,
  puis la progression et un pied de page de 12 octets (longueur, puis
  « RAVENRA1 »). Java et `StateSlotRepository` ne changent pas : les octets
  restent opaques. **Les états d'avant se chargent comme avant** (sans pied de
  page, les succès recommencent) ; sans compte ni jeu identifié, l'état reste
  celui du core seul. L'annulation d'un chargement remet aussi la progression.
- **Module C** : `raven_achievements_progress_size`, `_save_progress`,
  `_load_progress` (`rc_client_serialize_progress_sized` /
  `rc_client_deserialize_progress_sized`) ; une progression illisible est
  refusée et les succès recommencent. Le redémarrage de la console garde
  `rc_client_reset`. En Hardcore, les états restent interdits.
- Tests : `test_state_progress.c` (pied de page, états sans lui, longueur
  impossible, faux pied de page, progression vide) ; `test_achievements.c`
  (taille, écriture, relecture, départ à zéro, octets illisibles refusés, le
  succès se débloque ensuite) ; ASan/UBSan propre.

Les petits écarts relevés après RA-3 et K (progression dans les états,
avatars des classements, bandeaux sur le skin, skins en paysage, boutons sur
l'image en portrait, vibration et capteurs de la manette) sont ainsi traités
par les lots E-1 à E-6 ; il reste à les vérifier sur téléphone.

## Retours d'essai : grille de la bibliothèque, bandeau du lancement, glissement entre consoles, jaquettes

Trois défauts signalés par le propriétaire sur téléphone, et une demande.

- **Tuiles qui se décalent en faisant défiler la bibliothèque.** Le titre
  d'une tuile tient sur une à trois lignes, donc les tuiles d'une même rangée
  n'avaient pas la même hauteur. Or `GridView` place la rangée suivante sous
  la **dernière** tuile de la rangée, et non sous la plus haute ; en remontant,
  il aligne même les tuiles par le bas. Les tuiles se chevauchaient ou se
  décalaient, surtout après le chargement d'une jaquette, qui relance la mise
  en page. `RavenLibraryAdapter` donne maintenant aux titres d'une rangée la
  hauteur, en pixels, du plus haut d'entre eux (mesurée une fois par titre et
  par largeur de colonne). La rangée garde la hauteur de sa plus haute tuile,
  comme dans la grille de la bêta (`LazyVerticalGrid`) : une rangée de titres
  courts reste basse.
- **Étoile, puis icône du jeu, dans le bandeau du lancement.** Le bandeau
  RetroAchievements s'ouvrait aussitôt avec l'étoile, puis l'icône du jeu
  la remplaçait une fois téléchargée. Comme la bêta (`showUnlocks`), le bandeau
  attend maintenant son image (`BadgeCache.fetch`) : jusqu'à 2 s
  (`AchievementOverlay.BADGE_WAIT_MILLIS`). Si elle n'est pas arrivée, s'il n'y
  en a pas ou si son téléchargement échoue, il s'ouvre avec l'étoile et la
  garde : il ne change plus pendant qu'il est affiché. Le carillon d'un succès
  sonne à l'ouverture du bandeau, comme dans la bêta. Quand les bandeaux de
  succès sont désactivés, il sonne seul, comme avant.
- Tests : `RavenLibraryAdapterTest` (rangées alignées en descendant puis en
  remontant, titre long entier, rangée de titres courts plus basse, recherche
  qui regroupe les rangées) ; `AchievementOverlayTest` (icône avant l'ouverture,
  étoile gardée après 2 s, ouverture immédiate sans image à télécharger ou après
  un échec, rien d'affiché si le jeu se ferme pendant le téléchargement).

- **Changer de console en glissant** (demande du propriétaire : appuyer sur
  les icônes était pénible). La bibliothèque de la bêta était un
  `HorizontalPager` ; la version native n'avait que les icônes. `PageSwipe`
  entoure maintenant la grille :
  - un glissement horizontal fait suivre la page au doigt ;
  - au-delà d'un tiers de la largeur, ou avec un geste rapide, la page part
    et celle de la console voisine arrive de l'autre côté (Tous, Game Boy,
    Game Boy Color, Game Boy Advance, selon les consoles présentes) ;
    sinon elle revient en place ;
  - sans page voisine de ce côté, elle ne cède qu'un peu ;
  - un geste surtout vertical reste le défilement de la grille, qui garde la
    main une fois lancé ;
  - les icônes tournent toujours les pages, et une nouvelle page s'ouvre sur
    ses premiers jeux.

  Écart avec la bêta : pendant le glissement, la page voisine n'apparaît pas
  encore à côté. Elle arrive à la fin du geste, alors que le pager de la bêta
  montrait les deux pages côte à côte.
  Test : `HomeActivityTest.swipingSidewaysTurnsTheConsolePagesAsInTheBeta`
  (vers la gauche puis la droite, bords, geste court et lent, geste vertical,
  icônes).

- **Jaquettes floues, et pas au même format dans la fiche du jeu.** Les deux
  problèmes viennent de la version native.
  - À l'import, le décodage par puissances de 2 descendait *sous* la taille
    visée : une image de 1000 × 1400 était gardée en 250 × 350.
  - La grille relisait ensuite la jaquette en 256 px au plus, encore divisée
    par 2 (180 × 250 pour une jaquette de 360 × 500), puis étirée sur une
    tuile d'environ 430 px.
  - La fiche du jeu la chargeait en 160 px, dans un cadre fixe de 90 × 105 dp.
  - Correctifs dans `CoverRepository` :
    - les jaquettes sont gardées jusqu'à 768 px de côté ;
    - le décodage s'arrête à la plus grande réduction qui reste au-dessus de
      la taille voulue, puis un seul redimensionnement filtré donne la taille
      exacte ;
    - le nouveau `load(files, key, largeur, hauteur)` rend l'image à la taille
      réelle de son cadre en pixels (recadrée pour le remplir, jamais
      agrandie).
  - `CoverArtwork` (Java) reprend le composant du même nom de la bêta :
    - image arrondie à 10 dp, liseré par-dessus ;
    - sans image, pictogramme et nom de la console ;
    - il sert à la grille, à la liste et à la fiche.
  - Comme le propriétaire l'a demandé, la fiche montre la jaquette **aux
    dimensions exactes d'une tuile de la bibliothèque** (la largeur de la
    colonne sur 124 dp). La bêta avait un cadre plus petit de 90 × 105 dp.
  - Les jaquettes importées avant ce correctif restent dans leur ancienne
    taille : il faut les choisir à nouveau pour qu'elles soient nettes.
  - Tests :
    - `CoverRepositoryTest`, en Java pur : 768 px, décodage jamais sous la
      taille voulue, image recadrée sans agrandissement ;
    - `CoverImageQualityTest`, sur de vraies images : 549 × 768 gardés pour
      1000 × 1400, tuile servie à sa taille, petite image non agrandie ;
    - `RavenLibraryAdapterTest` : une tuile reçoit sa jaquette à sa taille en
      pixels, coins arrondis ;
    - `HomeActivityTest` : jaquette de la fiche aux dimensions d'une tuile.

À vérifier sur téléphone : défilement rapide d'une grande bibliothèque avec
jaquettes, en portrait et en paysage ; bandeau du lancement avec et sans réseau ;
glissement entre consoles avec la navigation par gestes d'Android (le retour par
le bord de l'écran reste au système) ; netteté d'une jaquette choisie à nouveau,
dans la grille et dans la fiche.

## Régression image et son, et leur synchronisation, lot AV-1

Demande du propriétaire : un test de régression de l'audio, de la vidéo et de
leur synchronisation. Les tests existants vérifiaient chaque pièce seule :
l'horloge et le calage sur l'écran (`raven_frame_pacer`), le pipeline audio
(rééchantillonnage, contrôle du débit, dérive d'horloge), le pilote GLES2 (y
compris au pixel près). Ils passent tous (ctest 16/16).

Nouveau test **`raven_native_av_sync`** (`tests/hardware/test_av_sync.c`), de bout
en bout et en temps virtuel, donc déterministe :

- Les vrais cores RavenCore GB, GBC et GBA tournent par le frontend C.
- Le calage sur l'écran les cadence comme le fait `drawFrame` du pont Android : rythme, vitesse transmise au son, images dues.
- Le son passe par le pipeline et le pilote AAudio jusqu'à un faux appareil qui tire des blocs de 192 trames à sa propre horloge.
- Les ROM sont construites par le test, en GB et en GBA. Elles jouent un La (131 072 / 298 Hz, soit 439,8 Hz) sur le canal carré 2 : on vérifie ainsi la hauteur du son et l'absence de trous.

Ce qui est vérifié, une fois le calage acquis :

- **Image**, à 60 Hz : une image du jeu par rafraîchissement, aucune répétée ni sautée. À 120 Hz, une image tous les deux rafraîchissements. Une image affichée par image jouée.
- **Son** : le nombre d'échantillons par image correspond au débit du core. L'appareil n'est jamais à court (craquement) et la file n'est jamais pleine (échantillons perdus). Le La est à la vitesse du jeu à 0,5 % près, sans trou de plus de 10 ms.
- **Synchronisation** : le délai entre une image affichée et son son entendu (la file devant l'appareil) reste sous le budget du pilote (64 ms plus une image) et ne dérive pas d'1 ms entre les deux dernières fenêtres de 5 s.

Résultats (après calage ; la latence est mesurée juste après chaque image écrite) :

| Scénario | Images répétées / sautées | Latence établie | Coupures / file pleine | La mesuré (attendu) |
| --- | --- | --- | --- | --- |
| GB, 60 Hz, sinc | 0 / 0 | 44,2 ms | 0 / 0 | 441,75 Hz (441,85) |
| GB, 59,94 Hz, appareil +300 ppm | 0 / 0 | 42,5 ms | 0 / 0 | 441,31 Hz (441,40) |
| GB, 60 Hz, pause d'1 s à 8 s | 0 / 0 | 44,3 ms | 0 / 0 | 441,66 Hz (441,85) |
| GBC, 120 Hz | 1 sur 2, voulu / 0 | 40,2 ms | 0 / 0 | 441,73 Hz (441,85) |
| GBA, 60 Hz, sinc puis linéaire | 0 / 0 | 44,2 ms | 0 / 0 | 441,75 et 441,79 Hz (441,85) |
| GBA, 60 Hz, appareil −3000 ppm | 0 / 0 | 61,0 ms | 0 / 0 | 441,65 Hz (441,85) |
| GBA, 90 Hz (pas un multiple) | 1 sur 3, non calé / 0 | 41,5 ms | 0 / 0 | 439,77 Hz (439,84) |
| GBA, 120 Hz, appareil +300 ppm | 1 sur 2, voulu / 0 | 38,4 ms | 0 / 0 | 441,74 Hz (441,85) |

Lecture des résultats :

- **Établissement de la latence.** Au lancement et après une pause, la file part presque vide (environ 29 ms de latence). Le contrôle du débit la porte à son niveau en une vingtaine de secondes : il ne corrige que de 0,5 % au plus, avec une constante de temps d'environ 6 s. L'écart image et son change ainsi d'environ 15 ms, lentement, puis ne bouge plus. Ce n'est pas une dérive.
- **Endurance**, avec `RAVEN_AV_SECONDS=600`, dix minutes par scénario : latence à 44,34 ms puis 44,33 ms entre la 590e et la 600e seconde, La à 441,84 Hz pour 441,85, aucune coupure.
- **Appareil 0,3 % plus lent** : la file se stabilise vers les quatre cinquièmes de sa taille, à 61 ms, sans la remplir. Au-delà de 0,5 %, limite du contrôle du débit, elle se remplirait ; les horloges des téléphones s'écartent de bien moins.
- **90 Hz** : l'écran ne divise pas le rythme de la console. Le jeu suit alors l'horloge de la console : une image sur trois est répétée, ce qui est inévitable sans changer la fréquence de l'écran (l'application demande 60 ou 120 Hz quand l'écran le permet).
- **Vrais jeux**, en local seulement, ROM jamais publiées : The Legend of Zelda, Pokémon Rubis, Pokémon Émeraude et GTA (GBA). Chacun a tourné 60 s, avec une pause à 30 s. Les mesures sont les mêmes que ci-dessus : 0 coupure, 0 file pleine, aucune image répétée ni sautée, latence établie à 44,2 ms. La synchronisation dépend du rythme et du nombre d'échantillons par image, que le core fixe exactement, et non du jeu. Le test accepte ces ROM en arguments supplémentaires.
- **Sanitizers** : le test passe sous AddressSanitizer et UBSan avec des mesures identiques.
- **Coût** : sur la machine de test, le core GB coûte environ 1,9 ms par image même sur une boucle vide, contre 0,33 ms pour le core GBA (qui saute sans doute les boucles d'attente). C'est sans effet sur la synchronisation, mais c'est à surveiller sur les téléphones modestes avec les images préventives : c'est du ressort de RavenCore.

Limites : le test reproduit la boucle de `drawFrame` (le pacer, la vitesse du son, les images dues) au lieu d'appeler le pont JNI, qui demande Android. L'appareil est simulé : la latence réelle d'AAudio, le Bluetooth et l'écoute restent à vérifier sur téléphone.

## RetroAchievements façon RetroArch, lot RA-4 : popups, coin en bas à droite, progression réparée

Demande du propriétaire : le même style que RetroArch pour le popup du
lancement d'un jeu, celui des succès et celui de la progression, placé en bas
à droite, et la réparation du popup de progression. Comme pour le thème
NVIDIA, seules des **valeurs** de RetroArch sont reprises (dimensions,
durées, couleurs, ordre des éléments, textes affichés), relevées dans
`gfx/widgets/gfx_widget_achievement_popup.c`,
`gfx/widgets/gfx_widget_leaderboard_display.c`, `gfx/gfx_widgets.h` et
`cheevos/cheevos.c` ; le code Java est écrit pour RavenEmu. Voir
[l'attribution](../legal/attributions/retroarch.md).

- **Popup** (`AchievementPopup.java`) : un badge carré sur un fond noir à 75 %
  (`DEFAULT_BACKDROP`), puis un fond aussi haut avec deux lignes de la même
  police, la première grise (`TEXT_COLOR_FAINT`, `#888888`), la seconde blanche
  (`TEXT_COLOR_INFO`, `#EEEEEE`). Le popup fait quatre lignes de haut ; sa
  largeur est celle de la plus longue ligne, plus deux tiers de ligne de chaque
  côté. Sans badge, un trophée blanc, comme l'icône de RetroArch. Le cadre doré
  et l'étoile de la bêta disparaissent.
- **Mouvement** (`AchievementOverlay.java`) : le badge glisse depuis le bord
  de l'image, puis le texte se déplie à sa droite en arrivant d'une demi-largeur
  vers la gauche, pendant que le popup se décale d'une demi-largeur pour finir
  centré ; il reste la durée des réglages, se replie, puis repart. Chaque étape
  dure 330 ms (`MSG_QUEUE_ANIMATION_DURATION`), avec un ralenti quadratique
  (`EASING_OUT_QUAD`) ; huit popups attendent au plus (`CHEEVO_QUEUE_SIZE`).
  Les positions sont des translations et un découpage : rien n'est remis en
  page pendant le mouvement. En haut ou en bas, centré, selon le réglage :
  ce sont les ancres `TOPCENTER` et `BOTTOMCENTER` de RetroArch. En bas, le
  popup s'arrête au-dessus de ce qu'affiche le coin.
- **Textes** (`AchievementNotices.java`) : au lancement, le titre du jeu en
  gris puis « Vous avez débloqué 3 succès sur 42 » (« Ce jeu n'a aucun
  succès » sans succès) ; un déblocage, « Succès débloqué » puis « Titre (5) »,
  avec ses points ; une fin de jeu, le titre du jeu puis « Jeu terminé » ou
  « Jeu maîtrisé ». Les avis de session (hors ligne, erreurs, classements)
  gardent leurs textes, dans le même popup.
- **Coin en bas à droite** (`AchievementCorner.java`), comme le widget
  `leaderboard_display` : de bas en haut, les suivis de classement (leur valeur
  dans une boîte, chiffres de largeur fixe, quatre au plus), les défis (une
  rangée de badges en couleur depuis la droite, huit au plus), puis la
  progression d'un succès : son badge verrouillé et « 3/10 », sur fond noir à
  75 %. L'espacement est un soixante-quatrième du petit côté de l'écran, comme
  dans RetroArch ; le badge mesure quatre espacements, la boîte cinq. Les
  défis et suivis quittent le bas centré.
- **Module C** (`frontend/src/achievements.c`) : comme RetroArch, un défi
  porte le badge en couleur (le succès est sur le point d'être gagné) et une
  progression le badge verrouillé ; l'événement `game` donne le nombre de
  succès déjà débloqués à la place du nombre de succès non pris en charge, que
  rien n'affichait ; `progress_hide` nomme le succès quand un reset, un état
  chargé ou la fin du jeu masque la progression, et rien quand c'est la minuterie
  de deux secondes du SDK.

**Défauts de la progression réparés.**

1. *La durée réglée était ignorée au-delà de deux secondes.* La minuterie du
   SDK (`rc_client`, deux secondes après la dernière hausse) envoie un
   `progress_hide` global que l'application suivait : réglée sur 5 s, la
   progression disparaissait à 2 s. Le coin ignore désormais ce masquage sans
   succès et garde sa propre durée (**Paramètres → RetroAchievements →
   Durée des progressions**, 2 s par défaut, comme RetroArch), relancée à
   chaque hausse ; un masquage qui nomme le succès (reset, état chargé) s'applique
   aussitôt.
2. *L'étoile clignotait.* Une progression dont le badge n'était pas encore
   téléchargé s'affichait d'abord avec l'étoile, puis avec le badge ; quand deux
   succès progressaient ensemble, le SDK alternait entre eux et le badge
   clignotait à chaque changement. Comme RetroArch (`CHEEVO_BADGE_HOLD_FRAMES`),
   une mise à jour dont le badge est nouveau attend celui-ci, ce qui est affiché
   restant tel quel, 500 ms au plus ; le trophée n'apparaît qu'après, ou tout de
   suite si le badge ne peut pas venir. Les badges déjà vus restent en cache :
   revenir au premier succès ne coûte rien. Un badge n'est demandé qu'une fois
   à la fois, même si la progression change à chaque image. Un défi garde sa
   place sans être dessiné tant que son badge arrive.
3. *La carte sautait.* La progression était empilée sous le bandeau du haut et
   descendait de toute la hauteur du bandeau quand un succès s'ouvrait. Elle a
   maintenant sa place, en bas à droite.
4. *La documentation décrivait un tour de rôle de la bêta* (`RetroProgressNotifications`),
   jamais porté dans l'application native. Le wiki décrit maintenant ce que fait
   l'application : le succès le plus proche de son objectif, choisi par le SDK,
   comme dans RetroArch.

Tests : `AchievementPopupTest` (quatre lignes, couleurs, badge depuis le bord
puis texte qui se déplie jusqu'au centre, départ et arrêt en bas, ligne trop
longue gardée dans l'image) ; `AchievementOverlayTest` (un popup à la fois,
durées de 330 ms, file de huit, popups du bas au-dessus du coin, progression en
bas à droite qui garde la durée réglée malgré la minuterie du SDK, masquée par
un reset, empilement suivis-défis-progression, limites de quatre et huit,
attente du badge sans trophée, défi qui garde sa place) ;
`AchievementNoticesTest` (textes de RetroArch) ; `test_achievements.c`
(badges en couleur et verrouillé, nombre de succès débloqués, masquage nommé
après un reset).

Écarts restants : RetroArch place ses popups en haut à gauche par défaut et
offre six ancres ; RavenEmu garde son réglage en haut ou en bas, centré
(les six ancres sont venues avec le lot RA-5, ci-dessous). Les
avis de session de RetroArch passent par sa file de messages, d'un autre
style ; RavenEmu les montre dans le même popup. La rareté d'un succès
(« Succès rare débloqué – 2,5 % ») et le nom du compte sous une fin de jeu ne
sont pas affichés. Les boîtes « RA … » et « ! RA ! » du coin (chargement,
déconnexion) ne sont pas reprises : les avis hors ligne restent des popups.
Sous Robolectric, un animateur va à sa fin d'un coup ; les étapes sont donc
vérifiées par leur géométrie, fraction par fraction. À vérifier sur téléphone
(portrait, paysage, skin, gestes et trois boutons) avant toute publication.

## RetroAchievements, lot RA-5 : les six positions de RetroArch

Demande du propriétaire : les six positions de RetroArch pour les popups.

- **Réglage** (`AchievementSettings.java`) : `TOP_LEFT`, `TOP`, `TOP_RIGHT`,
  `BOTTOM_LEFT`, `BOTTOM`, `BOTTOM_RIGHT`, dans l'ordre de
  `cheevos_appearance_anchor` de RetroArch. `top` et `bottom`, les deux valeurs
  enregistrées jusqu'ici, sont les positions centrées : aucun choix existant ne
  change. Une valeur inconnue revient à `TOP`. La position par défaut reste en
  haut au centre ; celle de RetroArch est en haut à gauche.
- **Page Paramètres → RetroAchievements** : **Position des popups**, six
  choix (« En haut à gauche » … « En bas à droite »). La description de
  **Progression des succès** ne parle plus d'un tour de rôle que l'application
  ne fait pas.
- **Géométrie** (`AchievementPopup.java`), comme `ANCHOR_LEFT`,
  `ANCHOR_CENTER` et `ANCHOR_RIGHT` : à gauche, le badge reste à une marge du
  bord pendant que le texte se déplie ; au centre, le popup se décale d'une
  demi-largeur de texte et finit centré ; à droite, il part contre le bord et se
  décale de toute la largeur du texte, pour finir contre le bord droit. La marge
  est la même horizontalement et verticalement (marge automatique de RetroArch).
  En bas, quelle que soit la colonne, le popup s'arrête au-dessus de ce
  qu'affiche le coin en bas à droite.
- Tests : `AchievementSettingsTest` (six valeurs gardées, anciennes valeurs lues,
  ancre par défaut) ; `PlaybackSettingsActivityTest` (six choix, enregistrement) ;
  `AchievementPopupTest` (gauche immobile, droite contre le bord pendant et après
  le dépliage, bas à droite) ; `AchievementOverlayTest` (chaque position place le
  popup suivant à sa place).

Écart restant : RetroArch permet aussi une marge manuelle
(`cheevos_appearance_padding_h` et `_v`) ; RavenEmu garde la marge automatique.
