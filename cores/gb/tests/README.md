# Harness de conformité GB/GBC

`gb_conformance_runner` exécute une ROM de test externe sans l'ajouter au
dépôt. Il ne télécharge rien et n'accepte implicitement aucune licence : le
développeur ou la CI doit fournir séparément des ROMs dont l'utilisation et la
redistribution sont autorisées.

Après le build natif, exemple :

```bash
build/native-host/gb/gb_conformance_runner chemin/test.gb \
  --hardware dmg --category timer --timeout-frames 600
```

Le runner détecte :

- les protocoles série contenant `Passed` ou `Failed` (textes configurables) ;
- la signature de registres `B,C,D,E,H,L = 3,5,8,13,21,34` utilisée par des
  suites matérielles publiques ;
- une ou plusieurs attentes mémoire stables, par exemple
  `--expect-memory 0xC000=0x42` ;
- un hash SHA-256 de framebuffer, sérialisé en pixels 32 bits big-endian, via
  `--expect-frame-sha256` ;
- l'arrêt inattendu du CPU, les exceptions et le timeout.

`--hardware cgb` sélectionne le CGB natif pour une cartouche couleur et le mode
de compatibilité CGB pour une cartouche DMG. Une boot ROM DMG/CGB acquise
légalement peut être passée par `--boot-rom`; aucune image n'est fournie par
RavenEmu.

Les catégories recommandées pour les manifests de CI sont : `cpu`, `timing`,
`interrupts`, `timer`, `halt`, `ei`, `stop`, `memory`, `oam-dma`, `vram-dma`,
`ppu`, `stat`, `palettes`, `speed-switch`, `serial`, `apu` et `mbc`.

Les tests synthétiques internes couvrent en complément les fronts des portes
VRAM/OAM/CRAM, leur échantillonnage à la frontière d'un M-cycle normal ou
double, le démarrage LCD DMG/CGB, le blocage entre sources STAT et
l'auto-incrément des palettes après une écriture refusée. Ils ne contiennent
aucune ROM ou donnée Nintendo.
