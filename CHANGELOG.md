# Journal des modifications de RavenEmu

## Non publié

### Corrigé

- GBA : The Legend of Zelda (Classic NES Series) affichait « GAME PAK ERROR » au démarrage. Il démarre avec RavenCore 0.3.0, qui laisse vide la zone SRAM des cartouches à EEPROM. Les autres jeux de la série font le même test.
- GBA : dans Pokémon Émeraude, le brouillard d'une arène masquait entièrement le décor. RavenCore 0.3.1 mélange les objets semi-transparents au décor même dans une fenêtre qui coupe les effets spéciaux, comme la console.

### Modifié

- RavenCore épinglé à la version 0.4.0, commit `83874b3aaa0c8f212bf8c3284bf7550b4f8b2ec7`. L'API C reste en version 0 : depuis la 0.2.0, elle gagne seulement les fonctions des capteurs de cartouche, que l'application n'utilise pas encore. Les états GBA passent au format 13 et relisent les formats 12 et 11 ; un état GBA enregistré par cette version ne se recharge plus dans une version antérieure. Les états GB/GBC restent au format 11.
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
