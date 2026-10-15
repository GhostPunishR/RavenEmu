# Dépannage

Les vérifications suivantes concernent la bêta Android multiplateforme. Commencez par relever le **commit du build**, votre appareil et la version Android.

## L'artefact Android est introuvable

- Connectez-vous à GitHub.
- Ouvrez le bon [workflow multiplateforme](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml?query=branch%3Amain+is%3Asuccess), une exécution de `main` et son job Android réussi.
- Cherchez `ravenemu-android-beta` dans les artefacts.
- Les artefacts expirent après 7 jours ; essayez une exécution plus récente ou [compilez](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compilation.md).

## L'APK ne s'installe pas

Vérifiez Android 8.0 minimum, l'espace libre et l'autorisation d'installer depuis la source utilisée. Extrayez bien l'APK du ZIP.

En cas de signature incompatible, **exportez vos sauvegardes depuis la version installée avant toute désinstallation**. La bêta utilise une signature Debug, qui n'est pas la clé Test dédiée de l'ancienne application. Voir [Installation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md).

## Un jeu n'apparaît pas

- Importez le fichier avec **+** ou le dossier avec **⋮ → Importer un dossier**.
- Vérifiez l'extension `.gb`, `.gbc` ou `.gba`, la validité et la taille maximale de 32 Mio.
- Lisez le bilan : doublons, ROM invalides, fichiers illisibles ou limites de parcours.
- Essayez l'import direct d'une ROM ou un sous-dossier plus petit.

La bibliothèque garde une copie privée ; elle n'est pas une surveillance permanente du dossier d'origine. Une nouvelle ROM copiée dans ce dossier doit être importée.

## La console ou la jaquette semble incorrecte

GB/GBC est décidé par l'en-tête. Un fichier `.gb` peut être classé en Game Boy Color. Les images en ligne dépendent du nom et d'une correspondance de catalogue, sans garantie d'édition exacte.

Vérifiez **Paramètres → Général → Jaquettes en ligne** si vous voulez la recherche facultative. Sinon choisissez une image manuelle dans la fiche. Une absence de réseau ou de correspondance ne doit pas bloquer le jeu.

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

Les exports ZIP doivent correspondre au SHA-256 du jeu cible. Un `.sav` brut nécessite une confirmation explicite. Ne désinstallez pas pour résoudre ce problème sans avoir exporté la progression encore accessible. Voir [Sauvegardes et états](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md).

## RetroAchievements ou skins

La reconnaissance et l'attribution des succès restent à valider : voir [RetroAchievements](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/RetroAchievements.md). L’import des skins `.deltaskin` est accessible dans Paramètres > Interface sur Android. Si un skin ne se charge pas, vérifiez son manifeste, sa représentation portrait et les limites de taille ; le lecteur peut revenir aux commandes natives.

## Signaler un défaut

Dans une [issue](https://github.com/GhostPunishR/RavenEmu/issues/new/choose), indiquez commit/build, appareil, Android, console, région/révision, étapes, résultat attendu et observé, réglages concernés et diagnostics utiles.

Ne joignez aucune ROM, aucun BIOS, aucun jeton ni aucune sauvegarde personnelle. Retirez les chemins et identifiants privés. Pour une vulnérabilité, utilisez un [avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new).
