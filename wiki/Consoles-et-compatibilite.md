# Consoles et compatibilité

Consultez la [[matrice de compatibilité détaillée des jeux|Compatibilite-des-jeux]] pour les résultats associés à une version, un appareil et une empreinte précise.

## Game Boy

Le moteur Game Boy comprend notamment:

- CPU Sharp LR35902 avec instructions principales et CB;
- gestion du délai `EI`, de `HALT` et de `STOP`;
- rendu du fond, de la fenêtre et des sprites;
- audio à quatre canaux;
- timers, interruptions et DMA OAM;
- cartouches ROM seule, MBC1, MBC2, MBC3 avec horloge et MBC5.

### Limites connues

- pas de câble link connecté à un second appareil;
- pas de multicartouches MBC1M;
- certains comportements matériels rares de l'audio ne sont pas reproduits;
- les timings PPU très fins restent approximatifs dans certains cas.

## Game Boy Color

Les cartouches GB et GBC partagent encore l'implémentation principale du cœur, qui active le matériel couleur d'après l'octet `0x0143` de l'en-tête. Le dépôt possède toutefois une cible C++ `gbc_raven_core` dédiée, des tests matériels GBC propres et commence à extraire les composants spécifiques sous `cores/gbc`.

Trois modes de cartouche sont distingués: monochrome, compatible couleur (`0x80`) et couleur exigée (`0xC0`). L'extension du fichier n'entre pas en compte: un `.gbc` peut contenir une cartouche monochrome, un `.gb` une cartouche couleur.

Une cartouche déclarant des fonctions couleur bénéficie notamment de:

- deux banques VRAM et huit banques WRAM;
- palettes couleur 15 bits et attributs de tuiles;
- rendu PPU cadencé par dots, avec durée de transfert sensible au décalage horizontal, à la fenêtre et aux sprites;
- restrictions d'accès VRAM, OAM et palettes pendant les phases concernées du LCD;
- OAM DMA progressif;
- GDMA et HDMA progressifs avec blocage du CPU pendant les transferts;
- mode double vitesse via `KEY1` et transition déclenchée par `STOP`;
- port série bit par bit avec horloge interne normale, horloge rapide CGB et horloge externe;
- registre infrarouge `RP` (`FF56`) modélisé dans le cœur;
- MBC5 rumble relié à la vibration Android;
- sortie couleur ARGB.

### Limites connues

- le PPU est désormais cadencé par dots mais certains délais du fetcher, de la fenêtre et des sprites restent des approximations et ne sont pas présentés comme cycle-perfect;
- le registre `OPRI` (`FF6C`) n'est pas encore émulé;
- le port série possède l'horloge externe côté cœur, mais aucun câble link entre deux sessions ou appareils n'est encore fourni;
- le port infrarouge est modélisé logiquement, sans backend matériel entre appareils Android;
- certains comportements audio rares restent simplifiés;
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

## Nintendo DS

La Nintendo DS apparaît dans la bibliothèque : un fichier `.nds` est reconnu, son en-tête est lu, la console a sa page et son skin. **Aucun jeu du commerce ne démarre encore.** Ce qui est en place :

- deux processeurs, ARM946E-S et ARM7TDMI, entrelacés par un ordonnanceur;
- leurs cartes mémoire, la mémoire partagée et la communication entre les deux;
- décors en mode texte et sprites ordinaires des deux moteurs 2D;
- balayage des deux écrans, empilés dans un tampon unique de 256 sur 384;
- amorçage d'une cartouche depuis son en-tête, minuteries, transferts autonomes, touches.

### Limites connues

- les appels du programme d'amorçage manquent : une cartouche qui compte sur l'amorceur s'arrête sans rien afficher, ce qui est le cas de la quasi-totalité des jeux du commerce;
- pas de bus de cartouche, donc pas de lecture de données au-delà de ce que l'amorçage recopie;
- pas d'écran tactile : la zone tactile d'un skin est reconnue mais reste inerte;
- pas de moteur 3D, pas de son, pas de sauvegarde de cartouche;
- aucun format d'état instantané : l'enregistrement n'est pas proposé pour cette console;
- les ROM de plus de 128 Mio ne sont pas indexées, la bibliothèque lisant un fichier entier pour en calculer les empreintes.

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
