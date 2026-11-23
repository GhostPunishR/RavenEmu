# RavenEmu — licences et crédits

## RavenEmu et RavenCore

Copyright (C) 2026 GhostPunishR. Licence GNU Affero General Public License, version 3 (onglet « AGPLv3 »). RavenCore reste le moteur d’émulation GB, GBC et GBA.

- [RavenEmu](https://github.com/GhostPunishR/RavenEmu)
- [RavenCore](https://github.com/GhostPunishR/RavenCore)

## Portions adaptées des pilotes AAudio et OpenGL2/GLES2 de RetroArch

Révision amont : `7986022baf6822677ad2cec4bc215069e89bd8ce` ([sources](https://github.com/libretro/RetroArch/tree/7986022baf6822677ad2cec4bc215069e89bd8ce)).

- **Pilote AAudio** : `audio/drivers/aaudio.c`. Copyright (C) 2026 - The RetroArch team. Rappel d'erreur (`aaudio_error_cb`), révision amont `715d38f57745409f7b97b4f1eb70b848d3c2e4c8` ([sources](https://github.com/libretro/RetroArch/tree/715d38f57745409f7b97b4f1eb70b848d3c2e4c8)).
- **Pilote OpenGL2/GLES2** : `gfx/drivers/gl2.c` ; compilation et liaison des shaders : `gfx/drivers_shader/shader_glsl.c` ; shaders de base : `gfx/drivers/gl_shaders/modern_opaque.glsl.vert.h` et `gfx/drivers/gl_shaders/modern_opaque.glsl.frag.h`. Copyright (C) 2010-2014 - Hans-Kristian Arntzen ; Copyright (C) 2011-2017 - Daniel De Matteis ; Copyright (C) 2012-2015 - Michael Lelli ; Copyright (C) 2016-2019 - Brad Parker.

Ces portions sont sous GNU General Public License, version 3 ou, à votre choix, toute version ultérieure (onglet « GPLv3 »). La version 3 est utilisée pour leur combinaison avec les parties AGPLv3 de RavenEmu, conformément à la section 13 des deux licences. Les parties RetroArch restent sous GPL ; la licence propre de RavenEmu et RavenCore reste AGPL.

Adaptation des sous-ensembles de pilotes au frontend et au cycle de vie RavenEmu : GhostPunishR, 29 octobre 2026. Les avis de copyright, licences et modifications sont également conservés dans les fichiers concernés. Le rééchantillonnage propre à RavenEmu reste distinct de ces emprunts. RavenEmu ajoute le réglage de gain/mute, la purge logicielle à leur changement, le cadrage en pixels entiers et les ajustements de luminosité/contraste. Ces adaptations conservent les licences indiquées dans leurs fichiers. Les sources correspondantes et scripts de construction de cette version sont accessibles par le bouton Sources et accompagnent chaque version publiée.

RavenEmu n’est pas le frontend officiel de RetroArch. Le portage ciblé de ces portions ne constitue pas une affiliation, une approbation ou une intégration de l’ensemble de RetroArch.

## Thème NVIDIA

**Paramètres → Interface → Thème** : valeurs de couleur du thème « NVIDIA Shield » du menu MaterialUI de RetroArch (`menu/drivers/materialui.c`, révision `d48b3aacabf8b09a7307a26f5fca78d06a8488ef`). Aucun code n’est repris. NVIDIA et Shield sont des marques de NVIDIA Corporation ; RavenEmu n’est ni affilié à NVIDIA ni approuvé par elle.

## Interface Libretro (libretro.h)

Copyright (C) 2010-2024 The RetroArch team. Licence MIT (onglet « MIT »). Source : `libretro-common@a3194c18ba65ef89734e1898a2f61403c255d368`, via `RavenCore@25982804f3502f2c3397829cfe720c139c635f38`. L’ABI Libretro est indépendante du frontend RetroArch.

## Effets d’écran Lissage net et Grille LCD

Écrits pour RavenEmu d’après sharp-bilinear (Themaister) et lcd3x (Gigaherz), shaders du domaine public de libretro/glsl-shaders, révision `cba01a48b0974a1232bea68d999a7c21bc4c233e`.

## RetroAchievements : SDK officiel rcheevos

Copyright (c) 2018 RetroAchievements.org. Licence MIT (onglet « MIT rcheevos »). Source : [RetroAchievements/rcheevos](https://github.com/RetroAchievements/rcheevos), révision `1433173220a7eaede6a9ed7a18e94117be1821e0`.

Compilé dans la bibliothèque native avec les correctifs de sûreté de la bêta RavenEmu (`frontend/cmake/rcheevos.cmake`) ; le module qui le relie au frontend, au réseau et à l'interface est écrit pour RavenEmu (AGPLv3).

## Glyphe « ® » de la boot ROM Game Boy inclus dans RavenCore

Les 8 octets du glyphe que la boot ROM laisse en mémoire vidéo, repris de la boot ROM de Nintendo avec l'accord du propriétaire de RavenCore. Aucune autre donnée de boot ROM n'est incluse ; le logo affiché à côté vient de l'en-tête de la cartouche chargée.

## Palettes de compatibilité GBC incluses dans RavenCore

Tables issues des contributeurs de The Cutting Room Floor, page « Notes:Game Boy Color Bootstrap ROM », [révision 2183057](https://tcrf.net/index.php?title=Notes:Game_Boy_Color_Bootstrap_ROM&oldid=2183057). Licence [Creative Commons Attribution 3.0 Unported](https://creativecommons.org/licenses/by/3.0/). Modification RavenCore : conversion des couleurs RGB 24 bits en RGB555.

## Bibliothèques Android

**Material Components for Android 1.14.0.** Copyright (C) 2015 The Android Open Source Project ([sources](https://github.com/material-components/material-components-android)). Licensed under the Apache License, Version 2.0 (onglet « Apache 2 »). The Maven library is used without copying or modifying its implementation.

Its AndroidX support libraries are provided by the Android Open Source Project under the Apache License, Version 2.0. The Kotlin standard library used by these dependencies is provided by JetBrains under the same license. RavenEmu's application sources in this module remain Java and native C/C++.

**Google Play services** : play-services-auth 22.0.0 et ses modules (play-services-base, -basement, -tasks, -auth-base, -auth-api-phone, -fido). Copyright Google LLC. Bibliothèques binaires de Google, sous l'[Android Software Development Kit License](https://developer.android.com/studio/terms). Google n'en publie pas les sources. RavenEmu ne s'en sert que pour connecter Google Drive aux sauvegardes cloud, quand le joueur le demande ; elles s'appuient sur les services Google Play du téléphone. kotlinx.coroutines (JetBrains, Apache License 2.0) les accompagne.

## Garantie

Les composants sont fournis sans garantie ; les clauses exactes figurent dans les licences ci-jointes. Leurs notices et droits de redistribution restent applicables aux copies et modifications.
