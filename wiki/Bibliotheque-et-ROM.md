# Bibliothèque et ROM

## Accès aux fichiers

RavenEmu s'appuie sur le Storage Access Framework d'Android. Vous choisissez explicitement les dossiers accessibles. L'application ne demande pas une permission globale sur tout le stockage.

## Analyse de la bibliothèque

Pour chaque fichier reconnu, RavenEmu peut utiliser:

- les informations de l'en-tête de cartouche;
- les empreintes CRC32, SHA-1 et SHA-256;
- la console détectée;
- le nom du fichier;
- une référence importée depuis un fichier No-Intro `.dat` ou un dataset JSON.

Les bases importées contiennent uniquement des métadonnées. Elles ne fournissent aucun jeu.

## Organisation

La bibliothèque propose:

- recherche;
- tri;
- filtre par console;
- vue grille ou liste;
- détection des fichiers ajoutés, déplacés ou supprimés;
- pochettes choisies par l'utilisateur ou placées près des jeux.

Si aucune pochette n'est disponible, RavenEmu peut afficher une jaquette générée localement.

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
