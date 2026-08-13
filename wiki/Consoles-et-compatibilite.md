# Consoles et compatibilité

Consultez la [[matrice de compatibilité détaillée des jeux|Compatibilite-des-jeux]] pour les résultats associés à une version, un appareil et une empreinte précise.

## Game Boy

Le moteur Game Boy comprend notamment:

- CPU Sharp LR35902 avec instructions principales et CB, accès bus ordonnancés
  par M-cycle et timings officiels vérifiés exhaustivement;
- délai `EI`, `DI`, interruptions, `RETI`, bug de `HALT` et `STOP`;
- rendu du fond, de la fenêtre et des sprites par fetcher et FIFO BG/OBJ
  séparés, avec données OAM/VRAM échantillonnées pendant le fetch;
- audio à quatre canaux;
- timer à détection de front et fenêtre de rechargement TIMA;
- interruptions et DMA OAM progressif;
- cartouches ROM seule, MBC1/MBC1M, MBC2, MBC3 avec horloge et MBC5;
- boot ROM utilisateur optionnelle via la fabrique C++ et démarrage HLE sans
  firmware;
- liaison série locale déterministe entre deux instances du cœur.

### Limites connues

- aucun backend Android, réseau ou Bluetooth n'expose encore le câble link;
- certains comportements matériels rares de l'APU restent à reproduire;
- le timing résiduel d'une annulation OBJ par écriture `LCDC.1` et certaines
  courses d'activation LCD propres aux révisions restent à valider sur matériel;
- les contrôleurs MMM01, MBC6, MBC7, HuC1, HuC3, TAMA5 et Camera restent refusés
  explicitement;
- l'injection d'une boot ROM n'est pas encore exposée dans l'interface Android;
- le fallback HLE cible DMG ABC/MGB et CGB ABCDE; DMG0, SGB, SGB2 et AGB ne
  disposent pas encore de profils de démarrage sélectionnables;
- avec une boot ROM utilisateur, les valeurs réellement non initialisées au
  power-on sont normalisées à zéro plutôt que randomisées.

## Game Boy Color

Les cartouches GB et GBC partagent les composants réellement communs, mais le
modèle physique n'est plus déduit uniquement de l'octet `0x0143`. La fabrique
publique distingue DMG, CGB natif et CGB exécutant une cartouche DMG en mode de
compatibilité. La cible `gbc_raven_core` force un CGB physique et possède ses
propres tests matériels, tandis que les composants spécifiques continuent leur
extraction sous `cores/gbc` sans dupliquer le PPU.

Trois modes de cartouche sont distingués: monochrome, compatible couleur (`0x80`) et couleur exigée (`0xC0`). L'extension du fichier n'entre pas en compte: un `.gbc` peut contenir une cartouche monochrome, un `.gb` une cartouche couleur.

Une cartouche déclarant des fonctions couleur bénéficie notamment de:

- deux banques VRAM et huit banques WRAM;
- palettes couleur 15 bits et attributs de tuiles;
- rendu PPU cadencé par dots avec FIFO BG/OBJ séparés, fetch OBJ phasé et durée
  de transfert sensible au décalage horizontal, à la fenêtre et aux sprites;
- restrictions d'accès VRAM, OAM et palettes pendant les phases concernées du LCD;
- OAM DMA progressif;
- GDMA et HDMA progressifs avec blocage du CPU pendant les transferts;
- mode double vitesse via `KEY1` et transition déclenchée par `STOP`;
- port série bit par bit avec diviseur libre aligné au reset, horloge interne
  normale, horloge rapide CGB et horloge externe;
- registre `OPRI` (`FF6C`) et priorité objet DMG/CGB;
- registres PCM `FF76`/`FF77`;
- registre infrarouge `RP` (`FF56`) modélisé et connectable entre deux instances;
- MBC5 rumble relié à la vibration Android;
- sortie couleur ARGB.

### Limites connues

- certaines courses sub-M-cycle de transition LCD, de fin d'annulation OBJ et
  de modification de registres en milieu de ligne restent à confronter aux
  différentes révisions matérielles;
- le verrouillage vidéo et les cas anormaux de `STOP` pendant la transition de
  vitesse CGB restent à reproduire au niveau des révisions matérielles;
- la palette HLE du mode de compatibilité CGB est générique et ne reproduit pas
  encore la sélection de palette propre aux différentes boot ROMs;
- le link et l'infrarouge fonctionnent entre deux machines dans le même
  processus, sans backend entre sessions Android ou appareils;
- les variantes d'enveloppes « zombie », de longueur CGB-02 et quelques
  particularités analogiques propres aux révisions restent simplifiées;
- les contrôleurs de cartouche exotiques non implémentés sont refusés plutôt que simulés incorrectement.

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
