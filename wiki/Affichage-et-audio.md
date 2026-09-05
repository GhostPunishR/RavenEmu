# Affichage et audio

## Profils Game Boy

Le moteur monochrome produit quatre niveaux. Le renderer applique ensuite un profil d'écran:

- Game Boy DMG;
- Game Boy Pocket;
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

La chaîne comporte trois étages. Le mixage des cœurs prélève la **moyenne** de chaque canal sur la durée d'un échantillon, et non sa valeur instantanée: les canaux d'une console changent d'état bien plus vite que le débit de sortie, et un prélèvement instantané ferait redescendre leurs harmoniques dans l'audible sous forme de sifflements. Le passage au débit de sortie de l'appareil est ensuite fait par un filtre à bande limitée, qui laisse la bande utile intacte au lieu de ternir l'aigu. Enfin, l'écart inévitable entre l'horloge du jeu et celle du téléphone est rattrapé en continu par une correction de débit inférieure à un demi pour cent, trop petite pour s'entendre, qui évite les blancs périodiques dus au vidage de la sortie.

Si le son craque:

- fermez les applications lourdes;
- vérifiez la fréquence du problème avec la surcouche de performance;
- testez sans économie d'énergie Android;
- utilisez le dernier APK Test;
- consultez [[Dépannage|Depannage]].

Un problème audio sur Game Boy Advance peut venir d'une performance insuffisante plutôt que du mixage lui-même.
