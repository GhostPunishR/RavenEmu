# Dépannage

Les vérifications suivantes concernent l'application Android native. Commencez par relever le **commit du build**, votre appareil et la version Android.

## Le téléchargement Android est introuvable

- L'application native n'a pas encore de version publique : son APK est un artefact des [compilations de `main`](https://github.com/GhostPunishR/RavenEmu/actions/workflows/ci-applications.yml?query=branch%3Amain+is%3Asuccess), visible une fois connecté à GitHub.
- Ouvrez la dernière compilation réussie, section « Artifacts », et téléchargez `ravenemu-android-dev-arm64`. Il est gardé 7 jours.
- Les préversions `android-beta-N` des Releases sont celles de la bêta retirée.

## L'APK ne s'installe pas

Vérifiez Android 8.0 minimum, un téléphone ARM64, l'espace libre et l'autorisation d'installer depuis la source utilisée. Extrayez `android-debug.apk` du ZIP de l'artefact avant de l'installer.

L'APK de développement (`com.ravenemu.dev`) s'installe à côté de la bêta. Chaque compilation est signée avec sa clé de débogage : si une mise à jour est refusée, **exportez vos sauvegardes avant de désinstaller**. Voir [Installation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md).

## Un jeu n'apparaît pas

- Importez le fichier avec **+** ou le dossier avec **⋮ → Importer un dossier**.
- Vérifiez l'extension `.gb`, `.gbc` ou `.gba`, la validité et la taille maximale de 32 Mio.
- Lisez le bilan : doublons, ROM invalides, fichiers illisibles ou limites de parcours.
- Essayez l'import direct d'une ROM ou un sous-dossier plus petit.

La bibliothèque garde une copie privée ; elle n'est pas une surveillance permanente du dossier d'origine. Une nouvelle ROM copiée dans ce dossier doit être importée.

## La console ou la jaquette semble incorrecte

GB/GBC est décidé par l'en-tête. Un fichier `.gb` peut être classé en Game Boy Color.

L'application ne télécharge aucune jaquette : choisissez une image dans la fiche du jeu. Une jaquette importée avant la correction de leur qualité reste floue : choisissez-la à nouveau.

## Écran noir ou jeu lent

- Utilisez un build récent de la nouvelle version, pas l'ancien APK Test.
- Démarrez sans charger un état ancien ou incompatible.
- Notez la console, la région/révision et le résultat avec un autre jeu autorisé.
- Affichez les performances dans **Paramètres → Général**.
- Testez sans économie d'énergie ni applications lourdes en arrière-plan.

Les mesures correspondent aux frames calculées, pas aux FPS d'écran garantis. La compatibilité et les performances GBC/GBA restent en validation.

## Son absent ou craquements

Vérifiez **Activer le son**, le volume RavenEmu et le volume média Android. Le son est volontairement suspendu en arrière-plan et pendant le panneau de jeu.

Pour un défaut reproductible, indiquez s'il survient au lancement, après un état ou pendant toute la partie. Le menu du jeu permet d'exporter le diagnostic audio. Voir [Affichage et audio](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Affichage-et-audio.md).

## Progression absente ou état refusé

Une sauvegarde normale du jeu et un état instantané sont distincts. Vérifiez que vous avez réimporté la **même ROM exacte**, pas une autre édition. Un état peut ne plus fonctionner après une évolution du moteur ou un reclassement GB vers GBC.

Les exports ZIP doivent correspondre au SHA-256 du jeu cible. Un `.sav` ou `.srm` brut nécessite une confirmation explicite. Ne désinstallez pas pour résoudre ce problème sans avoir exporté la progression encore accessible. Voir [Sauvegardes et états](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md).

## RetroAchievements ou skins

La reconnaissance et l'attribution des succès restent à valider : voir [RetroAchievements](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/RetroAchievements.md). L’import des skins `.deltaskin` est accessible dans Paramètres > Interface sur Android. Si un skin ne se charge pas, vérifiez son manifeste, sa représentation portrait et les limites de taille ; le lecteur peut revenir aux commandes natives.

## Signaler un défaut

Dans une [issue](https://github.com/GhostPunishR/RavenEmu/issues/new/choose), indiquez commit/build, appareil, Android, console, région/révision, étapes, résultat attendu et observé, réglages concernés et diagnostics utiles.

Ne joignez aucune ROM, aucun BIOS, aucun jeton ni aucune sauvegarde personnelle. Retirez les chemins et identifiants privés. Pour une vulnérabilité, utilisez un [avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new).
