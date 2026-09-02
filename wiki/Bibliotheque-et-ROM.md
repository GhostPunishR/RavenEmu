# Bibliothèque et ROM

## Accès aux fichiers

RavenEmu s'appuie sur le Storage Access Framework d'Android. Vous choisissez explicitement les dossiers accessibles. L'application ne demande pas une permission globale sur tout le stockage.

## Analyse de la bibliothèque

Pour chaque fichier reconnu, RavenEmu utilise:

- les informations de l'en-tête de cartouche;
- les empreintes CRC32, SHA-1 et SHA-256;
- la console détectée;
- le nom du fichier.

Les extensions reconnues sont `.gb` et `.gbc` pour la Game Boy, `.gba` pour la Game Boy Advance, `.nds` pour la Nintendo DS. L'extension n'est qu'une indication : c'est l'en-tête du fichier qui décide, et un fichier dont l'en-tête ne décrit pas une cartouche est écarté avec sa raison.

Une ROM est lue entièrement pour en calculer les empreintes. Les fichiers dépassant ce que le moteur de leur console accepte sont ignorés : 8 Mio en Game Boy, 32 Mio en Game Boy Advance, 128 Mio en Nintendo DS.

Rien n'est téléchargé et aucune base extérieure n'est nécessaire.

## Contrôle d'intégrité

Une cartouche porte ses propres sommes de contrôle. RavenEmu les recalcule sur le fichier et compare:

- **Intègre**: l'en-tête et la somme de contrôle globale correspondent au contenu. Le fichier est exactement celui que la cartouche déclare.
- **Modifiée**: l'en-tête est valide mais le contenu ne correspond plus. Traduction, correctif, ROM hack ou copie abîmée: la cartouche ne permet pas de distinguer ces cas.
- **En-tête vérifié**: cas des cartouches Game Boy Advance et Nintendo DS, dont l'en-tête ne comporte aucune somme couvrant le contenu. Le contrôle n'engage alors que l'en-tête.
- **En-tête douteux**: la somme de contrôle de l'en-tête ne correspond pas à l'en-tête lui-même.

Ce contrôle dit si le contenu correspond à ce que la cartouche déclare. Il ne dit pas de quel jeu il s'agit, ni si la copie est légitime: une cartouche ne porte pas cette information.

## Organisation

La bibliothèque propose:

- une page par console, que l'on fait défiler horizontalement. Les cartouches Game Boy monochromes et couleur ont chacune la leur, et une page « Tous les jeux » ouvre la marche dès qu'il y a plus d'une console. Une console sans jeu n'a pas de page;
- recherche, dans un champ permanent sous la barre;
- tri;
- vue grille ou liste;
- détection des fichiers ajoutés, déplacés ou supprimés;
- pochettes choisies par l'utilisateur ou placées près des jeux.

Si aucune pochette n'est disponible, RavenEmu peut afficher une jaquette générée localement.

En vue grille, le titre emprunte sa couleur à la pochette qu'il accompagne, éclaircie si besoin pour rester lisible sur le fond sombre. Une cartouche dont l'intégrité n'est pas établie porte une pastille de couleur dans le coin de sa jaquette ; une cartouche intègre n'est pas décorée.

## Bonnes pratiques

- conservez des noms de fichiers stables;
- gardez les jeux et les sauvegardes dans des emplacements sauvegardés;
- évitez de retirer l'accès Android au dossier;
- relancez l'actualisation après un déplacement important;
- conservez une copie externe de vos sauvegardes.

## Confidentialité

RavenEmu n'envoie pas les ROM, les empreintes ou les pochettes vers un service distant. La bibliothèque reste locale.

## Fichier absent de la bibliothèque

Consultez [[Dépannage|Depannage]] pour vérifier l'autorisation du dossier, le format et l'actualisation de l'index.
