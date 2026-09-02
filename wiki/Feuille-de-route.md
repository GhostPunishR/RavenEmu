# Feuille de route

Cette page présente les priorités générales. Les issues et pull requests restent la source la plus précise pour le travail en cours.

## Réalisé

- moteur Game Boy;
- compatibilité Game Boy Color;
- audio, vidéo, timers et principaux contrôleurs de cartouche;
- bibliothèque locale avec empreintes et pochettes;
- contrôles tactiles configurables et manettes physiques;
- profils d'écran monochrome;
- sauvegardes `.sav` et états instantanés;
- moteur Game Boy Advance intégré;
- APK Test publié automatiquement, signé par une clé dédiée et stable, avec empreintes et provenance vérifiables.

## Priorités actuelles

- étendre les tests sur appareils Android;
- améliorer les performances Game Boy Advance;
- valider davantage de jeux avec des copies obtenues légalement;
- améliorer la précision des timings;
- compléter les fonctions vidéo et audio GBA;
- renforcer les outils de diagnostic;
- documenter les résultats de compatibilité.

## Nintendo DS

Le travail est amorcé mais aucune image n'est encore émulée. Ce qui existe
aujourd'hui :

- l'identité de la console et son identifiant persisté;
- le décodage et le contrôle de l'en-tête de cartouche;
- le contrat vidéo, deux écrans de 256 sur 192 empilés dans un tampon unique;
- le contrat audio annoncé par le matériel;
- le processeur principal ARM946E-S, ses deux jeux d'instructions (ARM 32 bits,
  `CLZ`, `BLX` et arithmétique saturante comprises, et Thumb 16 bits) et le
  passage de l'un à l'autre, éprouvés contre une mémoire de test;
- son coprocesseur système : mémoires locales, base des vecteurs d'exception,
  attente d'interruption, registres de protection et de cache;
- la carte mémoire vue par ce processeur : mémoire principale, mémoire commune,
  palette, mémoire d'objets et les neuf banques vidéo par leur fenêtre de
  transfert;
- le processeur secondaire ARM7TDMI, jeu ARMv4T, servi par la même
  implémentation que le principal, avec les différences d'architecture nommées
  et éprouvées une à une;
- sa carte mémoire à lui, sa mémoire de travail propre, et la mémoire que les
  deux processeurs se partagent avec son découpage en quatre parts
  complémentaires;
- la communication entre les deux processeurs : le registre de synchronisation,
  les deux files de seize mots, leurs erreurs de débordement, et le contrôleur
  d'interruptions de chaque côté qui transforme un message déposé en
  interruption réellement prise;
- l'aiguillage des neuf banques vidéo, décodé banque par banque, qui décide de
  ce que chaque moteur trouve à une place donnée;
- les décors en mode texte des deux moteurs 2D : tuiles de huit sur huit, seize
  ou deux cent cinquante-six couleurs, retournements, quatre tailles de carte,
  défilement, et la résolution des priorités entre les quatre plans et le fond;
- leurs sprites ordinaires : cent vingt-huit objets, douze formats, les deux
  profondeurs de palette, les deux retournements, les deux rangements de tuiles,
  le repli sur les bords, et leur composition avec les décors, un sprite passant
  devant un décor de même priorité;
- le contrôleur d'affichage : le compteur de 263 lignes dont 192 affichées, un
  registre d'état par processeur avec ses propres autorisations et sa propre
  ligne guettée sur neuf bits, les trois interruptions du balayage, le rendu
  d'une trame entière dans le tampon empilé, et l'échange des deux écrans
  commandé par le registre d'alimentation;
- l'ordonnanceur qui fait tourner tout cela ensemble : les deux processeurs
  avancent entrelacés instruction par instruction, le principal deux fois pour
  une du secondaire comme le veut le rapport de leurs horloges, le faisceau
  avance entre les lignes, et chaque ligne se dessine à son passage plutôt que
  la trame entière à la fin. Les deux processeurs savent s'arrêter, chacun par
  le chemin que lui donne le matériel, et une source autorisée en attente les
  relance sans que l'autorisation générale ait à être donnée;
- l'amorçage d'une cartouche : les deux binaires copiés à leurs adresses de
  chargement, les deux processeurs pointés sur leurs points d'entrée. Une
  cartouche synthétique démarre, ses deux processeurs se relaient, et l'écran
  montre le résultat.
- les quatre minuteries de chaque processeur : quatre diviseurs d'horloge, le
  reste de division conservé pour ne pas dériver, le rechargement repris à
  l'allumage et à chaque débordement, l'enchaînement d'une minuterie sur les
  débordements de la précédente, et le réveil du seul processeur qui l'a demandé.
- les quatre canaux de transfert autonome de chaque processeur : les deux
  largeurs d'unité, les trois façons dont chaque adresse évolue, le compte qui
  s'entend en unités et dont l'étendue diffère des deux côtés, la répétition, et
  surtout le moment du départ, les moments dont l'organe manque étant comptés
  plutôt que déclenchés au hasard.
- les touches : les dix de la face avant lisibles des deux côtés, les deux
  supplémentaires et le contact de l'écran tactile du seul côté du processeur
  secondaire, la convention active à zéro, et le réveil que chaque processeur
  règle pour lui-même, avec ses deux conditions;
- les skins Delta de Nintendo DS : la console reconnue à son identifiant, les
  deux touches supplémentaires, le cadre des deux écrans empilés, et la zone
  tactile reconnue puis laissée inerte au lieu d'être prise pour une croix
  directionnelle;
- la place de la console dans l'application : un fichier `.nds` est reconnu à
  son extension puis à son en-tête, indexé dans la bibliothèque avec son titre,
  son code jeu et ses empreintes, rangé dans sa propre page, et lancé sur le
  cœur que le pont natif construit pour lui. L'en-tête est relu côté Kotlin pour
  que la bibliothèque n'ait pas à démarrer un moteur par fichier parcouru, et une
  vérification du dépôt compare les deux lectures ainsi que les identifiants de
  console de part et d'autre du pont;
- les services du programme d'amorçage, rendus sans ce programme : attente
  d'interruption, division, racine entière, somme de contrôle, les deux recopies,
  le dépaquetage de bits et les cinq formats de décompression. L'appel logiciel
  est intercepté avant son vecteur ; l'interruption, elle, passe par six
  instructions écrites pour RavenEmu et placées dans la région du programme
  d'amorçage, que le processeur émulé exécute vraiment;
- le bus de cartouche : la commande de huit octets, la taille du bloc et ses deux
  valeurs particulières, la cadence mot par mot que le jeu observe, le partage du
  port entre les deux processeurs, l'interruption de fin, et le moment de
  transfert autonome sur lequel un jeu arme un canal pour ne pas avoir à scruter
  le port entre chaque mot.

Le budget d'instructions accordé à une ligne repose sur une convention dite en
toutes lettres : une instruction par cycle de l'horloge maître, faute d'un modèle
de durée. Les 2130 cycles d'une ligne, eux, sont ceux du matériel.

L'amorçage ne pose que ce que l'en-tête décrit. L'état qu'un vrai programme
d'amorçage laisse derrière lui, piles et mémoires locales comprises, n'est pas
modélisé : ses valeurs ne sont affirmées nulle part dans ce dépôt, et les
inventer serait une affirmation que rien ne vérifie. Une cartouche qui monte sa
propre pile démarre ; une cartouche qui compte sur l'amorceur ne démarre pas.

Un programme qui n'a besoin que de ces organes démarre désormais, produit une
image et lit sa cartouche. Un jeu du commerce, non : il s'adresse tôt au port
série, par lequel passent l'écran tactile, les réglages enregistrés dans la
console et la commande d'alimentation. C'est ce qui manque le plus à présent.
Restent ensuite les décors tournants et les modes étendus, les sprites tournants
et semi-transparents, le moteur 3D, les fenêtres et les mélanges, les palettes
étendues, la sauvegarde et le son.
L'enregistrement d'un état reste refusé par une erreur nommée, faute de format ;
l'application ne le propose donc pas pour cette console, plutôt que de l'offrir
et d'échouer. La console est en revanche entrée dans la bibliothèque : un jeu s'y
range et se lance, et c'est ce qui rend le manque visible plutôt que théorique.

## Pistes futures

- effets vidéo au milieu d'une ligne;
- mosaïque Game Boy Advance;
- détails audio matériels supplémentaires;
- BIOS Game Boy Advance fourni par l'utilisateur avec validation stricte;
- nouvelles consoles sous forme de modules indépendants.

Une piste n'est pas une promesse de date ou de version. Ouvrez une [issue](https://github.com/GhostPunishR/RavenEmu/issues) pour discuter d'une proposition avant son développement.
