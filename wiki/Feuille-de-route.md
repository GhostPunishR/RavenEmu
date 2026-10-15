# Feuille de route

Cette page décrit la version multiplateforme, sans date de livraison promise. Les [issues](https://github.com/GhostPunishR/RavenEmu/issues) et [pull requests](https://github.com/GhostPunishR/RavenEmu/pulls) donnent le détail des travaux en cours.

## Intégré dans la bêta Android

- Interface Compose partagée avec l'hôte iOS.
- Import de fichiers et dossiers `.gb`, `.gbc`, `.gba`.
- Bibliothèque persistante, recherche, grille/liste et navigation par console.
- Passerelle vers les moteurs RavenCore, rendu vidéo, audio et commandes tactiles avec L/R en GBA.
- Sauvegardes de cartouche et quatre emplacements d'état avec miniatures.
- Export ZIP ou `.sav` et import avec contrôle/confirmation.
- Jaquettes manuelles et recherche en ligne facultative, désactivée par défaut.
- Réglages audio, vidéo, commandes et diagnostics.
- Manettes Android et gestion des skins de contrôleur `.deltaskin` en portrait.
- Intégration RetroAchievements Softcore : compte, tableau de bord, badges et suivi de succès.

« Intégré » décrit le raccordement dans le code. Cela ne remplace pas une validation sur appareil.

## À valider sur téléphone

| Sujet | Validation attendue |
|---|---|
| Audio | Écoute au démarrage, pendant la partie et après reprise/chargement d'état |
| Sauvegardes | Sauvegarde normale, quatre états, reprise après redémarrage, export puis import |
| Game Boy Color et Game Boy Advance | Compatibilité, vidéo, audio et performances sur plusieurs appareils |
| RetroAchievements | Authentification, ROM reconnue, badges et déblocage confirmé côté serveur |

## Travaux restants

- Raccorder import, stockage et lecteur jouable iOS.
- Étendre les réglages des commandes et valider les skins sur appareil.
- Étendre la matrice de résultats reproductibles et traiter les défauts observés.

Les fonctions de la dernière branche RavenCore ne sont pas automatiquement celles du commit épinglé par RavenEmu.

## Distribution

Le workflow `multiplateforme.yml` est la CI de construction active. Il produit `ravenemu-android-beta`. Aucun binaire iOS signé n’est distribué.

Consultez [Installation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md), [Compatibilité des jeux](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compatibilite-des-jeux.md) et l'[état détaillé du projet](https://github.com/GhostPunishR/RavenEmu/blob/main/README.md).
