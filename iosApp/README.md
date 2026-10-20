# Première intégration iOS

L'application SwiftUI héberge la même interface Compose qu'Android. `IosHost` fournit les états et les actions de l'interface ; `RavenRuntime` exécute tous les appels à une instance RavenCore sur une file série. Cette première tranche raccorde le lecteur ; les essais sur iPhone et iPad restent nécessaires.

## Fonctions raccordées

- Import d'une ROM `.gb`, `.gbc` ou `.gba` depuis Fichiers, avec accès de sécurité iOS et lecture coordonnée pour les fournisseurs de fichiers. Limite de 32 Mio et validation par le moteur avant ajout.
- Copie privée et bibliothèque persistante dans Application Support. Identité SHA-256 du contenu, détection GB/GBC par l'en-tête, doublons par contenu et contrôle d'intégrité à l'ouverture.
- Images natives GB/GBC 160 × 144 et GBA 240 × 160, commandes tactiles partagées, profils d'affichage et réglages audio/commandes persistés dans UserDefaults.
- Audio PCM16 stéréo converti pour AVAudioEngine, avec au plus quatre buffers en attente. Le mode silencieux et le volume système iOS restent applicables.
- Sauvegarde batterie atomique après modification, vérifiée toutes les secondes ainsi qu'à l'arrêt, à la pause, au changement de jeu et avant suspension. Une génération n'est acquittée qu'après écriture réussie.
- Quatre emplacements d'état, avec date, enregistrement, chargement et suppression depuis le menu. Pas encore de miniature.
- Import `.sav` confirmé avant remplacement et export depuis Fichiers avec le nom de la ROM. L'import d'une sauvegarde décharge l'ancienne instance du jeu pour empêcher un écrasement tardif.
- Pause et relâchement des commandes à l'ouverture du menu, à la perte de premier plan ou lors d'une interruption audio. Une tâche d'arrière-plan laisse terminer l'enregistrement ; aucune émulation en arrière-plan.

Les ROM et sauvegardes ne sont pas envoyées par ce lecteur à un service distant. Les sauvegardes système de l'appareil dépendent des réglages iOS de l'utilisateur. Une suppression de l'application peut supprimer ses données privées : garder ses exports `.sav` hors de l'application.

## Compiler et tester

Sur un Mac Apple Silicon avec Xcode, JDK 21, CMake et XcodeGen, depuis la racine :

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
sh native/build-ios.sh
sh iosApp/ci-test.sh
```

Le script génère le projet, choisit un simulateur iPhone disponible, construit l'application et exécute les XCTest. Les données de test sont des programmes GB/GBA synthétiques originaux ; aucune ROM commerciale ni BIOS n'est fourni. Les tests portent sur le stockage, la détection, les doublons, les bornes, la corruption, les états et les appels aux trois moteurs.

La CI conserve `ravenemu-ios-simulator`, contenant `RavenEmu-simulator.zip`. Ce `.app` est réservé au simulateur Apple Silicon : il ne s'installe pas sur un iPhone. Les résultats `.xcresult` sont conservés si les tests échouent.

Pour un appareil : générer le projet dans `iosApp/`, l'ouvrir dans Xcode, choisir son équipe de développement et un iPhone connecté, puis construire. Les scripts sélectionnent le framework et les bibliothèques natifs pour l'appareil. Aucun certificat, profil personnel ni binaire iPhone signé n'est stocké ou publié par ce dépôt.

## Validation sur appareil restante

Avec ses propres ROMs autorisées et des copies de sauvegardes :

1. Importer depuis le stockage local puis un fournisseur Fichiers, fermer et rouvrir l'application, vérifier les doublons et la reprise de bibliothèque.
2. Vérifier image, diagonales, appuis simultanés, boutons L/R GBA et menu. Vérifier aussi les marges et la rotation sur iPhone/iPad.
3. Vérifier son, volume, mode silencieux, pause/menu, mise en arrière-plan, retour, interruption et changement de route audio.
4. Sauvegarder dans le jeu, quitter et relancer ; exporter `.sav` puis le réimporter après confirmation. Vérifier qu'un refus ou une annulation garde les anciennes données.
5. Vérifier les quatre emplacements, le refus d'un état incompatible et la conservation de la sauvegarde batterie après une erreur.

## Étapes suivantes

RetroAchievements Apple, câble link, manettes externes, capteurs/vibration de cartouche, skins Delta, jaquettes, import de dossiers, archives ZIP et distribution iPhone signée ne sont pas raccordés dans cette tranche. Les réglages dépendant de services absents sont masqués. La compilation et les tests synthétiques ne constituent pas une validation de compatibilité commerciale, des performances ou de la sortie audio sur téléphone.
