# Journal des modifications de RavenEmu

## Non publié

### Ajouté

- Première session iOS raccordée à l'interface partagée : import Fichiers de ROM GB/GBC/GBA, bibliothèque persistante par SHA-256, commandes tactiles, images natives et audio AVAudioEngine. Réglages audio, affichage et commandes persistés ; les services absents sont masqués.
- iOS : sauvegarde batterie atomique périodique et avant pause/suspension, quatre emplacements d'état, import `.sav` avec confirmation et export au nom de la ROM. Pause/menu et interruptions relâchent les commandes.
- CI iOS : construction de l'application Xcode complète, XCTest sur simulateur avec programmes synthétiques et artefact `ravenemu-ios-simulator`. Aucune IPA signée pour iPhone ; audio, performances et parcours sur appareil restent à valider.

- RetroAchievements : états de synchronisation par succès (local, en attente, confirmé), compteur des demandes en attente, recherche, tris par progression/date, dates de déblocage et activité en jeu (Rich Presence).
- RetroAchievements : consultation des classements (20 meilleurs scores ou autour du compte), chargement/réessai et dernier résultat avec score, record personnel et rang. Les soumissions nécessitent le Hardcore, qui reste verrouillé en attente de validation externe.
- RetroAchievements : réglages persistants des popups (activation des succès et des progressions séparée, haut/bas, durées, son facultatif), bilan de jeu terminé avec image et distinction Softcore/Hardcore.

- **Jouer à deux sur un téléphone (câble link).** Depuis la fiche d'un jeu, **Jouer à deux** lance un second jeu en même temps que le premier, relié par un câble link virtuel : deux jeux Game Boy ou Game Boy Color, ou deux jeux Game Boy Advance, différents. Un seul jeu est affiché, en grand ; le bouton **⇄ Joueur 2** de la barre passe à l'autre, qui continue de tourner. Le jeu affiché reçoit les commandes tactiles et la manette, et seul son son est joué. Chaque jeu garde sa propre sauvegarde. Les états instantanés ne sont pas proposés à deux. Le câble vient de RavenCore 0.5.0 (`ravenemu_link_*`) ; voir la page [Jouer à deux](wiki/Jouer-a-deux.md).
- Capteurs de cartouche sur Android : le gyroscope du téléphone pour WarioWare: Twisted!, son accéléromètre pour Yoshi Topsy-Turvy, Koro Koro Puzzle et les cartouches Game Boy MBC7 (Kirby Tilt 'n' Tumble), son capteur de lumière pour Boktai 1 à 3. Les axes suivent l'orientation de l'écran. Les capteurs ne sont écoutés que pendant la partie, et seulement ceux de la cartouche chargée.
- Réglages, page Interface : **Capteurs du téléphone** (activé par défaut), **Soleil automatique (Boktai)** et un niveau de soleil manuel, de 0 à 100 %. Le niveau manuel s'applique quand le soleil automatique est coupé ou que le téléphone n'a pas de capteur de lumière. En automatique, il faut la lumière du jour : de 1 000 lx (une pièce bien éclairée, aucun soleil) à 100 000 lx (plein soleil), sur une échelle logarithmique.

### Corrigé

- RetroAchievements : la session d'un jeu s'ouvre au lancement et se ferme en quittant le lecteur. La bibliothèque n'affiche plus les succès du dernier jeu, et **Réessayer** hors partie reconnecte le compte sans rouvrir de session pour ce jeu. Importer une ROM n'ouvre plus de session. Le tri et le nombre de succès affichés repartent de zéro pour chaque jeu.
- RetroAchievements : un refus de connexion affiche la raison donnée par le serveur (mot de passe incorrect, compte bloqué…) au lieu d'un message générique. Sans réponse du serveur, le message indique de vérifier le réseau.
- Hardcore : quand le redémarrage du jeu échoue et que l'application repasse en Softcore, le réglage **Hardcore** est aussi désactivé. Le lancement suivant ne redemande plus le Hardcore.
- RetroAchievements : l'application ne plante plus quand le client RetroAchievements se ferme (changement de jeu, déconnexion, retour à la bibliothèque) alors que le dernier succès d'un jeu vient d'être confirmé pendant une pause. Le défaut est dans le SDK rcheevos épinglé ; le même correctif local que RavenEmu PC est appliqué à la compilation (`native/cmake/rcheevos_safety.cmake`).
- RetroAchievements : le journal des succès hors ligne ne garde plus une entrée par tentative d'envoi. Une seule entrée par succès est conservée, la plus récente. La réponse « User already has… » confirme le succès, comme dans rcheevos, et une confirmation retire toutes les tentatives du même succès, y compris celles déjà enregistrées par les versions précédentes. Le compteur « en attente » se vide donc après la synchronisation, et le journal ne se remplit plus de tentatives périmées.
- RetroAchievements : le pont lit le véritable objet de résultat des classements, copie les données avant leur libération et conserve le rang reçu. Les notifications d'un compte ou d'un jeu précédent sont retirées sans casser la file d'affichage.
- Hardcore préparé : identifiant HTTP à version numérique, restauration d'état sérialisée avec les changements de mode et retour en Softcore si le redémarrage requis échoue. Le verrou d'approbation reste fermé.

- RetroAchievements : le popup garde la valeur de progression lisible même avec un titre de succès long. Les indicateurs sont effacés à la déconnexion ou au remplacement de la session, même sans événement de disparition du SDK.
- RetroAchievements : les hausses mesurées des différents succès passent à tour de rôle dans le popup, avec deux secondes par tour. Un succès fréquemment mis à jour ne masque plus les autres et les valeurs déjà atteintes dans la session ne relancent plus continuellement la notification.
- RetroAchievements : une reconnexion crée un nouveau journal de session après la révocation de la déconnexion ; les anciens callbacks ne peuvent pas réécrire le journal.
- Fiche d'un jeu : elle s'ouvre directement à sa hauteur, avec **Jouer** et **Jouer à deux** juste sous la jaquette. Elle ne remonte plus jusqu'en haut de l'écran quand on la tire, et le premier appui sur un bouton compte, même pendant que la fiche se replace. Elle se ferme toujours en la faisant glisser vers le bas.
- Export `.sav` : le nom proposé reprend le nom complet de la ROM en remplaçant son extension par `.sav`, en conservant les espaces, accents, parenthèses et noms longs.
- Sauvegardes : lancements, imports et exports sont exclus mutuellement jusqu'à la fin de leur mise à jour d'interface, y compris pendant le sélecteur de fichiers. Une paire link encore en mémoire est libérée avant un transfert.
- Miniatures des états : contrôle des dimensions PNG avant décodage et réduction de résolution ; une miniature importée invalide est ignorée sans perdre son état associé.
- Manettes : plusieurs touches ou axes commandant le même bouton restent actifs jusqu'au relâchement de leur dernière source. Les touches sont interceptées par l'API publique Window.Callback.
- Vérifications : les tests communs sont activés sur JVM et exécutés en CI avec Android Lint.

- GBA : The Legend of Zelda (Classic NES Series) affichait « GAME PAK ERROR » au démarrage. Il démarre avec RavenCore 0.3.0, qui laisse vide la zone SRAM des cartouches à EEPROM. Les autres jeux de la série font le même test.
- GBA : dans Pokémon Émeraude, le brouillard d'une arène masquait entièrement le décor. RavenCore 0.3.1 mélange les objets semi-transparents au décor même dans une fenêtre qui coupe les effets spéciaux, comme la console.
- GBA : GTA Advance restait sur un écran noir après le choix du nom d'une nouvelle partie. RavenCore 0.4.1 ne relance plus un DMA déjà actif quand le jeu réécrit son contrôle.
- GBA : l'heure réglée par un jeu sur l'horloge de la cartouche revenait à celle du téléphone au lancement suivant et après une réinitialisation. RavenCore 0.4.1 la garde dans la sauvegarde `.sav`, qui gagne pour cela 16 octets sur les jeux à horloge (Pokémon Rubis, Saphir et Émeraude, Boktai), au format de mGBA. Les anciennes sauvegardes se chargent comme avant.
- Sauvegarde perdue après une réinitialisation : sauvegarder dans le jeu, réinitialiser avant l'enregistrement automatique puis quitter perdait la sauvegarde, sur GB, GBC et GBA. Avec RavenCore 0.4.1, une écriture en attente reste à enregistrer après la réinitialisation.

### Modifié

- RavenCore épinglé au commit `241f56c3564ca1389813753d5c13dd931771fe40` (base 0.7.1, [correctif RTC Pokémon/CFRU #28](https://github.com/GhostPunishR/RavenCore/pull/28), sans nouveau tag). Détection de certains pilotes d'horloge sans marqueur Seiko, corrections du protocole RTC, des modes 12/24 heures et des écritures de calendrier. API C et formats d'états inchangés ; les anciennes sauvegardes Flash sont conservées, avec ajout des 16 octets RTC pour les cartouches nouvellement détectées. Un ancien état créé sans RTC peut nécessiter de reprendre depuis la sauvegarde normale du jeu. L'intégration est vérifiée sur cartouches synthétiques ; Unbound reste à essayer sur appareil avec la ROM exacte.

- Barre de diagnostic du lecteur, seul ou à deux : les mesures sont écrites en blanc, et les FPS n'indiquent plus que la cadence obtenue (« FPS 59.7 »), sans la cadence visée.
- RavenCore épinglé à la version 0.7.1, commit `8f682a121b0ce2dc1d5c4777fc602e8f449110b5`. Le cœur GBA relit ses registres d'E/S et son port série comme la console, lève le drapeau de H-Blank à son instant, sort du Halt du BIOS 27 cycles après l'IRQ, comme la console, et remet à zéro les registres du son quand le jeu le coupe. La lecture de la mémoire suivie par RetroAchievements copie par blocs : environ 16 fois plus rapide sur GBA, aux mêmes octets. Les états GBA passent au format 16 et relisent les formats 15 à 11 ; un état GBA enregistré par cette version ne se recharge plus dans une version antérieure. L'API C ne change pas.
- Avec RavenCore 0.6.0, le câble link relie Pokémon Émeraude et Rubis ou Saphir : le BIOS GBA laisse passer les IRQ pendant IntrWait et ses longues copies, comme sur console, si bien que Rubis ne perd plus le compte de ses transferts. Les IRQ et les timers GBA prennent effet au cycle de la console. Les états GBA passent au format 15 et relisent les formats 14 à 11 ; un état GBA enregistré par cette version ne se recharge plus dans une version antérieure. L'API C ne change pas.
- Avec RavenCore 0.5.2, les fonctions ArcTan, ArcTan2 et Div du BIOS GBA rendent les valeurs de la console : les jeux qui calculent un angle avec ArcTan2 obtiennent exactement celui de la GBA.
- Avec RavenCore 0.5.1, les multiplications GBA (MULS, UMULLS, SMLALS…) laissent dans le drapeau C la valeur que produit le multiplieur de la console.
- Avec RavenCore 0.5.0, le cœur GBA émule le tampon de préchargement de la cartouche et compte le temps du processeur au cycle près : les jeux tournent à la cadence de la console, au prix d'environ 5 % de calcul en plus sur Pokémon Émeraude. Il émule aussi le câble link GBA (modes multijoueur et normal). L'API C reste en version 0 et gagne les fonctions `ravenemu_link_*`. Les états GBA passent au format 14 et relisent les formats 13, 12 et 11 ; un état GBA enregistré par cette version ne se recharge plus dans une version antérieure.
- Avec RavenCore 0.4.1, le cœur GBA était devenu environ 12 % plus rapide ; l'API C gagnait depuis la 0.2.0 les fonctions des capteurs de cartouche. Les états GB/GBC restent au format 11.
- Pont natif : RavenCore est déclaré à FetchContent sous le nom `ravencore`, et non plus `ravenemu_cores`, l'ancien nom de RavenCore. Ses fichiers de compilation passent donc de `_deps/ravenemu_cores-*` à `_deps/ravencore-*`, et le projet iOS cherche ses bibliothèques dans `_deps/ravencore-build`. L'option `RAVENEMU_BUILD_TESTS`, retirée de RavenCore 0.4.0, n'est plus réglée.
- Pont natif : `native/cmake/NeutralBridge.cmake` est retiré. RavenCore expose désormais directement les noms `ravenemu_*` : JNI et Swift utilisent son header tel quel, et le dossier `include/` lu par Xcode et la CI ne change pas.
- Code : `MainActivity.kt` et `RavenEmuApp.kt` sont découpés en fichiers par responsabilité (lecteur, emplacements d'état, transferts de sauvegardes, jaquettes, RetroAchievements, composants de la bibliothèque), sans changement de comportement, de textes ni de formats.

## v0.1.0 - bêta - 2026-10-15

Première base du dépôt autonome RavenEmu. Cette version rassemble l'application multiplateforme et ses outils ; les fonctionnalités ci-dessous décrivent le contenu livré, sans affirmer une validation sur tous les jeux ou appareils.

### Application et plateformes

- Interface Compose Multiplatform partagée entre Android et iOS : thème sombre, bibliothèque, navigation par console, recherche, grille/liste, fiche de jeu, paramètres et pages légales.
- Android 8.0 minimum, application nommée RavenEmu, identifiant `com.ravenemu.preview`, paquets `com.ravenemu.android` et `com.ravenemu.shared`.
- Hôte iOS SwiftUI et projet XcodeGen, framework `RavenShared`, adaptateur Swift et compilation native pour simulateur/appareil Apple. Le lecteur iOS complet, les imports et la persistance restent à connecter.

### Bibliothèque et import

- Import Android d'un fichier ou d'un dossier de ROM Game Boy, Game Boy Color et Game Boy Advance via le sélecteur système, avec parcours borné et récapitulatif.
- Validation de la console et du contenu, rejet des fichiers invalides ou trop volumineux, gestion des doublons, copie privée persistante et index JSON atomique.
- Identité des jeux par SHA-256 du contenu ; l'extension seule ne décide pas de la console GB/GBC.
- Jaquettes manuelles et téléchargement facultatif depuis le catalogue Libretro, désactivé par défaut ; cache et contrôles de provenance.

### Lecteur, audio et commandes

- Moteurs maison GB/GBC/GBA fournis par RavenCore, vidéo ARGB8888 et audio PCM16 via la même API C.
- Cadence à la nanoseconde sur un thread dédié, réutilisation des tampons d'image, rattrapage borné et suspension à la fermeture ou en arrière-plan.
- Rééchantillonnage audio à bande limitée, préremplissage, régulation de dérive et fondu de démarrage ; diagnostic exportable des performances audio/vidéo.
- Commandes tactiles avec croix, diagonales, A/B, Start/Select et L/R pour GBA ; manettes Android et réinitialisation des entrées au changement de cycle de vie.
- Réglages audio, palettes monochromes, luminosité/contraste, correction des couleurs GBC/GBA, diagnostics, taille/écartement/style des commandes et vibration.
- Import, validation, stockage, sélection et rendu portrait des skins `.deltaskin`, avec repli vers les commandes natives ; aucun skin fourni.

### Sauvegardes

- Sauvegardes batterie atomiques par ROM, sauvegarde automatique et restauration au chargement.
- Quatre emplacements d'état avec miniatures ; erreurs et incompatibilités signalées sans écraser silencieusement les données.
- Export/import `.sav` et ZIP `RavenEmu-GB-Saves`, sans ROM, avec vérification d'identité, de tailles et d'empreintes, aperçu des conflits et restauration en cas d'échec d'écriture.
- Import conservé pour les archives historiques. Formats d'états inchangés : GB/GBC 11 et GBA 12, ce dernier relisant le format 11.

### RetroAchievements

- Intégration Android du SDK officiel rcheevos 12.5.0, épinglé au commit `1433173220a7eaede6a9ed7a18e94117be1821e0`.
- Authentification facultative, jeton chiffré avec Android Keystore, tableau de bord, profil, points, badges, filtres, notifications et journal chiffré des demandes de déblocage à reprendre.
- Transport HTTPS borné vers les hôtes autorisés ; aucune ROM ni sauvegarde envoyée au service, aucun mot de passe conservé.
- Mode Softcore disponible ; Hardcore reste conditionné à l'approbation prévue dans le code et désactivé dans cette bêta. Les déblocages synchronisés restent à valider avec un compte et une ROM reconnue sur téléphone.

### Dépôt autonome et construction

- Modules `androidApp/`, `shared/`, `iosApp/`, `native/` et `policy/` déplacés à la racine, sans importer les anciens modules Android.
- Gradle Wrapper complet avec JAR et empreinte de distribution, configuration Gradle et documents légaux nécessaires à la génération des textes intégrés.
- Chemins des builds Android/iOS, des scripts Swift/XcodeGen, des sources légales, de la CI et des artefacts adaptés à la nouvelle racine.
- RavenCore épinglé au commit `bad9ade53076c3c44768cfe2599eeb55c996c434` : moteurs 0.1.2 et API C neutre `ravenemu_*`, après le nettoyage des marqueurs de génération.
- Workflows Android, framework/SDK natif iOS, vérification Swift, tests de liaison native et règles du dépôt ; actions et sources natives épinglées par SHA.
- Kit contributeur, modèles d'issues et de PR, code de conduite, politique de sécurité, guides d'architecture/construction/distribution, licence AGPL-3.0, confidentialité et conditions d'utilisation.
- Sources du site statique et du wiki conservées avec leurs assets ; synchronisation du Wiki GitHub lorsqu'il est accessible et initialisé.

### Compatibilité et limites

- Le déplacement ne change ni l'identifiant Android, ni les préférences et clés de stockage, ni les formats des sauvegardes. Le certificat d'une bêta Debug dépend du build : exporter les sauvegardes avant toute désinstallation.
- Aucun jeu, ROM, BIOS commercial, skin tiers ou secret n'est fourni. Les tests emploient des données synthétiques et des dépendances de test adaptées.
- Android reste en bêta ; les performances et la compatibilité GBC/GBA ainsi que RetroAchievements nécessitent une validation sur appareil. iOS n'est pas encore un lecteur complet et aucun binaire iOS signé n'est distribué.
