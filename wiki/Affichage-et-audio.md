# Affichage et audio

## Profils Game Boy

Le moteur monochrome produit quatre niveaux. Le renderer applique ensuite un profil d'écran:

- Game Boy DMG;
- Game Boy Pocket;
- Game Boy Light éteinte;
- Game Boy Light allumée;
- noir et blanc.

Le profil peut être changé sans modifier le fonctionnement interne du jeu.

## Réglages avancés

RavenEmu propose:

- luminosité;
- contraste;
- correction colorimétrique LCD;
- conservation du ratio natif;
- mise à l'échelle entière;
- filtrage nearest-neighbor.

Les corrections avancées n'ont pas d'effet par défaut. Les sorties Game Boy Color et Game Boy Advance sont affichées en couleur.

## Format d'image

La Game Boy et la Game Boy Color utilisent une image de 160 par 144 pixels. La Game Boy Advance utilise une image de 240 par 160 pixels.

Conserver le ratio évite une image étirée. La mise à l'échelle entière améliore la netteté lorsque la taille de l'écran le permet.

## Audio

Les moteurs produisent du PCM transmis à la couche audio Android. La synchronisation audio participe à la cadence normale de l'émulation.

Si le son craque:

- désactivez l'avance rapide;
- fermez les applications lourdes;
- vérifiez la fréquence du problème avec la surcouche de performance;
- testez sans économie d'énergie Android;
- utilisez la dernière version Debug;
- consultez [[Dépannage]].

Un problème audio sur Game Boy Advance peut venir d'une performance insuffisante plutôt que du mixage lui-même.
