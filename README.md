# RavenEmu

Émulateur Game Boy (DMG) pour Android, propriétaire et entièrement développé
pour RavenEmu à partir de documentation technique publique. Aucun code, cœur,
BIOS, ROM ou contenu tiers n'est intégré.

## Architecture

| Module | Type | Rôle |
|---|---|---|
| `app` | Application Android | Bibliothèque, écran d'émulation, paramètres |
| `emulation-api` | Kotlin JVM | Interfaces communes app ↔ moteurs |
| `gameboy-core` | Kotlin JVM | Moteur Game Boy complet (CPU, PPU, MBC…) |
| `rom-library` | Kotlin JVM | En-têtes, empreintes, identification, index |
| `storage` | Bibliothèque Android | SAF, `.sav`, états, index, pochettes |
| `renderer` | Bibliothèque Android | Affichage du framebuffer |
| `input` | Bibliothèque Android | Tactile, éditeur de disposition, manettes |
| `settings` | Bibliothèque Android | Préférences et palettes |

Les décisions structurantes sont consignées dans `docs/ARCHITECTURE.md`, le
besoin dans `docs/CAHIER_DES_CHARGES.md`, la compilation dans `docs/BUILD.md`.

## État d'avancement

- [x] Cahier des charges technique et décisions d'architecture
- [x] Arborescence Gradle multi-module
- [x] Interfaces du moteur d'émulation (`emulation-api`)
- [x] Cartouche, en-têtes, MBC1/2/3/5, RAM à pile, RTC
- [x] Bus mémoire complet, écho WRAM, zone interdite
- [x] CPU LR35902 (jeu principal + CB) et tests
- [x] Interruptions et timers au cycle
- [x] PPU scanline (fond, fenêtre, sprites, STAT/LYC), OAM DMA
- [x] Joypad, port série minimal
- [x] Sauvegardes `.sav` (écriture atomique) et états instantanés versionnés
- [x] Intégration Android (session threadée, cycle de vie)
- [x] Bibliothèque de ROM (SAF, empreintes, statuts, pochettes locales)
- [x] Interface d'émulation adaptative et éditeur de commandes tactiles
- [x] Paramètres
- [x] Workflow APK (tests, lint, Debug, Release signé via secrets)
- [ ] Audio (APU) — phase dédiée, registres déjà mémorisés
- [ ] Game Boy Color — prévu par l'architecture
- [ ] Tests de compatibilité étendus sur matériel réel

## Limites connues (v1)

- Pas de synthèse audio (phase dédiée à venir) : `readAudio` retourne 0.
- PPU sans effets mid-scanline (durée de mode 3 fixe) ; suffisant pour la
  grande majorité des jeux DMG.
- Multicarts MBC1M non pris en charge ; câble link non émulé.
- Les états instantanés sont propres à RavenEmu (format `RVNS` versionné).

## Compilation rapide

```bash
# Tests du moteur (aucun SDK Android requis)
./gradlew test

# APK Debug (SDK Android requis)
./gradlew assembleDebug
```

Voir `docs/BUILD.md` pour le détail et `docs/CONTRIBUTING.md` pour les règles
de contribution.

## Licence

Projet propriétaire. Tous droits réservés à son créateur. Voir les contraintes
dans `docs/CAHIER_DES_CHARGES.md` (§2).
