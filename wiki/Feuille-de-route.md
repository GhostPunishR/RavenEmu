# Feuille de route

Cette page présente les priorités générales. Les issues et pull requests restent la source la plus précise pour le travail en cours.

## Réalisé

- moteur Game Boy;
- compatibilité Game Boy Color;
- audio, vidéo, timers et principaux contrôleurs de cartouche;
- bibliothèque locale avec empreintes et pochettes;
- contrôles tactiles configurables et manettes physiques;
- profils d'écran monochrome;
- sauvegardes `.sav` et états instantanés;
- moteur Game Boy Advance intégré;
- APK Test publié automatiquement, signé par une clé dédiée et stable, avec empreintes et provenance vérifiables.

## Priorités actuelles

- étendre les tests sur appareils Android;
- améliorer les performances Game Boy Advance;
- valider davantage de jeux avec des copies obtenues légalement;
- améliorer la précision des timings;
- compléter les fonctions vidéo et audio GBA;
- renforcer les outils de diagnostic;
- documenter les résultats de compatibilité.

## Nintendo DS

Le travail est amorcé mais aucune image n'est encore émulée. Ce qui existe
aujourd'hui :

- l'identité de la console et son identifiant persisté;
- le décodage et le contrôle de l'en-tête de cartouche;
- le contrat vidéo, deux écrans de 256 sur 192 empilés dans un tampon unique;
- le contrat audio annoncé par le matériel;
- le processeur principal ARM946E-S, ses deux jeux d'instructions (ARM 32 bits,
  `CLZ`, `BLX` et arithmétique saturante comprises, et Thumb 16 bits) et le
  passage de l'un à l'autre, éprouvés contre une mémoire de test;
- son coprocesseur système : mémoires locales, base des vecteurs d'exception,
  attente d'interruption, registres de protection et de cache;
- la carte mémoire vue par ce processeur : mémoire principale, mémoire commune,
  palette, mémoire d'objets et les neuf banques vidéo par leur fenêtre de
  transfert;
- le processeur secondaire ARM7TDMI, jeu ARMv4T, servi par la même
  implémentation que le principal, avec les différences d'architecture nommées
  et éprouvées une à une;
- sa carte mémoire à lui, sa mémoire de travail propre, et la mémoire que les
  deux processeurs se partagent avec son découpage en quatre parts
  complémentaires;
- la communication entre les deux processeurs : le registre de synchronisation,
  les deux files de seize mots, leurs erreurs de débordement, et le contrôleur
  d'interruptions de chaque côté qui transforme un message déposé en
  interruption réellement prise;
- l'aiguillage des neuf banques vidéo, décodé banque par banque, qui décide de
  ce que chaque moteur trouve à une place donnée;
- les décors en mode texte des deux moteurs 2D : tuiles de huit sur huit, seize
  ou deux cent cinquante-six couleurs, retournements, quatre tailles de carte,
  défilement, et la résolution des priorités entre les quatre plans et le fond.

Toute demande d'exécution reste refusée par une erreur nommée, volontairement :
un écran noir laisserait croire à une émulation silencieuse. Le moteur 2D sait
dessiner une ligne, mais rien ne la lui demande encore, faute d'ordonnanceur et
de compteur de lignes. Restent à écrire les sprites, les décors tournants et les
modes étendus, le moteur 3D, les fenêtres et les mélanges, les palettes
étendues, la cartouche, les minuteries, les transferts autonomes, l'écran
tactile et le son. La console n'apparaît pas encore dans la bibliothèque de
l'application.

## Pistes futures

- effets vidéo au milieu d'une ligne;
- mosaïque Game Boy Advance;
- détails audio matériels supplémentaires;
- BIOS Game Boy Advance fourni par l'utilisateur avec validation stricte;
- nouvelles consoles sous forme de modules indépendants.

Une piste n'est pas une promesse de date ou de version. Ouvrez une [issue](https://github.com/GhostPunishR/RavenEmu/issues) pour discuter d'une proposition avant son développement.
