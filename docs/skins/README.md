# Previews des skins RavenEmu

Ces previews sont rendues à partir des mêmes `VectorDrawable` que
l’application. Les écrans « avec jeu » utilisent une scène synthétique
originale, uniquement destinée à vérifier le cadrage natif.

| État | Preview |
| --- | --- |
| GB/GBC sans jeu | ![GB/GBC sans jeu](previews/raven-gb-empty.svg) |
| GB/GBC avec jeu | ![GB/GBC avec jeu](previews/raven-gb-game.svg) |
| GBA sans jeu | ![GBA sans jeu](previews/raven-gba-empty.svg) |
| GBA avec jeu | ![GBA avec jeu](previews/raven-gba-game.svg) |
| Bouton A pressé | ![A pressé](previews/raven-gb-a-pressed.svg) |
| Direction haute pressée | ![D-pad haut pressé](previews/raven-gb-dpad-up-pressed.svg) |
| Gâchette L pressée | ![L pressé](previews/raven-gba-l-pressed.svg) |

Pour régénérer les images :

```shell
python3 tools/render_raven_skin_previews.py
```
