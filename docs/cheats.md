# Cheats RavenEmu

## Première version : GameShark GB/GBC, cartouches couleur uniquement

RavenEmu implémente le format de patch RAM GameShark décrit par Pan Docs :

```text
ABCDEFGH

AB    banque de RAM externe
CD    nouvelle valeur sur 8 bits
GHEF  adresse mémoire, comprise entre A000 et DFFF
```

Ainsi, `010238CD` sélectionne la banque de RAM externe `01` et écrit `02` à
l'adresse `CD38`. Les deux octets d'adresse sont donc écrits dans le code dans
l'ordre faible, puis fort.

Référence technique publique :
[Pan Docs — Game Genie/Shark Cheats](https://gbdev.io/pandocs/Shark_Cheats.html).
L'implémentation RavenEmu est originale et ne reprend aucun moteur de cheats
d'un autre émulateur.

### Syntaxe acceptée

- exactement huit chiffres hexadécimaux après normalisation ;
- lettres minuscules ou majuscules ;
- espaces, tabulations et tirets ignorés à l'intérieur d'une ligne ;
- banque externe `00` à `0F` ;
- adresse `A000` à `DFFF`.

Un retour à la ligne sépare deux codes : un cheat peut ainsi regrouper plusieurs
écritures. Chaque ligne est validée séparément avant d'être persistée ou
transmise au cœur.

Les préfixes `90`/`91` parfois publiés pour certains appareils GameShark Color
ne font pas partie du format RAM documenté ci-dessus et ne sont pas interprétés
par cette version. Ils sont refusés au lieu d'être silencieusement transformés.

### Configuration et persistance

Le frontend conserve des définitions génériques (`id`, nom, format, lignes et
état activé) dans le stockage privé Android :

```text
files/cheats/<sha256-de-la-rom>.json
```

`CheatStore` contrôle l'empreinte, la version du document, les limites de taille
et chaque ligne de code à la lecture comme à l'écriture. Un document absent ou
corrompu donne une liste vide ; il n'est jamais interprété comme une préférence
globale.

Après une modification, Android persiste la liste puis poste une commande à
`EmulationSession`. La session remplace la liste active sur son propre thread,
y compris lorsqu'elle est en pause pendant l'affichage des dialogues. Le cœur
natif reste l'unique composant qui écrit dans la mémoire émulée.

### Sémantique mémoire

- `A000-BFFF` écrit directement dans la banque de RAM de cartouche indiquée par
  `AB`, sans modifier les registres actifs du MBC. La banque et la taille réelle
  de la RAM sont contrôlées avant l'écriture.
- `C000-CFFF` écrit dans la banque WRAM fixe.
- `D000-DFFF` écrit dans la banque WRAM actuellement sélectionnée par la
  machine CGB. Le champ `AB`, qui décrit la RAM externe, est sans effet sur
  cette plage.

Le cœur vérifie de nouveau format, banque et adresse : une chaîne venant de JNI
n'est jamais tenue pour valide sur la seule foi de l'interface Android.

### Point d'application déterministe

Les écritures actives sont appliquées par le cœur natif après l'exécution
exacte d'une trame émulée et avant la remise du framebuffer au frontend. Ce
point correspond au comportement de réécriture périodique à VBlank décrit pour
le GameShark, sans timer Android, thread auxiliaire ni écriture mémoire depuis
l'interface.

La liste active appartient au frontend et reste en dehors des save states :

- un reset reconstruit la machine mais conserve la liste active du cœur ;
- un chargement de save state remplace la machine, pas la configuration des
  cheats ;
- pause, frame skipping et cadence n'ajoutent aucun point d'application ; une
  écriture a lieu exactement une fois par trame réellement exécutée.

### Capacité exposée

Le cœur unifié Game Boy annonce ce format uniquement après le chargement d'une
cartouche dont le mode matériel réel est CGB. `ConsoleType.GAME_BOY` reste
l'unique type public pour GB et GBC ; aucun `ConsoleType.GAME_BOY_COLOR` n'est
réintroduit.

Formats non pris en charge dans cette première version :

- Game Genie GB/GBC ;
- variantes GameShark Color `90`/`91` non documentées par Pan Docs ;
- GameShark ou Action Replay Game Boy Advance ;
- codes conditionnels, patches ROM et formats d'autres consoles.
