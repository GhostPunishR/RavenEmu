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

- utilisez le dernier APK Test;
- attendez quelques secondes au premier lancement;
- vérifiez la console détectée;
- testez une autre copie obtenue légalement;
- pour la Game Boy Advance, activez la surcouche de performance et relevez le dernier appel BIOS ainsi que les anomalies;
- redémarrez le jeu sans charger un ancien état instantané.

Le moteur Game Boy Advance reste expérimental. Un écran noir peut être une incompatibilité du moteur.

### Écran noir sur Nintendo DS

Le moteur Nintendo DS est en construction. Il fait tourner les deux processeurs et dessine les plans de texte et les sprites, mais le moteur 3D, le son, les plans tournants, les fenêtres et les mélanges manquent encore. Beaucoup de jeux du commerce ne montreront donc rien, ou une image incomplète, sans que rien ne soit cassé.

Pour savoir ce qui se passe, activez le compteur de performance dans Paramètres. Sur cette console, il affiche un relevé de quelques lignes:

- `ARM9` et `ARM7`, avec `actif` ou `arrêt`, le nombre d'instructions de la dernière trame et la position dans le programme. Deux compteurs à zéro, ou deux `arrêt`, disent que la console n'avance plus;
- `image`, avec le nombre de pixels allumés de la dernière trame. Zéro dit que le moteur n'a rien produit; un autre nombre dit qu'il a produit une image que l'écran n'a pas montrée, ce qui est un défaut d'affichage et non d'émulation;
- `non dessiné`, quand un plan, un mode de sortie ou un sprite a été rencontré sans pouvoir être dessiné;
- `ignoré`, quand un registre ou une commande de cartouche n'est servi par aucun organe, avec la première adresse en cause;
- `buté`, quand une instruction n'a pas été reconnue ou qu'un appel du programme d'amorçage manque.

Les trois dernières lignes n'apparaissent que si elles ont quelque chose à dire. Joignez ce relevé à votre rapport: il dit lequel des organes reprendre, et évite de chercher au mauvais endroit.

## Jeu trop lent

- désactivez l'économie d'énergie;
- fermez les applications en arrière-plan;
- testez avec l'appareil suffisamment chargé;
- activez la surcouche pour relever les images par seconde et le temps de trame;
- ne comparez pas une performance mesurée sur l'APK Debug à celle d'une version de production;
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
