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
  attente d'interruption, registres de protection et de cache.

Toute demande d'exécution reste refusée par une erreur nommée, volontairement :
un écran noir laisserait croire à une émulation silencieuse. Restent à écrire la
carte mémoire, le second processeur, les moteurs 2D et 3D, les banques vidéo
commutables, la communication entre processeurs, l'écran tactile et le son. La
console n'apparaît pas encore dans la bibliothèque de l'application.

## Pistes futures

- effets vidéo au milieu d'une ligne;
- mosaïque Game Boy Advance;
- détails audio matériels supplémentaires;
- BIOS Game Boy Advance fourni par l'utilisateur avec validation stricte;
- nouvelles consoles sous forme de modules indépendants.

Une piste n'est pas une promesse de date ou de version. Ouvrez une [issue](https://github.com/GhostPunishR/RavenEmu/issues) pour discuter d'une proposition avant son développement.
