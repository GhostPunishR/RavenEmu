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

Le travail est amorce mais rien n'est encore emule. Ce qui existe aujourd'hui:

- l'identite de la console et son identifiant persiste;
- le decodage et le controle de l'en-tete de cartouche;
- le contrat video, deux ecrans de 256 sur 192 empiles dans un tampon unique;
- le contrat audio annonce par le materiel.

Toute demande d'execution est refusee par une erreur nommee, volontairement:
un ecran noir laisserait croire a une emulation silencieuse. Restent a ecrire
les deux processeurs, les moteurs 2D et 3D, les banques video commutables, la
communication entre processeurs, l'ecran tactile et le son. La console
n'apparait pas encore dans la bibliotheque de l'application.

## Pistes futures

- effets vidéo au milieu d'une ligne;
- mosaïque Game Boy Advance;
- détails audio matériels supplémentaires;
- BIOS Game Boy Advance fourni par l'utilisateur avec validation stricte;
- nouvelles consoles sous forme de modules indépendants.

Une piste n'est pas une promesse de date ou de version. Ouvrez une [issue](https://github.com/GhostPunishR/RavenEmu/issues) pour discuter d'une proposition avant son développement.
