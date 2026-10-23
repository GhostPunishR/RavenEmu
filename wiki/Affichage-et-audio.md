# Affichage et audio

Les réglages partagés sont présents dans l'interface Compose. Leur application et leur persistance sont raccordées sur Android et dans la première session iOS. Sur iOS, l'audio utilise AVAudioEngine ; le routage et les performances restent à valider sur appareil. Les options des services absents y sont masquées.

## Vidéo

Dans **Paramètres → Vidéo** :

| Réglage | Effet |
|---|---|
| Original | Préserve les couleurs produites par le moteur |
| DMG vert | Profil monochrome vert, uniquement pour Game Boy |
| Pocket gris | Profil monochrome gris, uniquement pour Game Boy |
| Correction des couleurs LCD : Game Boy Color | Atténue les couleurs très vives pour retrouver l'aspect plus mat de l'écran d'origine |
| Correction des couleurs LCD : Game Boy Advance | Même correction, réglée à part pour la Game Boy Advance |
| Luminosité | Ajustement de -50 à +50 |
| Contraste | Ajustement de 50 à 150 % |
| Rétablir l'affichage d'origine | Original, corrections LCD coupées, luminosité 0 et contraste 100 % |

Les jeux GBC et GBA restent en couleur : les profils DMG vert et Pocket gris ne les concernent pas. La correction LCD, désactivée par défaut, ne touche jamais un jeu Game Boy. Luminosité et contraste s'appliquent aux trois consoles.

Le lecteur respecte les dimensions fournies par le moteur et affiche l'image sans lissage : **160 × 144** pour GB/GBC, **240 × 160** pour GBA.

## Audio

Dans **Paramètres → Audio**, activez ou coupez le son et réglez son volume de 0 à 100 %. Couper le son ne désactive pas l'horloge d'émulation.

Le moteur fournit de l'audio stéréo PCM16. Android le joue via `AudioTrack`, avec rééchantillonnage à bande limitée, préremplissage et régulation de dérive. Le jeu et le son sont suspendus quand l'application passe en arrière-plan ou que le panneau de jeu est ouvert.

Les corrections audio doivent être évaluées à l'écoute sur appareil. Une compilation réussie ne prouve pas la qualité du son.

## Diagnostics

Dans **Paramètres → Général**, **Afficher les performances** active les mesures du lecteur : frames calculées par seconde, cible du moteur, temps de calcul natif et de conversion vidéo.

Ces valeurs sont des diagnostics, **pas une garantie de FPS affichés à l'écran**. Le menu du jeu propose aussi **Exporter le diagnostic audio**. Vérifiez le contenu avant de joindre un export à un rapport public.

Pour les craquements, décalages ou lenteurs, consultez [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md).

Sources : [réglages](https://github.com/GhostPunishR/RavenEmu/blob/main/shared/src/commonMain/kotlin/com/ravenemu/shared/SettingsScreen.kt) et [sortie audio Android](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/audio/AndroidGbAudioOutput.kt).
