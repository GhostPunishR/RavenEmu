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
Le module distinct `androidNative/` raccorde Java/JNI, les cores Libretro ARM64
et un premier rendu natif OpenGL ES 2, sans modifier `androidApp/`.
L'audio AAudio, les sauvegardes privées et la bibliothèque ont été ajoutés
par les tranches suivantes. Les services avancés de la bêta restent à porter.
Voir [androidNative/README.md](../androidNative/README.md).

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

## Sixième tranche : jaquettes locales et bibliothèque illustrée

- Sélection d'une image par l'utilisateur depuis le sélecteur SAF Android ;
  aucune source distante ni jaquette propriétaire intégrée.
- Décodage borné : fichier source inférieur ou égal à 8 MiB, dimensions
  comprises entre 16 et 4096 px et 16 Mpixels au maximum ; normalisation en
  PNG de 512 px de côté au plus avec écriture atomique, dans le dossier privé
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
  module `androidNative`. Le début d'intégration de
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
  une PR qui ne modifie que `androidNative/` ou `settings.gradle.kts`.
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

[Provenance, périmètre et distribution](../third_party/retroarch/README.md).
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
