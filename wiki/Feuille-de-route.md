# Feuille de route

Cette page présente les priorités générales. Les issues et pull requests restent la source la plus précise pour le travail en cours.

## Réalisé

- moteur Game Boy;
- compatibilité Game Boy Color;
- ordonnancement GB/GBC par M-cycle et PPU à fetcher/FIFO BG/OBJ séparés;
- modes DMG, CGB natif et compatibilité CGB explicites;
- audio, vidéo, timers et principaux contrôleurs de cartouche, dont MBC1M;
- endpoints locaux déterministes pour série et infrarouge;
- boot ROM GB/GBC optionnelle dans l'API C++ et harness de conformité externe;
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
- valider et affiner les cas PPU/APU les plus dépendants de la révision
  matérielle à l'aide de suites librement utilisables;
- exposer la sélection du modèle et la boot ROM utilisateur dans l'hôte Android;
- fournir des backends link/IR hors du cœur;
- ajouter progressivement les contrôleurs de cartouche exotiques sans faux
  support;
- compléter les fonctions vidéo et audio GBA;
- renforcer les outils de diagnostic;
- documenter les résultats de compatibilité.

## Pistes futures

- effets vidéo au milieu d'une ligne;
- mosaïque Game Boy Advance;
- détails audio matériels supplémentaires;
- BIOS Game Boy Advance fourni par l'utilisateur avec validation stricte;
- nouvelles consoles sous forme de modules indépendants.

Une piste n'est pas une promesse de date ou de version. Ouvrez une [issue](https://github.com/GhostPunishR/RavenEmu/issues) pour discuter d'une proposition avant son développement.
