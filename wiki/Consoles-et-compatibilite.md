# Consoles et compatibilité

## Game Boy

Le moteur Game Boy comprend notamment:

- CPU Sharp LR35902 avec instructions principales et CB;
- gestion du délai `EI` et du comportement `HALT`;
- rendu du fond, de la fenêtre et des sprites;
- audio à quatre canaux;
- timers, interruptions et DMA OAM;
- cartouches ROM seule, MBC1, MBC2, MBC3 avec horloge et MBC5.

### Limites connues

- pas de câble link;
- pas de multicartouches MBC1M;
- certains comportements matériels rares de l'audio ne sont pas reproduits;
- pas d'effets modifiant le rendu au milieu d'une ligne.

## Game Boy Color

Le moteur Game Boy Color étend le moteur Game Boy avec:

- banques VRAM et WRAM;
- palettes couleur 15 bits;
- attributs de tuiles;
- HDMA et GDMA;
- mode double vitesse;
- sortie couleur ARGB.

### Limites connues

- timing HDMA HBlank simplifié;
- séquenceur audio non doublé en mode double vitesse;
- registre `OPRI` non émulé;
- rendu LCD couleur encore simplifié.

## Game Boy Advance

Le moteur Game Boy Advance est expérimental. Il comprend:

- CPU ARM7TDMI avec modes ARM et Thumb;
- modes vidéo bitmap 3, 4 et 5;
- arrière-plans texte et affines;
- sprites, fenêtres et effets de couleur;
- interruptions, quatre timers et quatre canaux DMA;
- audio PSG et Direct Sound;
- SRAM, Flash 64 ou 128 Kio et EEPROM;
- BIOS HLE avec les appels courants, dont plusieurs routines de décompression;
- temps d'attente mémoire, accès séquentiels et préchargement Game Pak simplifiés.

### Limites connues

- compatibilité à vérifier jeu par jeu;
- performances encore insuffisantes sur certains appareils;
- mosaïque et effets au milieu d'une ligne non émulés;
- DMA de capture vidéo absent;
- interruptions clavier et série absentes;
- `SOUNDBIAS` et certains détails de la mémoire d'onde absents;
- aucun BIOS externe fourni par l'utilisateur n'est chargé.

## Signaler une incompatibilité

Indiquez:

- le modèle de l'appareil;
- la version Android;
- la version ou le commit RavenEmu;
- la console concernée;
- l'empreinte SHA-256 ou CRC32 de votre propre fichier;
- les étapes précises;
- le résultat attendu et le résultat observé;
- une capture sans contenu personnel si elle est utile.

Ne joignez jamais de ROM, de BIOS ou de sauvegarde personnelle à une issue publique.
