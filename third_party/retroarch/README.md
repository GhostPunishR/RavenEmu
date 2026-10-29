# Portions de RetroArch adaptées dans la prévisualisation native

Source amont : [libretro/RetroArch, révision
7986022baf6822677ad2cec4bc215069e89bd8ce](https://github.com/libretro/RetroArch/tree/7986022baf6822677ad2cec4bc215069e89bd8ce).
Adaptations par GhostPunishR le 29 octobre 2026. Les fichiers adaptés gardent
leurs avis de copyright, de modification et leur licence **GPL-3.0-or-later**.

| Fichier RavenEmu | Sources amont et parties adaptées |
| --- | --- |
| `androidNative/src/main/cpp/retroarch_aaudio.c` | `audio/drivers/aaudio.c` : `aaudio_data_cb`, ouverture PCM, configuration des buffers et fermeture du stream |
| `androidNative/src/main/cpp/native_video.c` | `gfx/drivers/gl2.c` : chargement et mise à jour de texture, branche GLES des images logicielles ; `gfx/drivers_shader/shader_glsl.c` : compilation et liaison ; `gfx/drivers/gl_shaders/modern_opaque.glsl.vert.h` et `.frag.h` : shaders opaques de base |

AAudio : copyright © 2026 The RetroArch team.
GL2 : copyright © 2010–2014 Hans-Kristian Arntzen, 2011–2017 Daniel De Matteis,
2012–2015 Michael Lelli, 2016–2019 Brad Parker. Les avis exacts sont conservés
dans les fichiers. Le code de compilation/liaison porte les copyrights
Arntzen/De Matteis ; les shaders sont ceux inclus par ce pilote.

L'adaptateur audio utilise directement AAudio API 26, en PCM16 stéréo partagé
à la fréquence native du périphérique. Une file C11 bornée remplace les
dépendances du frontend RetroArch ; le producteur n'attend pas de place.
Le callback remplit les sous-alimentations par du silence. La taille demandée
au périphérique est de deux bursts ; le budget de file est de 64 ms, ajusté
aux bursts et à la capacité. Ce budget ne mesure pas la latence réelle.
Le convertisseur linéaire de `native_audio.c` reste celui de RavenEmu.

Le pilote vidéo reçoit les pixels RGBA logiciels de RavenCore, alloue la
texture quand sa géométrie change et la met à jour par `glTexSubImage2D`.
Un rafraîchissement sans image nouvelle réutilise la texture. Le filtrage
nearest, le cadrage centré et l'orientation sont conservés. Les pixels CPU
restent accessibles aux miniatures d'états. Les échecs GL sont retentés.

Le frontend, la boucle d'émulation et les menus restent ceux de RavenEmu ;
RavenCore reste le moteur GB/GBC/GBA. Les resamplers sinc, les presets de
shaders, la sélection de périphériques, la reconnexion audio automatique,
les cores graphiques matériels et la planification de RetroArch ne sont
pas portés. La cadence précise audio/vidéo et la validation sur téléphone
restent des travaux de migration.

## Licences et distribution

Le [texte intégral GPLv3](../../androidNative/src/main/assets/licenses/GPL-3.0.txt)
accompagne l'APK. RavenEmu et RavenCore gardent leur [licence AGPLv3](../../LICENSE).
Les sections [13 de GPLv3](https://www.gnu.org/licenses/gpl-3.0.html#section13)
et [13 d'AGPLv3](https://www.gnu.org/licenses/agpl-3.0.html#section13) permettent
cette combinaison, sans changer la licence propre des portions GPL. Les
exigences AGPL relatives à l'interaction à distance s'appliquent à la combinaison.
L'écran **Paramètres → À propos → Licences et crédits** fournit les avis et
les textes GPL, AGPL, MIT Libretro et Apache 2.0. Les palettes GBC de RavenCore
sont également attribuées à TCRF sous CC BY 3.0.

Les crédits seuls ne remplacent pas les obligations de fourniture du code.
La CI de migration joint à l'APK de test `ravenemu-native-sources.tar.gz`, dans
le **même artifact**, avec les fichiers suivis de RavenEmu et du checkout
RavenCore réellement construit, leurs scripts et notices, et les publications
sources/POM/AAR ou JAR des dépendances Android résolues. Le manifeste indique
les révisions, modifications locales et SHA-256, dont celui de l'APK.
Les fichiers non suivis, clés privées, caches et sorties de compilation
ne font pas partie de l'archive.

Pour reconstruire l'archive et l'APK, voir les commandes dans
[androidNative/README.md](../../androidNative/README.md). Les dépendances
conservent leurs propres licences et notices. Leurs publications sources
sont complétées par leurs dépôts de construction :
[Material Components](https://github.com/material-components/material-components-android/tree/1.14.0),
[AndroidX](https://android.googlesource.com/platform/frameworks/support/),
[Kotlin](https://github.com/JetBrains/kotlin). Les versions exactes sont
inventoriées dans `dependencies/DEPENDENCIES.tsv` et les POM de l'archive.
Le collecteur conserve aussi les conteneurs binaires d'origine pour leurs
ressources et notices ; il ne transforme pas un binaire en source.

Toute redistribution ultérieure doit maintenir un accès équivalent aux
sources de **sa version exacte**, vérifier les publications amont nécessaires
et conserver les avis de chaque composant. La rétention CI de sept jours
convient aux essais de cette PR ; elle ne constitue pas une offre permanente
de sources pour une future publication de produit.
