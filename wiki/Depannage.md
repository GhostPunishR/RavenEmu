# Dépannage

## L'APK ne s'installe pas

- vérifiez que l'installation depuis la source choisie est autorisée;
- téléchargez de nouveau le fichier officiel;
- comparez son empreinte SHA-256;
- vérifiez l'espace disponible;
- si Android indique une signature incompatible, sauvegardez vos données avant de désinstaller l'ancienne version.

Voir [[Installation]].

## Un jeu manque dans la bibliothèque

1. Vérifiez que le fichier est dans le dossier autorisé.
2. Actualisez la bibliothèque.
3. Accordez de nouveau l'accès au dossier avec le sélecteur Android.
4. Vérifiez que le fichier n'est pas un raccourci ou un fichier incomplet.
5. Essayez un dossier local simple pour isoler un problème de fournisseur de stockage.

## Écran noir

- utilisez la dernière version Debug;
- attendez quelques secondes au premier lancement;
- vérifiez la console détectée;
- testez une autre copie obtenue légalement;
- pour la Game Boy Advance, activez la surcouche de performance et relevez le dernier appel BIOS ainsi que les anomalies;
- redémarrez le jeu sans charger un ancien état instantané.

Le moteur Game Boy Advance reste expérimental. Un écran noir peut être une incompatibilité du moteur.

## Jeu trop lent

- désactivez l'économie d'énergie;
- fermez les applications en arrière-plan;
- testez avec l'appareil suffisamment chargé;
- activez la surcouche pour relever les images par seconde et le temps de trame;
- n'utilisez pas l'APK Debug pour comparer une performance finale de production;
- indiquez le modèle exact de l'appareil dans le rapport.

## Sauvegarde absente

- lancez le jeu et utilisez sa propre fonction de sauvegarde;
- quittez proprement vers la bibliothèque;
- vérifiez que le type de sauvegarde Game Boy Advance a été détecté;
- ne confondez pas sauvegarde `.sav` et état instantané;
- restaurez une copie externe si le fichier a été supprimé.

## Signaler un problème

Ouvrez une [issue GitHub](https://github.com/GhostPunishR/RavenEmu/issues) avec:

- appareil et version Android;
- version RavenEmu ou commit;
- console;
- étapes de reproduction;
- résultat attendu;
- résultat observé;
- journaux utiles sans donnée sensible;
- empreinte du fichier si nécessaire.

Ne publiez jamais de ROM, BIOS, clé, jeton, sauvegarde personnelle ou donnée privée.

Pour une vulnérabilité, utilisez un [avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new).
