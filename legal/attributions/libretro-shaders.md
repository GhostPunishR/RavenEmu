# Effets d'écran d'après des shaders libretro

Les effets **Lissage net** et **Grille LCD** du pilote GLES2
(`android/src/main/cpp/hardware/video/gles2/gles2_renderer.c`) sont écrits
pour RavenEmu d'après deux shaders du dépôt
[libretro/glsl-shaders, révision cba01a48b0974a1232bea68d999a7c21bc4c233e](https://github.com/libretro/glsl-shaders/tree/cba01a48b0974a1232bea68d999a7c21bc4c233e),
tous deux placés par leurs auteurs dans le **domaine public** :

| Effet RavenEmu | Shader d'origine | Auteur | Licence |
| --- | --- | --- | --- |
| Lissage net | `interpolation/shaders/sharp-bilinear.glsl` | Themaister | Domaine public |
| Grille LCD | `handheld/shaders/lcd3x.glsl` | Gigaherz | Domaine public |

Seul le calcul de chaque shader est repris, en GLSL ES, dans le shader unique
du pilote : préchelle entière automatique puis filtre bilinéaire matériel pour
le lissage net ; scanlines (16) et triade de colonnes (4) aux réglages par
défaut pour la grille LCD. Aucun préréglage, paramètre ou autre fichier du
dépôt n'est repris. Le domaine public n'impose aucune obligation ; ce crédit
est gardé par honnêteté envers leurs auteurs.
