# Installation

## Télécharger l'APK Debug

La branche `main` produit automatiquement un APK de test:

**[Télécharger RavenEmu Debug](https://github.com/GhostPunishR/RavenEmu/releases/download/debug-latest/RavenEmu-debug.apk)**

Son empreinte SHA-256 est disponible à la même adresse avec l'extension `.sha256`:

[RavenEmu-debug.apk.sha256](https://github.com/GhostPunishR/RavenEmu/releases/download/debug-latest/RavenEmu-debug.apk.sha256)

La préversion `debug-latest` est remplacée après chaque construction réussie de `main`. Le lien reste donc identique, mais le fichier et son empreinte changent.

## Installer sur Android

1. Téléchargez l'APK depuis le lien officiel.
2. Ouvrez le fichier depuis les téléchargements Android.
3. Autorisez temporairement l'installation depuis cette source si Android le demande.
4. Vérifiez que l'application installée se nomme RavenEmu.

L'APK porte l'identifiant `com.ravenemu.app.debug` et utilise une signature de développement. Il est destiné aux essais, pas à une distribution de production.

## Mettre à jour

Une nouvelle version Debug peut normalement être installée par-dessus une version Debug précédente. Si Android signale une signature incompatible, l'ancienne application provient probablement d'une autre source ou d'un autre type de construction.

Avant toute désinstallation:

- copiez les sauvegardes importantes;
- vérifiez l'emplacement choisi pour les fichiers;
- notez les réglages personnalisés.

Une désinstallation peut supprimer les données internes de l'application.

## Compiler soi-même

Les développeurs peuvent produire l'APK avec le Gradle Wrapper. Consultez la page [[Compilation]].

## Contenu non fourni

RavenEmu ne télécharge et ne distribue ni ROM ni BIOS. Utilisez uniquement des copies de jeux que vous êtes autorisé à employer ou des homebrews librement distribués.
