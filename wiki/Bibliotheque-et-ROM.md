# Bibliothèque et ROM

## Accès aux fichiers

RavenEmu s'appuie sur le Storage Access Framework d'Android. Vous choisissez explicitement les dossiers accessibles. L'application ne demande pas une permission globale sur tout le stockage.

## Analyse de la bibliothèque

Pour chaque fichier reconnu, RavenEmu utilise:

- les informations de l'en-tête de cartouche;
- les empreintes CRC32, SHA-1 et SHA-256;
- la console détectée;
- le nom du fichier.

Rien n'est téléchargé et aucune base extérieure n'est nécessaire.

## Organisation

La bibliothèque propose:

- une page par console, que l'on fait défiler horizontalement. Les cartouches Game Boy monochromes et couleur ont chacune la leur, et une page « Tous les jeux » ouvre la marche dès qu'il y a plus d'une console. Une console sans jeu n'a pas de page;
- recherche, dans un champ permanent sous la barre;
- tri par titre ou par taille;
- vue grille ou liste;
- détection des fichiers ajoutés, déplacés ou supprimés;
- pochettes choisies par l'utilisateur ou placées près des jeux.

Si aucune pochette n'est disponible, RavenEmu peut afficher une jaquette générée localement.

En vue grille, le titre emprunte sa couleur à la pochette qu'il accompagne, éclaircie si besoin pour rester lisible sur le fond sombre.

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
