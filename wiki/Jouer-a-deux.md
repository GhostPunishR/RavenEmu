# Jouer à deux

RavenEmu propose deux façons de relier deux jeux par un câble link :

- **Sur un téléphone** : deux jeux tournent en même temps, sur le même téléphone. Le cas typique : échanger des Pokémon entre vos propres sauvegardes, par exemple entre Rubis et Saphir, ou faire évoluer un Pokémon par échange.
- **Entre deux téléphones** : chacun joue sur son téléphone, et le câble passe par le Wi-Fi. Voir [Entre deux téléphones](#entre-deux-téléphones-câble-sans-fil).

## Sur un téléphone

### Lancer une partie à deux

1. Dans la bibliothèque, touchez le premier jeu.
2. Choisissez **Jouer à deux (câble link)**.
3. Choisissez le second jeu dans la liste.

Le premier jeu est le joueur 1. Sur Game Boy Advance, c'est aussi la console parente des jeux multijoueurs.

### Jeux compatibles

| Premier jeu | Second jeu possible |
|---|---|
| Game Boy ou Game Boy Color | Game Boy ou Game Boy Color |
| Game Boy Advance | Game Boy Advance |

Les deux jeux doivent être différents : une cartouche n'a qu'une sauvegarde, et deux copies d'un même jeu se l'écraseraient. Le câble Game Boy Advance vers Game Boy Color n'existe pas sur console, et RavenEmu ne le propose pas non plus.

### Pendant la partie

- **Affichage** : un seul jeu est affiché, en grand, comme lorsqu'il tourne seul. La barre du haut indique lequel.
- **Changer de jeu** : le bouton **⇄ Joueur 2** (ou **⇄ Joueur 1**), en haut à droite, affiche l'autre jeu. Celui qu'on quitte continue de tourner : la connexion entre les deux reste établie.
- **Commandes** : les commandes tactiles et une manette pilotent le jeu affiché.
- **Son** : seul le jeu affiché s'entend.
- **Sauvegardes** : chaque jeu enregistre sa sauvegarde interne, comme lorsqu'il tourne seul. Elle est écrite régulièrement, en arrière-plan et à la sortie.
- **États instantanés** : ils ne sont pas proposés à deux. Sauvegardez dans chaque jeu.
- **Quitter** : la flèche de retour ramène à la bibliothèque. Chaque jeu se relance ensuite seul, avec sa sauvegarde.

### Limites

- Faire tourner deux consoles demande deux fois plus de calcul. Sur un téléphone modeste, deux jeux Game Boy Advance peuvent ralentir.
- Le câble Game Boy Advance suit le matériel au cycle près pour les échanges multijoueurs (Pokémon, Mario Kart, Four Swords…). Le mode normal, plus rare, est approché.
- Les combats et échanges en ligne de Pokémon (Union Room, adaptateur sans fil) ne sont pas émulés.

## Entre deux téléphones (câble sans fil)

Deux téléphones Android connectés **au même réseau Wi-Fi** se relient comme par un câble link. Il n'y a rien à lancer : jouez normalement, et le câble se branche quand le jeu en a besoin.

### Comment ça se passe

1. Chacun ouvre son jeu, avec sa propre sauvegarde. Par exemple : Pokémon Rouge sur un téléphone, Pokémon Bleu sur l'autre.
2. Dans le jeu, allez là où il se sert du câble link (le Centre Troc d'un Centre Pokémon, par exemple). RavenEmu affiche alors **Câble link : recherche d'un autre téléphone…**.
3. Dès que l'autre téléphone en fait autant, le câble se branche seul : **Câble relié à** suivi du modèle de l'autre téléphone.
4. Si plusieurs téléphones attendent en même temps, RavenEmu demande lequel relier. Chaque téléphone y figure sous son modèle et un code à quatre caractères ; le vôtre est rappelé dans la fenêtre.

### Jeux compatibles

| Votre jeu | Jeu de l'autre téléphone |
|---|---|
| Game Boy ou Game Boy Color | Game Boy ou Game Boy Color |
| Game Boy Advance | Game Boy Advance |

Deux téléphones seulement : les jeux Game Boy Advance à trois ou quatre joueurs ne sont pas proposés.

### Pendant la partie

- **Pause** : ouvrir le menu du jeu met aussi l'autre jeu en pause, tant que le câble sert. L'autre téléphone affiche **Partenaire en pause**.
- **Débrancher** : le menu du jeu indique le téléphone relié et propose **Débrancher**. Ce téléphone n'est plus relié jusqu'à ce que vous changiez de jeu.
- **États instantanés** : on peut en enregistrer, mais pas en charger tant que le câble est branché. L'autre jeu ne suivrait pas et l'échange en cours serait cassé.
- **Fin** : le câble se débranche quand l'autre téléphone quitte son jeu, passe en arrière-plan ou perd le réseau. Le jeu affiche alors sa propre erreur de liaison, comme avec un vrai câble retiré. Le câble se rebranche seul dès que les deux jeux s'en servent de nouveau.

### Réglage

**Paramètres → Général → Câble link sans fil**, activé par défaut. Désactivé, le téléphone ne cherche jamais de partenaire.

### Réseau et confidentialité

- Le téléphone ne s'annonce sur le réseau local que pendant que le jeu se sert de son câble. L'annonce indique le modèle du téléphone, la console (Game Boy ou Game Boy Advance) et un identifiant tiré au hasard à chaque lancement de RavenEmu. Elle n'indique jamais le jeu.
- Une fois les deux téléphones reliés, ils échangent le nom de leur jeu, pour l'afficher dans le menu, puis les données du câble. Rien ne passe par Internet ni par un serveur.
- Il faut le même réseau Wi-Fi. Le partage de connexion d'un des deux téléphones peut convenir, selon les appareils. Un réseau qui isole les appareils entre eux (Wi-Fi invité, certains réseaux publics) empêche la liaison.

### Limites

- Chaque échange de données attend un aller-retour réseau. Sur un bon Wi-Fi, un échange Pokémon prend quelques secondes de plus. Un réseau lent ou chargé peut faire ralentir un jeu Game Boy Advance pendant les échanges.
- À valider entre deux vrais téléphones : les échanges Pokémon des générations 1 et 2 (Game Boy et Game Boy Color) et 3 (Game Boy Advance).
