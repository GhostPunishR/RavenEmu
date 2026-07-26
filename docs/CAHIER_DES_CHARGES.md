# RavenEmu : cahier des charges technique

Version 1.0 : document de référence du projet.

## 1. Objet

RavenEmu est une application Android d'émulation de consoles, dont la
première cible est la Game Boy classique (DMG). Le moteur d'émulation, les
composants applicatifs et la logique métier sont développés spécifiquement
pour RavenEmu, à partir de documentation technique publique (Pan Docs,
documentation matérielle publiée), sans intégrer, copier, adapter ni
traduire le code d'un émulateur existant. Le code est distribué sous licence
MIT, © GhostPunishR (voir `LICENSE`).

## 2. Contraintes d'originalité

- Aucun coeur d'émulation tiers, aucun code issu d'un émulateur existant.
- Aucun BIOS, ROM, pochette ou contenu protégé embarqué dans le dépôt ou l'APK.
- Dépendances limitées aux composants officiels : SDK Android, Kotlin,
  Gradle, bibliothèques AndroidX et Material de Google et bibliothèques
  officielles JetBrains comme kotlinx-coroutines et kotlinx-serialization.
- Toute autre dépendance est remplacée par une implémentation interne.

## 3. Périmètre fonctionnel de la version 1

### 3.1 Émulation

| Domaine | Exigence |
|---|---|
| Console | Game Boy DMG, architecture prête pour GBC |
| Format ROM | `.gb` de 32 Kio à 8 Mio |
| Cartouches | ROM seule, MBC1, MBC2, MBC3, MBC5, RAM cartouche, pile |
| CPU | Sharp LR35902 : jeu principal, instructions préfixées CB, drapeaux exacts |
| Interruptions | VBlank, STAT, Timer, Serial, Joypad, IME, délai EI, HALT |
| Timers | DIV, TIMA, TMA et TAC au cycle près |
| Mémoire | Plan mémoire complet de 0x0000 à 0xFFFF, échos, zones interdites |
| PPU | Modes 0 à 3, BG, fenêtre, sprites 8×8 et 8×16, priorités, LYC, STAT |
| DMA | OAM DMA |
| Joypad | 8 boutons, registre P1, interruption joypad |
| Audio | 4 canaux, phase dédiée après validation du moteur principal |
| Sauvegardes | `.sav` brut pour la RAM cartouche, états instantanés versionnés |
| Synchronisation | Cadence 59,7275 Hz alignée sur l'affichage Android |

### 3.2 Application

- Bibliothèque visuelle des jeux : dossiers choisis via Storage Access
  Framework, indexation locale, en-têtes de cartouche, recherche, tri, filtres,
  actualisation manuelle, détection des fichiers ajoutés, déplacés ou supprimés,
  vue grille et liste.
- Pochettes : image manuelle, image à côté de la ROM, dossier de pochettes,
  association par nom de fichier ou par empreinte, jaquette générée localement
  en l'absence de pochette. Aucune pochette distribuée.
- Identification : CRC32, SHA-1, SHA-256, statuts « Officielle vérifiée »,
  « ROM hack identifié », « Modifiée ou non reconnue », « Inconnue » et
  « Homebrew ». La base locale ne contient que des métadonnées et empreintes,
  jamais de ROM.
- Écran d'émulation : croix, A, B, Start, Select, menu, indicateur de
  performance optionnel, adaptation à toutes tailles, encoches, pliables,
  portrait, paysage et tablettes, ratio natif 10:9 conservé par défaut.
- Éditeur de commandes tactiles : déplacement, redimensionnement, opacité,
  zone tactile, verrouillage, restauration, profils portrait, paysage et par
  jeu, vibrations, multi-touch, manettes physiques. Coordonnées relatives.
- Sauvegardes : `.sav` compatible, écriture atomique via fichier temporaire,
  sauvegarde automatique et états instantanés versionnés propres à RavenEmu.
- Paramètres : émulation, vidéo, audio, contrôles, fichiers, bibliothèque et
  débogage.

## 4. Exigences non fonctionnelles

- Moteur sur thread dédié, boucle déterministe, framebuffer partagé de
  manière sûre, allocations minimales pendant l'émulation.
- Pause et reprise Android correctes, sauvegarde avant interruption du
  processus lorsque possible.
- Sécurité : aucun téléversement de ROM ni de sauvegarde, aucune télémétrie,
  validation de taille et de format des fichiers, permissions minimales,
  sélecteurs de fichiers Android via SAF plutôt que permissions globales.
- Qualité : code lisible, documenté, testable, modulaire, déterministe et
  maintenable, `gameboy-core` testable sur JVM sans Android.

## 5. Tests exigés

Tests unitaires originaux, sans ROM de test tierce dans le dépôt : registres
CPU, drapeaux F, instructions principales et CB, branchements, pile,
interruptions, timers, accès mémoire, banques, MBC1, MBC2, MBC3, MBC5, DMA,
PPU et rendu de lignes, sérialisation, `.sav`, parsing d'en-têtes, empreintes
et cycle de vie Android lorsque l'instrumentation est disponible.

## 6. Intégration continue

Le workflow GitHub Actions se déclenche sur les branches principales et les
pull requests. Il fixe Java, utilise le Gradle Wrapper et exécute `lint`,
`test` et `assembleDebug`, avec publication des rapports et de l'APK Debug.

`assembleRelease` et `bundleRelease` utilisent uniquement les secrets GitHub.
Le keystore ne doit jamais être stocké dans le dépôt.

## 7. Livraison

La réalisation est progressive. Consultez `docs/ARCHITECTURE.md` pour les
décisions et `README.md` pour l'état d'avancement. Chaque étape livre le code,
ses tests et ses limites connues. Aucune fonctionnalité n'est déclarée
terminée sans tests ou sans mention explicite de ses limites.
