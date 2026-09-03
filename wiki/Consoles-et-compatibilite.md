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

La Nintendo DS apparaît dans la bibliothèque : un fichier `.nds` est reconnu, son en-tête est lu, la console a sa page et son skin. Ce qui est en place :

- deux processeurs, ARM946E-S et ARM7TDMI, entrelacés par un ordonnanceur;
- leurs cartes mémoire, la mémoire partagée et la communication entre les deux;
- décors en mode texte et sprites ordinaires des deux moteurs 2D;
- balayage des deux écrans, empilés dans un tampon unique de 256 sur 384;
- amorçage d'une cartouche depuis son en-tête, minuteries, transferts autonomes, touches;
- les services du programme d'amorçage : attente d'interruption, division, racine, somme de contrôle, recopies, et les cinq formats de décompression;
- le bus de cartouche, par lequel un jeu lit la suite de sa ROM;
- le port série : l'écran tactile, les réglages enregistrés dans la console et la commande d'alimentation.

### Les services du programme d'amorçage

Un jeu ne se contente pas de son propre code : il demande au programme d'amorçage d'attendre le retour vertical, de diviser, de décompresser ses données. Sans personne pour répondre, il ne plante pas, il attend une réponse qui ne vient jamais.

**Ce programme n'est pas fourni avec RavenEmu et ne peut pas l'être** : c'est du code de la console. Les services sont réécrits d'après la description publique de leur comportement, et aucun octet n'en est copié. Le vecteur d'interruption, lui, porte six instructions écrites pour RavenEmu, que le processeur émulé exécute vraiment.

### Le bus de cartouche

L'amorçage ne recopie que les deux blocs de code que l'en-tête décrit. Tout le reste d'un jeu, décors, sprites, musiques, niveaux, et le code chargé en cours de route, se lit par ce bus, à la demande.

Un transfert se fait mot par mot : le jeu écrit une commande de huit octets, indique la taille du bloc voulu, puis vide le port autant de fois qu'il y a de mots, ou arme un canal de transfert autonome sur ce moment précis et laisse le matériel le faire pendant qu'il travaille. Les deux chemins sont en place.

Deux commandes sont servies : lire à une adresse, et demander l'identifiant de la puce. Les autres appartiennent aux phases d'amorçage de la console, qui chiffrent leurs échanges avec des clés que RavenEmu ne contient pas et ne peut pas contenir. Une commande non servie rend un bus au repos et est signalée, plutôt que de rendre un contenu inventé.

Un seul processeur tient le port à la fois, et c'est un registre du processeur principal qui en décide. L'autre lit alors une cartouche absente.

### Le port série, et les trois puces qui y pendent

Trois choses passent par un seul fil : l'**alimentation**, qui allume les écrans et dit où en est la batterie ; la **mémoire de réglages**, où le jeu lit le nom du joueur, sa langue et l'étalonnage de la dalle ; et le **convertisseur de l'écran tactile**. Un jeu du commerce a besoin des trois au démarrage, et son code du processeur secondaire s'y adresse avant d'afficher quoi que ce soit.

Un bus série n'a pas de lecture ni d'écriture, il a des **échanges** : chaque octet écrit en fait entrer un autre au même instant. Ce qu'un programme lit est donc la réponse à l'octet qu'il vient d'envoyer, jamais à celui qu'il s'apprête à envoyer, et c'est cette avance d'un octet qui donne au protocole sa forme.

### Les réglages de la console

**Le programme d'amorçage graphique de la console n'est pas fourni et ne peut pas l'être** : c'est du code de la console. RavenEmu n'en a pas besoin, puisqu'il amorce une cartouche directement d'après son en-tête.

Le bloc de réglages, lui, n'est pas du code : c'est une structure décrite publiquement, que RavenEmu **remplit avec ses propres valeurs**, somme de contrôle comprise. Aucun octet n'en est relevé sur une console.

### L'écran tactile, d'un bout à l'autre

La dalle ne rend pas des pixels mais des mesures brutes sur douze bits, et c'est le jeu qui les traduit avec l'étalonnage enregistré dans les réglages. Les deux moitiés sont donc construites l'une pour l'autre : la mesure rendue, passée dans la formule d'un jeu avec les valeurs que RavenEmu inscrit, retombe au pixel près sur l'endroit touché. Une vérification l'éprouve sur toute la largeur de l'écran.

Un doigt posé sur la zone tactile d'un skin arrive jusque-là : la zone rend une position, la console la convertit en pixels, et le convertisseur la rend au jeu sous la forme du matériel.

### Ce qui reste entre un programme et un jeu

Un programme qui n'a besoin que des organes ci-dessus démarre, produit une image, lit sa cartouche et répond au doigt. Ce qui manque encore à un jeu du commerce n'est plus un organe par lequel il s'arrête, mais ce qu'il montre et ce qu'il garde : le son, le moteur 3D, les modes vidéo restants et la sauvegarde de cartouche.

### Limites connues

- l'état qu'un vrai programme d'amorçage laisse derrière lui n'est pas reproduit : un programme qui monte sa propre pile démarre, un programme qui compte sur l'amorceur ne démarre pas;
- aucun chiffrement de cartouche : les commandes des phases d'amorçage de la console ne sont pas servies;
- pas de puce de sauvegarde : son registre existe mais n'est relié à rien, et son accès est signalé;
- les réglages de la console ne se modifient pas depuis un jeu : aucune écriture de la mémoire de réglages n'est servie, faute de fichier derrière elle;
- le microphone passe par le convertisseur de l'écran tactile mais n'est relié à aucune entrée : son canal est signalé;
- aucune durée sur le port série : le bit d'occupation ne se lève jamais, un échange étant fini dès qu'il est demandé;
- pas de moteur 3D, pas de son, pas de sauvegarde de cartouche;
- aucun format d'état instantané : l'enregistrement n'est pas proposé pour cette console;
- les ROM de plus de 512 Mio ne sont pas prises en charge, ce qui dépasse la plus grosse cartouche produite.

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
