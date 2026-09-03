# Architecture RavenEmu V2

RavenEmu V2 utilise les chemins physiques comme frontières d'architecture. Un
module Gradle n'est jamais redirigé vers un ancien dossier avec `projectDir`.

```text
RavenEmu/
├── app/
│   └── android/
├── cores/                    # 100 % C++
│   ├── common/
│   ├── gb/
│   ├── gbc/
│   ├── gba/
│   └── nds/
├── native/
│   ├── api/
│   └── jni/
├── engine/                   # Kotlin/JVM pur
│   ├── api/
│   ├── runtime/
│   ├── session/
│   ├── state/
│   ├── save/
│   ├── audio/
│   └── diagnostics/
├── platform/
│   └── android/
│       ├── audio/
│       ├── renderer/
│       ├── input/
│       ├── storage/
│       ├── vibration/
│       └── lifecycle/
├── features/
│   ├── library/
│   ├── player/
│   ├── settings/
│   ├── skins/
│   ├── savestates/
│   └── diagnostics/
├── build-logic/
├── gradle/
├── tools/
├── docs/                     # site officiel RavenEmu
└── settings.gradle.kts
```

## Règles de dépendances

```text
app/android
    ↓
features + platform/android
    ↓
engine
    ↓
native/api + native/jni
    ↓
cores
```

- `cores/` ne contient que du C++ et ne connaît ni JNI, ni JVM, ni Android.
- `native/api` expose la frontière native générique ; `native/jni` est le seul endroit qui connaît JNI.
- `engine/*` reste Kotlin/JVM pur et ne dépend jamais d'Android.
- `platform/android/*` contient les services dépendants du système Android.
- `features/*` contient les fonctions produit ; les features JVM restent indépendantes de la plateforme.
- `app/android` est la composition finale et la coque UI Android.
- `docs/` est réservé aux fichiers du site officiel RavenEmu.

## Cœurs C++

`cores/common` porte le contrat `Core`, les primitives binaires et SHA-256.

`cores/gb` porte encore l'implémentation principale commune GB/GBC. Le mode CGB
est sélectionné à partir de l'en-tête de la cartouche et conserve la même identité
persistée que la Game Boy afin de ne pas casser la bibliothèque ou les sauvegardes.

`cores/gbc` est désormais une vraie bibliothèque statique `gbc_raven_core`, avec
ses propres tests matériels. L'extraction est progressive plutôt qu'une copie du
cœur GB : les composants spécifiques déjà déplacés comprennent notamment le
contrôleur de double vitesse et le port infrarouge. Le PPU, les DMA, le port série
et les autres organes restent encore partagés avec l'implémentation GB pendant
leur séparation sous tests de parité.

Le dossier porte donc **deux** cibles, et la distinction commande la suite de
l'extraction :

- `gbc_hardware` rassemble les composants CGB déjà extraits. C'est elle que
  `gb_raven_core` lie, parce que l'implémentation unifiée DMG/CGB les consomme.
- `gbc_raven_core` est la façade publique du cœur couleur ; elle s'appuie sur
  `gb_raven_core`.

Les deux arêtes vont en sens inverse et sont toutes les deux justes ; une cible
unique créerait un cycle. `gbc_hardware` reste INTERFACE tant que les composants
extraits ne sont que des en-têtes, et devient STATIC dès que l'un d'eux gagne un
`.cpp` — sans qu'aucune autre cible n'ait à changer.

`cores/gba` porte le moteur Game Boy Advance indépendant.

`cores/nds` est une **fondation**, pas encore un moteur. Il porte l'identité de
la console, décode et contrôle l'en-tête de cartouche, publie le contrat vidéo
et audio, exécute les deux processeurs de la console avec leurs jeux
d'instructions et le coprocesseur système du principal, décode les cartes
mémoire que chacun voit, les fait dialoguer, dessine les décors et les sprites de
ses deux moteurs graphiques, balaie ses deux écrans, fait tourner tout cela
ensemble, et **démarre une cartouche** : les deux binaires sont chargés à leurs
adresses, les deux processeurs partent de leurs points d'entrée, ils alternent au
rythme de leurs horloges, le faisceau avance entre eux, et une trame se dessine
ligne par ligne. `run_frame` produit donc une image.

Ce que cette image vaut est une autre question : il manque encore les minuteries,
les transferts autonomes, les entrées, les appels du programme d'amorçage, le bus
de cartouche et le moteur 3D. La plupart des cartouches réelles s'arrêteront donc
tôt. Ce qui refuse encore franchement, c'est l'enregistrement d'un état, faute de
format.

`cores/nds/src/cpu` tient les deux processeurs. Ils ne connaissent pas la carte
mémoire de la console : ils passent par une frontière `Bus` abstraite, ce qui
permet de les éprouver contre une simple mémoire de test — sans cartouche, sans
banques vidéo — et donc de distinguer une faute du processeur d'une faute de la
machine autour.

**Une seule implémentation les sert.** Ce n'est pas une économie de lignes :
deux copies dériveraient l'une de l'autre, et une correction apportée à l'une
laisserait l'autre avec l'ancienne faute. Ce qui les sépare tient dans une
révision d'architecture, `Architecture`, nommée et consultée aux quelques
endroits où elle compte — ces endroits sont ainsi énumérables, ce qu'une
duplication interdirait. `Arm9` et `Arm7` ne sont que ce cœur commun instancié
avec l'une ou l'autre.

Le processeur principal est un ARM946E-S, jeu ARMv5TE. Le secondaire est un
ARM7TDMI, jeu ARMv4T : il tient l'amorçage, le son, l'écran tactile et la
liaison sans fil, et son jeu est plus étroit — ni `BLX`, ni `CLZ`, ni
arithmétique saturante, ni doubles mots, ni coprocesseur. Ces absences sont
celles du matériel, et les instructions correspondantes lèvent l'exception
d'instruction indéfinie comme sur console. Une différence est plus insidieuse
que les autres : **charger le compteur de programme entrelace sur ARMv5 et pas
sur ARMv4T**, si bien qu'un même `LDR PC` change de jeu d'instructions sur l'un
et reste où il est sur l'autre.

Les deux jeux d'instructions sont complets de part et d'autre : ARM 32 bits et
Thumb 16 bits, avec le passage de l'un à l'autre dans les deux sens. Un jeu de
la console alterne sans cesse entre eux — le code compact en Thumb, les
gestionnaires d'interruption en ARM — si bien qu'un cœur qui n'en connaîtrait
qu'un ne ferait rien tourner. Ils partagent le décaleur, les indicateurs et le
banc de registres ; ils diffèrent surtout sur un point, que le code isole :
Thumb écrit ses indicateurs d'office, là où ARM demande un bit `S` explicite.

Le coprocesseur système CP15 accompagne le seul processeur principal, et c'est
par lui que celui-ci cesse d'être un simple exécuteur d'instructions. Le
processeur secondaire n'en a aucun, et la distinction est portée par un pointeur
nul plutôt que par un objet inerte : un coprocesseur qui répond « rien » n'est
pas la même chose qu'un coprocesseur absent. Trois choses y sont observables
depuis le processeur : les mémoires locales, qui ne sont pas sur le bus mais
dans le cœur et répondent avant lui ; la base de la table des vecteurs, que le
logiciel déplace ; et l'attente d'interruption, qui arrête le cœur au lieu de le
faire tourner à vide. Sans les mémoires locales, la carte mémoire de la console
ne peut pas être juste, parce que les mêmes adresses désignent autre chose selon
qu'une mémoire locale les couvre ou non.

Les caches ne sont pas modélisés, et les opérations qui les vident sont donc
acceptées sans effet. L'unité de protection est tenue mais pas appliquée : ses
registres s'écrivent et se relisent fidèlement, parce que le logiciel les relit,
mais aucun accès n'est refusé faute d'un chemin d'exception d'abandon où le
refus aurait un sens.

Les multiplications signées de la variante DSP et le point d'arrêt matériel ne
sont pas écrits : ils sont décodés et comptés comme non implémentés plutôt que
passés sous silence, parce qu'une instruction inconnue exécutée sans bruit donne
un jeu qui part à la dérive sans qu'on sache où. Aucune durée n'est comptée non
plus — une instruction par pas, sans cache et sans attente de bus — la justesse
temporelle demandant un modèle de durée que rien ne consomme encore.

`cores/nds/src/memory` porte les cartes mémoire, c'est-à-dire ce à quoi mène une
adresse. Il y en a **deux**, une par processeur, et elles ne voient pas la même
chose : le processeur secondaire ignore la palette, la mémoire d'objets et la
plupart des banques vidéo, et dispose en propre de soixante-quatre kilooctets de
mémoire de travail que l'autre ne voit pas.

Ce qu'elles partagent vit dans `SystemMemory` : la mémoire principale et la
mémoire commune. Les tenir là plutôt que dans l'une des deux cartes est ce qui
permet qu'une écriture faite par un processeur soit vue par l'autre, sans quoi
la communication entre eux serait impossible à écrire.

Rien n'y est acquis : le même nombre désigne deux choses différentes selon la
configuration, et trois mécanismes y pourvoient. Le partage de la mémoire commune
répartit trente-deux kilooctets entre les deux processeurs en quatre découpages
**complémentaires** — ce que l'un reçoit, l'autre ne l'a pas — dont deux ne
laissent rien à l'un d'eux, et « rien » est un état légitime, pas une panne. Les
deux fenêtres sont calculées depuis le même registre, de sorte qu'aucun découpage
ne puisse rendre les deux processeurs propriétaires du même octet. Privé de sa
part, le processeur secondaire ne se retrouve pas devant une fenêtre muette :
elle donne alors sur sa mémoire propre, et un programme qui s'y adresse continue
de fonctionner. Les mémoires locales du processeur principal, enfin, ne passent
jamais par sa carte : il les consulte avant le bus, si bien qu'une adresse peut
ne rien désigner là tout en lui répondant très bien. Le reste est du miroir,
parce que le matériel ne décode pas les bits hauts.

Les neuf banques vidéo ne vivent plus ici : elles sont passées à
`cores/nds/src/video`, avec leur aiguillage, parce qu'une banque n'est pas une
mémoire à une adresse fixe. Le BIOS, la cartouche et le port Game Boy Advance ne
sont pas décodés, faute de contenu à leur donner.

`cores/nds/src/system` porte ce qui n'appartient à aucun des deux processeurs
mais les relie. C'est ici que la console cesse d'être deux machines côte à côte :
les deux cartes mémoire donnaient déjà sur les mêmes octets, mais rien ne
permettait à l'un de dire à l'autre qu'il y avait écrit.

Deux mécanismes, et ils ne se remplacent pas. Le registre de synchronisation
porte quatre bits dans chaque sens : ce que l'un écrit, l'autre le relit à
l'autre bout du registre, de sorte que les deux côtés voient le même nombre aux
champs échangés près. Il sert aux échanges brefs — un état, un accusé, une étape
d'amorçage. Les deux files portent seize mots chacune, une par sens, et servent
aux commandes et à leurs réponses.

**Le destinataire de chaque interruption est le point délicat.** La file qui se
remplit réveille celui qui reçoit ; la file qui se vide réveille celui qui
envoie, puisque c'est lui qui attend de pouvoir en déposer d'autres. Se tromper
de côté donne deux processeurs qui s'attendent l'un l'autre sans fin, et rien
dans le code ne le signalerait : les deux chemins compilent, et seule une suite
qui monte les deux processeurs ensemble peut trancher. Ces réveils se posent sur
un front, non sur un niveau — une file déjà pleine qu'on remplit encore ne
réveille personne une seconde fois.

Déborder une file n'est pas refusé. Le matériel inscrit une erreur, rend la
dernière valeur lue ou écarte le mot, et continue ; le logiciel est censé
consulter cette erreur, qui ne s'efface qu'en écrivant un bit à un. Lever une
exception serait infidèle : un programme qui déborde sa file ne s'arrête pas sur
console.

Le contrôleur d'interruptions de chaque processeur transforme ces demandes en
interruption réellement prise, ou les laisse dormir. Son registre de demandes se
comporte à l'envers de ce qu'on attend — **écrire un bit à un l'efface** — parce
que c'est ainsi qu'un gestionnaire acquitte ; l'écrire normalement donnerait des
interruptions qui se redéclenchent sans fin. Il accepte toutes les sources, y
compris celles qu'aucun organe ne pose encore : ni retour de balayage, ni
minuteries, ni transferts autonomes.

Ces registres ont contraint les deux cartes mémoire à connaître la largeur de
l'accès qu'on leur demande, là où elles décomposaient jusqu'ici en octets. Lire
une file est **indivisible** : la décomposer en quatre lectures d'octet la
viderait quatre fois.

**L'ordonnanceur** monte enfin tout cela et le fait avancer. Il n'apporte aucun
organe nouveau : deux processeurs, deux cartes, la mémoire partagée, les files,
les deux moteurs et le balayage existaient déjà, mais chacun attendait qu'on
l'appelle.

Faire tourner un processeur pendant toute une trame puis l'autre donnerait
exactement les mêmes registres à la fin et une console qui ne marche pas, parce
que les deux se parlent en cours de route : celui qui dépose un mot dans une file
et attend la réponse attendrait une trame entière. Les deux avancent donc par
petits pas **alternés**, au plus fin que ce cœur sache faire — une instruction —
et le processeur principal joue deux fois pour une du secondaire, comme le veut
le rapport de leurs horloges. Ce rapport est réel et s'observe ; le nombre
d'instructions accordées à une ligne ne l'est pas.

Aucune instruction ne dure ici : rien ne compte les cycles, ni les attentes de
bus. Le budget d'une ligne repose donc sur une **convention explicite, une
instruction par cycle de l'horloge maître**. Les 2130 cycles d'une ligne sont,
eux, ceux du matériel — 355 points à six cycles — et le jour où les instructions
auront une durée, c'est la convention qui disparaîtra, pas les constantes. La
console tourne ainsi plus vite qu'une vraie ; ce qui est préservé, et qui compte
davantage, c'est le rapport entre les deux processeurs et la place du balayage.

Les deux processeurs savent s'arrêter, et par deux chemins différents que le
matériel impose : le principal par une opération de son coprocesseur, le
secondaire par un registre d'entrée-sortie. L'état d'arrêt appartient donc au
cœur, et non au coprocesseur que l'un des deux n'a pas. Ce qui les relance est en
revanche le même des deux côtés, et **ce n'est pas la condition qui fait prendre
l'interruption** : une source autorisée en attente suffit, sans l'autorisation
générale. Un programme de console coupe couramment cette autorisation avant de
s'arrêter, pour traiter la demande à la main plutôt que par le vecteur ; la lui
imposer pour repartir l'endormirait définitivement.

**L'amorçage** fait ce que l'en-tête de cartouche décrit, et rien de plus : les
deux binaires sont copiés à leurs adresses de chargement, par mots comme le fait
le transfert de cartouche, et les deux processeurs pointés sur leurs points
d'entrée. Amorcer remet d'abord la console à zéro, sans quoi deux exécutions se
mêleraient.

**Ce n'est pas tout ce que le matériel fait**, et l'écart est dit plutôt que
comblé au jugé. Sur console, un programme d'amorçage tourne avant la cartouche et
laisse un état que l'en-tête ne décrit pas : piles des différents modes, mémoires
locales du processeur principal configurées, registres initialisés. Cet état
n'est pas modélisé, faute d'une source qui en fixe les valeurs dans ce dépôt ;
les inventer serait une affirmation que rien ne vérifie. Les deux processeurs
partent donc de leur état de mise sous tension. Un programme qui monte sa propre
pile et démasque lui-même ses interruptions démarre ; un programme qui compte sur
l'amorceur ne démarre pas.

Une conséquence en découle pour la suite : le chargement passe par la carte
mémoire et non par le chemin d'écriture du processeur. Les deux coïncident tant
que les mémoires locales sont éteintes, ce qui est le cas faute d'amorceur pour
les allumer ; le jour où cet état sera modélisé, le chargement devra passer par
le processeur, sinon un binaire destiné à une mémoire locale atterrirait à côté.

**Les quatre minuteries** de chaque processeur donnent enfin à un programme une
horloge plus fine que la trame. Le balayage ne mesurait que des seizièmes de
seconde ; un son se cadence à des dizaines de milliers de fois par seconde, une
attente se compte en microsecondes. Un jeu privé de minuteries ne va pas plus
lentement : il s'arrête, parce qu'il attend un compteur qui ne bouge jamais.

Elles appartiennent à la carte de leur processeur, comme le registre
d'alimentation, et non à un organe partagé : rien de ce qu'elles comptent ne
traverse d'un processeur à l'autre.

Trois choses y comptent plus qu'elles n'en ont l'air. **Le reste de la division
est conservé** d'un pas à l'autre : le jeter ferait dériver une minuterie lente
d'autant plus vite qu'on l'interroge souvent, et la dérive resterait invisible
jusqu'au jour où un jeu compte dessus. **Le registre bas ne dit pas la même
chose dans les deux sens** : on y lit le compteur, on y écrit le rechargement, et
confondre les deux donne soit un temps immobile, soit un jeu qui replace le temps
où il veut. Et **une minuterie peut compter les débordements de celle qui la
précède** plutôt que le temps, ce qui est la seule façon d'obtenir un compteur
plus large que seize bits ; une minuterie éteinte au milieu d'une chaîne la rompt
plutôt que de se laisser enjamber.

Les minuteries avancent à la granularité d'une ligne de balayage, comme tout le
reste : une interruption tombe à une frontière de ligne plutôt qu'à l'instant
exact du débordement. L'allumage ne remet pas la phase du diviseur à zéro, faute
d'une source qui le dise ; l'écart possible vaut moins d'un pas.

**Les quatre canaux de transfert autonome** de chaque processeur copient la
mémoire sans que le processeur y revienne. C'est par là que passent la palette,
la table des sprites et les tuiles à chaque trame : un émulateur qui n'a pas ces
canaux ne montre pas un écran fautif, il montre un écran qui ne se met jamais à
jour.

**Le point délicat est le moment où un canal part.** À l'allumage pour un départ
immédiat, ou au moment demandé : retour vertical, retour horizontal. Les autres
moments désignent des organes qui n'existent pas encore, et un canal qui les
demande est **compté** plutôt que parti au mauvais instant — un transfert
déclenché trop tôt est plus difficile à diagnostiquer qu'un transfert qui n'a pas
lieu. Le champ qui porte ce moment n'a pas la même largeur des deux côtés, si
bien que les mêmes bits disent « retour vertical » au processeur principal et
« départ immédiat » au secondaire.

Un canal peut se réarmer après chaque transfert, et c'est ainsi qu'un jeu obtient
une copie à chaque trame sans y revenir. La répétition n'a de sens qu'avec un
moment : un départ immédiat qui se répéterait tournerait sans fin. Des deux
façons dont une arrivée évolue, l'une revient à son point de départ à chaque
tour et l'autre poursuit son chemin.

Un transfert a lieu **d'un coup, entre deux instructions**, là où le matériel
l'entrelace avec le processeur en lui volant des cycles. La différence
s'observerait sur un programme qui lit la zone d'arrivée pendant qu'elle se
remplit ; elle ne s'observe pas sur un programme qui attend la fin, ce que fait
le logiciel ordinaire. Un canal armé par un moment de fin de ligne est servi au
premier pas de la ligne suivante, exactement comme l'interruption posée au même
instant.

**Les touches** sont enfin lisibles. Les dix de la face avant se lisent des deux
côtés, à la même adresse ; les deux supplémentaires, le contact de l'écran
tactile et celui du couvercle **ne se lisent que du côté du processeur
secondaire**, et un jeu qui veut les connaître doit les lui demander par la file.
Cette asymétrie est celle du matériel, et elle explique pourquoi l'état vit dans
un organe partagé plutôt que dans l'une des deux cartes.

Les deux registres sont **actifs à zéro** : une touche tire une ligne vers la
masse, et une ligne que rien ne tire se lit à un. Inverser la convention ne donne
pas une console en panne mais un jeu qui part tout seul, toutes touches enfoncées.
La même raison décide de ce que rendent les bits sans emploi, et c'est une
conséquence plutôt qu'une affirmation de plus.

Chaque processeur règle son propre réveil par les touches, avec deux conditions
qui ne se ressemblent pas : dès qu'une des touches choisies est enfoncée, ou
seulement quand **toutes** le sont. La seconde sert aux combinaisons. Une
sélection vide ne réveille jamais, dans les deux cas : en mode combinaison,
l'ensemble vide serait sinon satisfait par n'importe quel état. Le réveil tient
sur un niveau et non sur un front, si bien qu'une touche gardée enfoncée le
redemande après chaque acquittement.

Les coordonnées de l'écran tactile ne sont pas là : elles ne passent pas par un
registre mais par un convertisseur que le processeur secondaire interroge en
série, et cet organe n'existe pas encore. Le contact lui-même l'est, puisqu'il
est porté par un registre. Les deux touches supplémentaires sont modélisées dans
le matériel mais **hors de portée de l'interface publique**, dont l'énumération
décrit une manette à dix touches commune à tous les cœurs.

`cores/nds/src/video` porte les neuf banques, leur aiguillage, les deux moteurs
graphiques 2D et le contrôleur d'affichage.

Ce matériel est **partagé par les deux processeurs**, et non possédé par l'un
d'eux. Il vivait d'abord dans la carte du processeur principal, ce qui suffisait
tant que lui seul y touchait ; le contrôleur d'affichage a changé cela, le
processeur secondaire lisant l'état du balayage et se faisant réveiller par lui.
Laisser ce matériel chez l'autre aurait obligé une carte à dépendre de sa
jumelle, alors qu'elles sont paires. C'est la même décision que pour
`SystemMemory`, et pour la même raison.

**Une banque vidéo n'est pas une mémoire à une adresse fixe** : c'est un bloc
qu'on branche quelque part. Le même bloc peut servir de décor au moteur
principal, de sprites au secondaire, de texture au moteur 3D, de palette
étendue, ou être prêté au processeur secondaire — et ce qu'il vaut à une adresse
donnée dépend entièrement de ce branchement. Tant que personne ne lisait ces
banques, les tenir pour de simples tableaux suffisait ; ça cesse dès qu'un
moteur doit y trouver ses décors.

Le décodage est écrit banque par banque, et non ramené à une formule commune :
ni le nombre de destinations, ni la façon dont le champ d'écart les place, ne se
déduisent d'une règle générale. Deux banques n'acceptent que quatre destinations,
cinq en acceptent huit ; certaines se placent par blocs de cent vingt-huit
kilooctets, deux d'entre elles combinent deux bits d'écart qui ne se suivent pas,
et une seule ne commence pas au début de sa fenêtre. Une banque branchée seize
kilooctets trop loin donne un décor faux sans que rien ne le signale.

**Remplir une banque et l'afficher sont exclusifs.** Une banque branchée sur un
moteur quitte la fenêtre de transfert, celle qu'on emprunte pour la remplir ; le
matériel ne les distingue pas d'une banque éteinte, et le code non plus. Quand
deux banques se disputent la même place, le résultat n'est pas défini sur
console : ici la première dans l'ordre répond, et le recouvrement est compté
plutôt qu'absorbé, parce qu'une faute de configuration passée sous silence se
manifeste bien plus loin sous la forme d'un décor faux.

Les deux moteurs partagent une implémentation, pour la raison qui a valu aux deux
processeurs : deux copies dérivent. Le principal place ses décors dans une fenêtre
quatre fois plus grande et décale ses bases par deux champs supplémentaires ; il
reçoit le rendu 3D comme un plan, sait afficher une banque telle quelle et lire
son image depuis la mémoire principale. Le secondaire n'a rien de tout cela.

Sont rendus les **décors en mode texte** : tuiles de huit sur huit, seize ou deux
cent cinquante-six couleurs, retournement dans les deux sens, quatre tailles de
carte, défilement, et la résolution des priorités entre les quatre plans et le
fond. C'est le socle, parce que tous les autres modes s'appuient sur les mêmes
palettes, les mêmes priorités et la même composition.

Et les **sprites ordinaires** : cent vingt-huit objets, douze formats donnés par
deux champs séparés dont le couple ne se déduit ni de l'un ni de l'autre, les
deux profondeurs de palette, les deux retournements, et les deux rangements de
tuiles. Les retournements portent sur le sprite entier et non sur chacune de ses
tuiles, ce qui les distingue de ceux d'un décor. Les deux replis comptent : une
ordonnée sur huit bits fait reparaître en haut un sprite posé bas, une abscisse
sur neuf bits fait revenir par la gauche un sprite posé au-delà du bord droit,
et c'est ainsi qu'un jeu fait entrer ses personnages par les côtés.

**Un sprite passe devant un décor de même priorité.** C'est l'inverse de la règle
entre décors, où le plus petit numéro l'emporte : la comparaison est large d'un
côté, stricte de l'autre, et c'est ce qui met un personnage devant son sol
plutôt que dedans.

Le tampon des sprites couvre les cinq cent douze positions de l'abscisse, non les
deux cent cinquante-six de l'écran. Ce n'est pas du gaspillage : un sprite posé
au bord y dépose ce qui dépasse, la composition ne relit que l'écran, et le
découpage vient donc de la forme du tampon plutôt que d'une condition qu'aucune
image ne permettrait de vérifier.

Une entrée d'attributs à zéro ne décrit pas l'absence de sprite : elle décrit un
sprite de huit sur huit, allumé, posé en haut à gauche. Une table vierge en
dessine donc cent vingt-huit superposés, et tout logiciel de console commence par
les éteindre.

Deux détails y comptent plus qu'ils n'en ont l'air. **La première couleur d'une
palette n'est pas une couleur** : c'est l'absence de pixel, et c'est ce qui
permet à quatre plans de se superposer sans se cacher entièrement ; une
sous-palette déplace les quinze autres couleurs sans déplacer celle-là. Et **à
priorité égale, le plan de plus petit numéro l'emporte**, ce qui tient à une
comparaison stricte : la rendre large cacherait un décor derrière un autre.

Un numéro de plan ne veut rien dire à lui seul : le plan 3 est un décor en
tuiles dans un mode, une surface tournante dans un autre, et n'existe pas dans un
troisième. Une table le dit mode par mode. Les plans qu'un mode ne donne pas ne
sont pas comptés comme manquants, parce que le matériel n'en affiche pas non
plus ; en revanche un plan demandé dans un mode que ce lot ne dessine pas encore
est compté, un plan absent qui ne dit rien se confondant avec un plan vide.

**Le contrôleur d'affichage donne son rythme à la console.** Un jeu n'attend pas
le temps qui passe : il attend le retour du balayage. Sans lui, un moteur
graphique est une fonction que personne n'appelle.

L'écran fait 192 lignes, le balayage en compte 263. Les 71 lignes de différence ne
s'affichent pas, et c'est pendant elles qu'un jeu prépare la trame suivante :
d'où l'importance de l'interruption de retour vertical, la plus utilisée de la
console. **La toute dernière ligne n'est pas comptée comme retour vertical**, ce
qui surprend et compte, un logiciel qui scrute cet indicateur le voyant retomber
une ligne avant la fin.

Le compteur de lignes est unique, puisque c'est un seul faisceau, mais **chaque
processeur a son propre registre d'état**, avec ses propres autorisations : l'un
peut demander à être réveillé au retour vertical sans que l'autre le soit, et
chacun guette la ligne qu'il veut. Le neuvième bit de cette ligne est rangé loin
des huit autres, et les recoller à l'envers ferait guetter une ligne pour une
autre. Les trois indicateurs, eux, se lisent pareil des deux côtés, et ne
s'écrivent pas : les laisser écrire donnerait à un jeu le pouvoir de se mentir
sur la position du faisceau.

Quel moteur alimente quel écran est décidé par un bit du registre
d'alimentation, non par une convention de ce code.

Le balayage avance ici **ligne par ligne**, non point par point. Le retour
horizontal est donc posé une fois par ligne, et l'indicateur correspondant
n'existe pas : à cette granularité toute lecture se fait à une frontière de
ligne, où le faisceau n'est pas en retour horizontal, et prétendre le contraire
serait inventer une position dans la ligne. C'est suffisant pour tout ce qui
s'accroche au retour vertical ou à une ligne donnée ; ce ne le serait pas pour un
effet qui change un registre au milieu d'une ligne.

Une ligne se dessine **au passage du faisceau**, et non toute la trame d'un coup
à la fin. C'est ce qui distingue un balayage d'une capture : un programme qui
change un décor en cours de trame n'agit que sur les lignes qui suivent, et
dessiner à la fin effacerait cette distinction sans rien dire. L'ordre à
l'intérieur d'une ligne compte pour la même raison — les processeurs ont leur
temps **avant** que la ligne se dessine, parce que le gestionnaire réveillé par
le retour horizontal de la ligne précédente s'exécute pendant celle-ci et prépare
ce qu'elle doit montrer.

Ne sont pas rendus : les décors tournants, les modes étendus, la grande image, le
plan 3D, les sprites tournants, la semi-transparence, la fenêtre par sprite, les
sprites en image directe, les fenêtres, les mélanges, la mosaïque et les palettes
étendues.

La distinction entre « pas dessiné » et « dessiné sans son effet » est tenue au
cas par cas plutôt que par une règle générale. Un sprite tournant ou
semi-transparent n'est pas dessiné, parce que le dessiner comme un sprite
ordinaire donnerait une image plausible et fausse ; un sprite mosaïqué l'est,
parce que l'omettre serait plus faux que de le rendre sans son effet. Les deux
sont comptés.

Deux décisions y sont prises, parce qu'elles engagent le reste du projet et
qu'il vaut mieux les arrêter avant d'écrire un moteur autour :

- **Identité persistée.** La console prend l'identifiant 3. Le 1 reste retiré,
  ayant désigné une seconde entrée Game Boy Color, et ne sera jamais réattribué.
- **Deux écrans dans un tampon unique.** Le contrat vidéo de RavenEmu ne décrit
  qu'un écran. Plutôt que de l'élargir pour une seule console, les deux écrans
  sont empilés : l'écran haut occupe les 192 premières lignes, l'écran bas les
  192 suivantes. L'agencement réel — côte à côte, un seul écran, proportions
  libres — reste à la couche qui affiche, seule à connaître l'appareil.

C'est cette pile que `features/skins` encadre : un skin Delta de Nintendo DS
déclare un écran de 256 sur 384, non de 256 sur 192. La taille est portée par
`DeltaSkinConsole`, aux côtés des touches que la console possède réellement, de
sorte qu'une touche dessinée par un skin mais absente de la console ne soit
jamais pressée. La zone tactile qu'un tel skin décrit emprunte la forme d'un
D-pad sans en être un : elle est reconnue à ses deux axes et rend une
**position**, jamais une direction, plutôt que d'être découpée en neuf cases.
Cette position est donnée en fractions de la zone, non en pixels : `features/skins`
ne connaît la résolution d'aucune console, et c'est `ConsoleType` qui porte la
taille de l'écran tactile et fait la conversion. Un skin qui déclare une telle
zone pour une console qui n'a pas de dalle la voit rester inerte et signalée dans
les entrées ignorées : ce qui décide est ce que le matériel possède, non ce que
le skin déclare.

La console est déclarée à l'application comme les deux autres : un
`ConsoleProvider` publié par son module de moteur, ajouté à la racine de
composition, et rien d'autre à retrouver ailleurs. Deux points lui sont propres.

Le premier est que l'en-tête de cartouche est **lu deux fois** : une fois en C++
pour amorcer, une fois en Kotlin pour indexer. Ce n'est pas un oubli. La
bibliothèque parcourt des fichiers sans jamais construire de moteur, et démarrer
un cœur natif pour lire un titre coûterait une allocation par ROM rencontrée. Le
prix de cette seconde lecture est qu'elle peut diverger de la première ; les deux
sont donc tenues aux mêmes refus, et une vérification de `tools/ci-policy`
compare les identifiants de console de part et d'autre du pont natif — un
identifiant qui différerait ferait construire le moteur d'une autre console, ou
relire un état enregistré pour elle.

Le second est qu'un moteur peut n'avoir **pas encore** de format d'état. C'est le
cas de celui-ci : en figer un avant que la console soit complète promettrait une
compatibilité que le prochain organe ajouté briserait. Le contrat le dit
(`EmulatorCore.supportsSaveState`, et son pendant sur `ConsoleProvider` pour
répondre sans construire de moteur), et l'application ne propose pas ce que le
moteur ne tient pas, au lieu de l'offrir et d'échouer au moment où le joueur
enregistre.

### Les services du programme d'amorçage

Un jeu de la console appelle son programme d'amorçage : attendre le retour
vertical, diviser, décompresser. Ce programme est du code de la console, que
RavenEmu ne fournit pas et ne peut pas fournir. Deux chemins sont pris, et la
distinction est délibérée.

**L'appel logiciel est intercepté avant son vecteur.** Le service est rendu hors
du processeur, à partir de la description publique de son comportement, et
l'exécution reprend à l'instruction suivante. Un appel non couvert redescend au
vecteur du matériel plutôt que d'être inventé.

**L'interruption, elle, ne l'est pas.** Le vecteur porte six instructions ARM
écrites pour RavenEmu, que le processeur émulé exécute vraiment : le changement
de mode, l'empilement et le retour restent ceux du matériel, là où les simuler
aurait demandé de refaire à la main ce que le cœur sait déjà. C'est aussi ce qui
rend la région du programme d'amorçage nécessaire dans les deux cartes mémoire,
en haut de l'espace pour le processeur principal et en bas pour le secondaire.

L'attente d'interruption mérite d'être signalée, parce qu'elle explique une
indirection qui semblerait gratuite. Elle ne se termine pas sur l'interruption
mais sur un **mot d'indicateurs que le gestionnaire du jeu tient à jour**. Sans
cette indirection, une attente du retour vertical se terminerait sur un
débordement de minuterie. La boucle est tenue par le compteur de programme, que
l'appel rembobine sur lui-même avant d'arrêter le processeur : au réveil, le
gestionnaire du jeu s'exécute, rend la main sur l'appel, et l'appel se repose la
question.

### Le bus de cartouche

Une décision y mérite d'être écrite : **l'image de la cartouche n'est pas
recopiée**. Le bus la relit à la demande, par une vue sans propriété. Une
cartouche fait jusqu'à cent vingt-huit mégaoctets, et en doubler la présence en
mémoire pour un téléphone n'aurait pas de sens. La contrepartie est une
contrainte de durée de vie, portée par la documentation de `Machine::boot` : la
fabrique du cœur garde l'image, et c'est elle qui la tient vivante.

Le décodage des registres du bus vit sur l'organe, non dans les deux cartes
mémoire. Les deux processeurs voient ces registres aux mêmes adresses, et deux
copies du décodage dériveraient ; les cartes ne gardent que le routage. C'est
aussi ce qui permet au partage du port, qui décide lequel des deux y accède,
d'être appliqué en un seul endroit.

### Le port série

Trois puces très différentes pendent au même fil, et le partage retenu suit cette
différence : `SerialPort` tient le **protocole du bus**, chaque puce tient le
**sien**, et `Firmware` tient à part le **contenu** de la mémoire de réglages.
Contenu et protocole sont séparés parce qu'ils se trompent différemment : un
contenu faux donne un jeu qui affiche le mauvais nom, un protocole faux donne un
jeu qui n'obtient rien du tout.

Ces registres ne vivent pas dans le fichier des registres partagés, contrairement
à ceux du bus de cartouche : le processeur principal ne les voit pas, et une
seule carte les route. Ce qui est partagé y vit ; ce qui ne l'est pas vit chez
son organe.

La décision qui engage le plus est celle de l'**étalonnage de l'écran tactile**.
La dalle rend des mesures brutes, jamais des pixels, et c'est le jeu qui traduit
avec les deux points enregistrés dans les réglages de la console. RavenEmu écrit
ces deux points lui-même, et fait rendre au convertisseur des mesures construites
pour eux : la conversion d'un jeu retombe alors au pixel près sur l'endroit
touché. Les deux moitiés ne sont donc justes qu'ensemble, et une vérification les
éprouve l'une contre l'autre plutôt que chacune contre une constante.

Le contact traverse ensuite quatre couches sans qu'aucune ne devine ce que fait
la suivante : la zone d'un skin rend une fraction, `ConsoleType` la convertit en
pixels, `EmulationSession` la fait passer par la file du thread d'émulation, et
le cœur la ramène dans l'écran avant de la confier au convertisseur.

Les suites natives (`common`, `gb`, `gbc`, `gba`, `nds`) doivent pouvoir être
construites directement avec `cmake -S cores`.

## Frontières plateforme

Les cœurs ne pilotent jamais directement un service Android. Par exemple, une
cartouche MBC5 rumble expose uniquement son état de vibration dans le contrat
moteur. `engine/session` transforme cet état en sortie abstraite et
`platform/android/vibration` est seul responsable du `Vibrator` Android.

Le même principe s'applique aux ports série des consoles et au port infrarouge :
le modèle matériel reste dans le cœur, tandis qu'une future connexion entre
appareils doit passer par une couche plateforme séparée. L'écran tactile de la
Nintendo DS en est l'exemple achevé : le cœur ne connaît qu'un pixel, et c'est
`platform/android/input` qui sait d'où vient le doigt.

## Bibliothèque ROM

La bibliothèque vit dans `features/library`. Ses modèles persistés, `storageId`,
filtres, index et analyseurs restent indépendants de l'UI Android. Les écrans de
bibliothèque vivent encore dans `app/android` et pourront être extraits séparément.

## Chaîne de build

- Android Gradle Plugin : 9.3.1
- Gradle : 9.5.0
- Kotlin : 2.4.10
- compileSdk : 37
- targetSdk : 35
- NDK : 29.0.14206865
- Java/JVM : 17
- C++ : C++20
- CMake : 3.22.1

La documentation utilisateur détaillée se trouve dans le wiki. Le dossier `docs/`
reste réservé au site officiel.
