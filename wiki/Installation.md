# Installation

## Télécharger RavenEmu Test

La branche `main` produit automatiquement un seul APK public optimisé :

**[Télécharger RavenEmu Test](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk)**

Son empreinte SHA-256 est disponible à la même adresse avec l'extension `.sha256` :

[RavenEmu-test.apk.sha256](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk.sha256)

La préversion `test-latest` est remplacée après chaque construction réussie de `main`. Le lien reste identique, mais le fichier et son empreinte changent — l'empreinte du **certificat de signature**, elle, ne change pas.

La publication n'a lieu que si la clé de signature Test du projet est configurée. Tant qu'elle ne l'est pas, la CI refuse de publier plutôt que de diffuser un APK dont la signature changerait à chaque construction, et le lien ci-dessus reste sans cible.

## Installer sur Android

1. Téléchargez l'APK depuis le lien officiel.
2. Ouvrez le fichier depuis les téléchargements Android.
3. Autorisez temporairement l'installation depuis cette source si Android le demande.
4. Vérifiez que l'application installée se nomme RavenEmu.

L'APK Test conserve les diagnostics et laisse Android optimiser le moteur. Il reste destiné aux essais, pas à une distribution de production. Son identifiant d'application est `com.ravenemu.app.profil` : il cohabite avec une éventuelle version Release sans l'écraser.

## Vérifier l'APK avant de l'installer

Chaque préversion publie quatre informations : l'empreinte SHA-256 du fichier, l'empreinte SHA-256 du certificat de signature, le commit source et le nom du package.

```bash
sha256sum -c RavenEmu-test.apk.sha256
apksigner verify --print-certs RavenEmu-test.apk
```

L'empreinte du certificat doit être **identique** à celle des préversions précédentes. Si elle a changé sans annonce, n'installez pas l'APK.

## Mettre à jour

L'APK Test est signé par une clé dédiée et stable : une nouvelle version s'installe par-dessus la précédente, sans désinstallation et sans perte de données.

Si Android refuse la mise à jour en signalant une signature incompatible, l'application déjà installée ne vient pas de ce dépôt, ou date d'avant l'adoption de cette clé dédiée. Dans ce dernier cas, sauvegardez vos données, désinstallez, puis réinstallez : c'est une opération à faire une seule fois.

Avant toute désinstallation :

- copiez les sauvegardes importantes ;
- vérifiez l'emplacement choisi pour les fichiers ;
- notez les réglages personnalisés.

Une désinstallation peut supprimer les données internes de l'application.

## Compiler soi-même

Seul l'APK Test est publié. Un APK Debug se construit localement avec le Gradle Wrapper — voir [[Compilation]] — et ne demande aucun secret de signature. Il n'est pas destiné à circuler.

## Contenu non fourni

RavenEmu ne télécharge et ne distribue ni ROM ni BIOS. Utilisez uniquement des copies de jeux que vous êtes autorisé à employer ou des homebrews librement distribués.
