# Skins de contrôleur

RavenEmu lit le **format de fichier** `.deltaskin` pour importer des panneaux de
commandes que vous fournissez vous-même. Le fichier reste local à l'appareil :
RavenEmu ne télécharge aucun skin et n'en distribue aucun dans l'APK.

> RavenEmu n'est ni affilié à Delta, ni approuvé ou soutenu par ses auteurs. La
> prise en charge de `.deltaskin` est une compatibilité de format, rien d'autre.
> Les skins appartiennent à leurs auteurs respectifs : vérifiez leurs conditions
> avant de les réutiliser ou de les redistribuer.

## Compatibilité de cette première version

Sont pris en charge :

- Game Boy et Game Boy Color en portrait ;
- Game Boy Advance en portrait ;
- représentations iPhone `standard` et `edgeToEdge` ;
- assets PDF déclarés par `assets.resizable` ;
- D-pad, A, B, Start, Select et Menu ;
- L et R pour la Game Boy Advance ;
- boutons combinés et multitouch.

Le paysage continue d'utiliser les commandes RavenEmu actuelles. Les
métadonnées paysage du manifeste sont conservées pour une évolution future,
mais l'asset n'est pas proposé ni rendu. Les skins DS, NES, SNES et N64, les
thumbsticks, les écrans tactiles et les filtres CoreImage ne sont pas pris en
charge.

## Importer un skin

1. Ouvrez **Paramètres**, puis **Interface**.
2. Dans **Contrôles tactiles**, ouvrez **Skins de contrôleur**.
3. Touchez **Importer un fichier .deltaskin**.
4. Choisissez un fichier portant l'extension `.deltaskin`.
5. Vérifiez la console et les représentations indiquées, puis sélectionnez le
   skin.

Le sélecteur Android peut afficher des fichiers de type générique. RavenEmu
vérifie ensuite l'extension, la signature ZIP, `info.json`, la console, les
assets référencés et le nombre de pages des PDF. L'application copie l'archive
dans son stockage privé et ne dépend plus de l'URI d'origine.

Si le même identifiant est déjà installé, RavenEmu demande confirmation avant
de le remplacer.

## Standard ou bord à bord

Trois modes sont disponibles :

- **Automatique** choisit `edgeToEdge` sur une zone portrait haute et
  `standard` sur une zone plus courte ;
- **Standard** préfère la représentation `standard` ;
- **Bord à bord** préfère `edgeToEdge`.

Si la représentation préférée est absente, l'autre est utilisée
automatiquement. Le panneau PDF garde toujours son ratio et reste ancré en bas.
L'écran du jeu est centré dans la zone noire restante avec le ratio natif de la
console.

## Gérer et supprimer les skins

La page groupe les skins en **GB/GBC** et **GBA**. Les sélections portrait sont
indépendantes : un skin GBA ne peut pas être utilisé pour une session GB/GBC,
et inversement.

Touchez **Commandes classiques** pour désactiver le skin personnalisé. La
suppression retire l'archive et ses assets du stockage privé. Si le skin
supprimé était actif, RavenEmu revient aux commandes classiques.

Un manifeste corrompu ou un PDF devenu illisible provoque également ce retour
automatique. Les profils de disposition classiques ne sont ni supprimés ni
modifiés.

## Retour de pression

Les boutons font déjà partie du PDF. RavenEmu ne les redessine pas et ne les
déplace pas. Un calque léger peut seulement signaler la hitbox pressée ; il se
désactive dans **Paramètres → Interface → Contrôles tactiles → Retour visuel des
skins**.
La vibration utilise le réglage tactile existant.

## Limites de sécurité

Une archive importée est traitée comme non fiable. RavenEmu refuse notamment :

- les chemins absolus, `..`, liens symboliques et archives imbriquées ;
- plus de 64 entrées ;
- une archive de plus de 25 Mio ;
- plus de 50 Mio décompressés ;
- un `info.json` de plus de 1 Mio ;
- un asset de plus de 25 Mio ;
- un PDF de plusieurs pages.

Les entrées de métadonnées macOS (`__MACOSX`, `.DS_Store`, noms commençant par
`._`) sont ignorées.
