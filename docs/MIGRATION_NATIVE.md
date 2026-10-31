# Migration native de RavenEmu

> Réorganisation du 29 octobre 2026 : `android/` (application, JNI et pilotes matériels), `frontend/` (C et Libretro), `tests/hardware/` (tests matériels), `legal/attributions/` (provenance). Le nouvel APK de développement `com.ravenemu.dev` est isolé des données de la bêta et de l'ancienne prévisualisation, sans migration automatique.

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

Écarts restants pour **RA-3** (après RA-3a et RA-3b) : verrous du mode Hardcore (pas de chargement
d'état, pauses limitées) et son activation, liste des succès, classements,
indicateurs de défi et de suivi, carte du compte dans le menu de jeu, journal
hors ligne (fait en RA-3a, ci-dessous), progression des succès dans les
états.
La recréation du lecteur (rotation) reconnecte le compte et ré-identifie le
jeu. Validation sur téléphone et connexion à un vrai compte à faire.
