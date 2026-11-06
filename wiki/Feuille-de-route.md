# Feuille de route

Cette page décrit l'application Android native, sans date de livraison promise. Les [issues](https://github.com/GhostPunishR/RavenEmu/issues), les [pull requests](https://github.com/GhostPunishR/RavenEmu/pulls) et [l'historique de la migration](https://github.com/GhostPunishR/RavenEmu/blob/main/docs/MIGRATION_NATIVE.md) donnent le détail des travaux.

## Intégré dans l'application native

- Interface Java fidèle à RavenEmu : bibliothèque par console (appui ou glissement), recherche, grille ou liste, fiche du jeu.
- Import de fichiers et de dossiers `.gb`, `.gbc`, `.gba`, doublons reconnus à leur empreinte SHA-256.
- Frontend C et cores Libretro de RavenCore ; image GLES2 calée sur l'écran, son AAudio, commandes tactiles avec L/R en GBA.
- Sauvegardes de cartouche, quatre emplacements d'état avec miniatures, annuler le chargement.
- Export `.sav` ou `.srm`, export de toutes les sauvegardes, import avec vérification.
- Jaquettes choisies par le joueur, gardées sur l'appareil.
- Réglages audio, vidéo, console, interface et commandes ; thèmes RavenEmu et NVIDIA.
- Manettes Android, vibration et capteurs, skins de contrôleur `.deltaskin`.
- Câble link entre deux jeux d'un même téléphone.
- Codes de triche, vérifiés par RavenCore.
- RetroAchievements : compte, succès, classements, bandeaux, journal hors ligne.

« Intégré » décrit le raccordement dans le code. Cela ne remplace pas une validation sur appareil.

## À valider sur téléphone

| Sujet | Validation attendue |
|---|---|
| Interface | Navigation Android à trois boutons et par gestes, portrait et paysage, clavier, encoches |
| Audio | Écoute au démarrage, pendant la partie et après reprise ou chargement d'état |
| Sauvegardes | Sauvegarde normale, quatre états, reprise après redémarrage, export puis import |
| Game Boy Color et Game Boy Advance | Compatibilité, vidéo, audio et performances sur plusieurs appareils |
| RetroAchievements | Authentification, ROM reconnue, badges et déblocage confirmé côté serveur |

## Travaux restants

- Câble link sans fil entre deux téléphones, compatible avec la version PC.
- Import des états instantanés de la bêta, sur décision du propriétaire.
- Une première version publique : identifiant, signature et workflow de publication.
- Étendre la matrice de résultats reproductibles et traiter les défauts observés.

L'avance rapide et le retour arrière de la bêta ne sont pas repris, au choix du propriétaire. L'application iOS a été retirée.

Les fonctions de la dernière branche RavenCore ne sont pas automatiquement celles du commit épinglé par RavenEmu.

## Distribution

Le workflow `ci-applications.yml` construit et teste l'application, puis archive l'APK de développement `ravenemu-android-dev-arm64`. Il ne publie aucune version. Consultez [Installation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md) et [Compatibilité des jeux](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compatibilite-des-jeux.md).
