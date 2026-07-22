# RavenEmu — Cahier des charges technique

Version 1.0 — document de référence du projet.

## 1. Objet

RavenEmu est une application Android propriétaire d'émulation de consoles,
dont la première cible est la Game Boy classique (DMG). Le moteur
d'émulation, les composants applicatifs et la logique métier sont développés
spécifiquement pour RavenEmu, à partir de documentation technique publique
(Pan Docs, documentation matérielle publiée), sans intégrer, copier, adapter
ni traduire le code d'un émulateur existant.

## 2. Contraintes de propriété

- Aucun cœur d'émulation tiers, aucun code issu d'un émulateur existant.
- Aucun BIOS, ROM, pochette ou contenu protégé embarqué dans le dépôt ou l'APK.
- Dépendances limitées aux composants officiels : SDK Android, Kotlin,
  Gradle, bibliothèques AndroidX/Material de Google et bibliothèques
  officielles JetBrains (kotlinx-coroutines, kotlinx-serialization).
- Toute autre dépendance est remplacée par une implémentation interne.

## 3. Périmètre fonctionnel de la version 1

### 3.1 Émulation

| Domaine | Exigence |
|---|---|
| Console | Game Boy DMG (architecture prête pour GBC) |
| Format ROM | `.gb` (32 KiB à 8 MiB) |
| Cartouches | ROM seule, MBC1, MBC2, MBC3, MBC5, RAM cartouche, pile |
| CPU | Sharp LR35902 : jeu principal + instructions préfixées CB, flags exacts |
| Interruptions | VBlank, STAT, Timer, Serial, Joypad ; IME, délai EI, HALT |
| Timers | DIV, TIMA/TMA/TAC au cycle près |
| Mémoire | Plan mémoire complet 0x0000–0xFFFF, échos, zones interdites |
| PPU | Modes 0–3, BG, fenêtre, sprites 8×8/8×16, priorités, LYC, STAT |
| DMA | OAM DMA |
| Joypad | 8 boutons, registre P1, interruption joypad |
| Audio | 4 canaux (phase dédiée, après validation du moteur principal) |
| Sauvegardes | `.sav` brut (RAM cartouche), états instantanés versionnés |
| Synchronisation | Cadence 59,7275 Hz alignée sur l'affichage Android |

### 3.2 Application

- Bibliothèque visuelle des jeux : dossiers choisis via Storage Access
  Framework, indexation locale, en-têtes de cartouche, recherche/tri/filtres,
  actualisation manuelle, détection des fichiers ajoutés/déplacés/supprimés,
  vue grille et liste.
- Pochettes : image manuelle, image à côté de la ROM, dossier de pochettes,
  association par nom de fichier ou par empreinte ; jaquette générée
  localement en l'absence de pochette. Aucune pochette distribuée.
- Identification : CRC32, SHA-1, SHA-256 ; statuts « Officielle vérifiée »,
  « ROM hack identifié », « Modifiée ou non reconnue », « Inconnue »,
  « Homebrew ». La base locale ne contient que des métadonnées et
  empreintes, jamais de ROM.
- Écran d'émulation : croix, A/B, Start/Select, menu, indicateur de
  performance optionnel ; adaptation à toutes tailles, encoches, pliables,
  portrait/paysage, tablettes ; ratio natif 10:9 conservé par défaut.
- Éditeur de commandes tactiles : déplacement, redimensionnement, opacité,
  zone tactile, verrouillage, restauration, profils portrait/paysage et par
  jeu, vibrations, multi-touch, manettes physiques. Coordonnées relatives.
- Sauvegardes : `.sav` compatible (écriture atomique via fichier temporaire,
  sauvegarde automatique), états instantanés versionnés propres à RavenEmu.
- Paramètres : émulation, vidéo, audio, contrôles, fichiers, bibliothèque,
  débogage (voir §11 du besoin d'origine).

## 4. Exigences non fonctionnelles

- Moteur sur thread dédié, boucle déterministe, framebuffer partagé de
  manière sûre, allocations minimales pendant l'émulation.
- Pause/reprise Android correctes, sauvegarde avant interruption du
  processus lorsque possible.
- Sécurité : aucun téléversement de ROM ni de sauvegarde, aucune télémétrie,
  validation de taille et de format des fichiers, permissions minimales,
  sélecteurs de fichiers Android (SAF) plutôt que permissions globales.
- Qualité : lisible, documenté, testable, modulaire, déterministe,
  maintenable ; `gameboy-core` testable sur JVM sans Android.

## 5. Tests exigés

Tests unitaires propriétaires (aucune ROM de test tierce dans le dépôt) :
registres CPU, flags F, instructions principales et CB, branchements, pile,
interruptions, timers, accès mémoire, banques, MBC1/2/3/5, DMA, PPU et rendu
de lignes, sérialisation, `.sav`, parsing d'en-têtes, empreintes, cycle de
vie Android (instrumentation, hors périmètre CI initial).

## 6. Intégration continue

Workflow GitHub Actions : déclenchement sur branches principales et pull
requests ; Java fixé ; Gradle Wrapper ; `lint`, `test`, `assembleDebug` avec
publication des rapports et de l'APK Debug en artefact ; `assembleRelease`
signé uniquement via les secrets GitHub (keystore jamais dans le dépôt) ;
génération `.aab` prévue ultérieurement.

## 7. Livraison

Réalisation progressive par étapes (voir `docs/ARCHITECTURE.md` pour les
décisions et `README.md` pour l'état d'avancement). Chaque étape livre le
code complet, ses tests et ses limites connues. Aucune fonctionnalité n'est
déclarée terminée sans tests ou sans mention explicite de ses limites.
