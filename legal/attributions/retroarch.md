# Portions de RetroArch adaptées dans la prévisualisation native

Source amont : [libretro/RetroArch, révision
7986022baf6822677ad2cec4bc215069e89bd8ce](https://github.com/libretro/RetroArch/tree/7986022baf6822677ad2cec4bc215069e89bd8ce).
Adaptations par GhostPunishR les 29 et 30 octobre 2026 (le 30 : rappel
d'erreur AAudio, depuis la révision 715d38f57745409f7b97b4f1eb70b848d3c2e4c8). Les fichiers adaptés gardent
leurs avis de copyright, de modification et leur licence **GPL-3.0-or-later**.

| Fichier RavenEmu | Sources amont et parties adaptées |
| --- | --- |
| `android/src/main/cpp/hardware/audio/aaudio/aaudio_driver.c` | `audio/drivers/aaudio.c` : `aaudio_data_cb`, ouverture PCM, configuration des buffers et fermeture du stream ; depuis la [révision 715d38f57745409f7b97b4f1eb70b848d3c2e4c8](https://github.com/libretro/RetroArch/blob/715d38f57745409f7b97b4f1eb70b848d3c2e4c8/audio/drivers/aaudio.c), `aaudio_error_cb` et la génération du stream |
| `android/src/main/cpp/hardware/video/gles2/gles2_renderer.c` | `gfx/drivers/gl2.c` : chargement et mise à jour de texture, branche GLES des images logicielles ; `gfx/drivers_shader/shader_glsl.c` : compilation et liaison ; `gfx/drivers/gl_shaders/modern_opaque.glsl.vert.h` et `.frag.h` : shaders opaques de base |

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
Les convertisseurs de fréquence (linéaire et sinc, `audio_pipeline.c` et
`sinc_resampler.c`) restent ceux de RavenEmu.

Le rappel d'erreur AAudio (`aaudio_error_cb`) signale seulement qu'un
périphérique a disparu (casque débranché, Bluetooth, USB), en ignorant le
signalement d'un stream déjà remplacé ; le propriétaire de l'émulation ferme
et rouvre le stream, jamais le rappel lui-même. La logique de réouverture,
réessayée environ une fois par seconde en gardant la pause, est celle de
RavenEmu.

Le pilote vidéo reçoit les pixels RGBA logiciels de RavenCore, alloue la
texture quand sa géométrie change et la met à jour par `glTexSubImage2D`.
Un rafraîchissement sans image nouvelle réutilise la texture. Le filtrage
nearest, le cadrage centré et l'orientation sont conservés. Les pixels CPU
restent accessibles aux miniatures d'états. Les échecs GL sont retentés.

RavenEmu ajoute à cet adaptateur le cadrage en pixels entiers et les uniforms
luminosité/contraste, ainsi que la purge logicielle lors d'un changement de
gain ou de mute dans l'adaptateur audio. Ces modifications restent sous la
licence indiquée dans chaque fichier ; elles ne sont pas attribuées à l'amont.

Le frontend, la boucle d'émulation et les menus restent ceux de RavenEmu ;
RavenCore reste le moteur GB/GBC/GBA. Les resamplers sinc, les presets de
shaders, la sélection de périphériques, les cores graphiques matériels et la
planification de RetroArch ne sont pas portés : le filtre sinc de RavenEmu
(`android/src/main/cpp/hardware/audio/sinc_resampler.c`) est écrit pour lui,
d'après la méthode classique d'interpolation à bande limitée et une fenêtre
de Kaiser, sans aucune ligne de `libretro-common/audio/resampler`. Le calage sur l'écran et le
contrôle dynamique du débit audio reprennent l'idée publiée par l'auteur de
RetroArch, mais leur code (`frontend/src/frame_pacer.c`,
`android/src/main/cpp/hardware/audio/audio_pipeline.c`) est écrit pour
RavenEmu, sous sa licence AGPLv3 ; aucune ligne de `audio/audio_driver.c`
n'y est reprise. De même, les images préventives reprennent l'idée des
« preemptive frames » de RetroArch (`runahead.c`), mais leur code
(`frontend/src/libretro_host.c`, garde de coût dans `frontend/src/frame_pacer.c`)
est écrit pour RavenEmu ; aucune ligne de `runahead.c` n'y est reprise. La
validation sur téléphone reste un travail de migration.

Le thème NVIDIA de **Paramètres → Interface → Thème** reprend les valeurs de
couleur du thème « NVIDIA Shield » du menu MaterialUI de RetroArch
(`menu/drivers/materialui.c`, `materialui_theme_nvidia_shield`, [révision
d48b3aacabf8b09a7307a26f5fca78d06a8488ef](https://github.com/libretro/RetroArch/blob/d48b3aacabf8b09a7307a26f5fca78d06a8488ef/menu/drivers/materialui.c)).
Seules des valeurs hexadécimales sont reprises, rangées sur les rôles de
RavenEmu dans `android/src/main/java/com/ravenemu/app/ui/common/RavenTheme.java`
et `android/src/main/res/values/styles.xml` ; aucun code de MaterialUI n'est
porté. NVIDIA et Shield sont des marques de NVIDIA Corporation ; RavenEmu
n'est ni affilié à NVIDIA ni approuvé par elle.

## Licences et distribution

Le [texte intégral GPLv3](../../android/src/main/assets/licenses/GPL-3.0.txt)
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
[android/README.md](../../android/README.md). Les dépendances
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
