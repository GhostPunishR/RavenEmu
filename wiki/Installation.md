# Installation

## Télécharger RavenEmu Test

La branche `main` produit automatiquement un seul APK public optimisé :

**[Télécharger RavenEmu Test](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk)**

Son empreinte SHA-256 est disponible à la même adresse avec l'extension `.sha256` :

[RavenEmu-test.apk.sha256](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk.sha256)

La préversion `test-latest` est remplacée après chaque construction réussie de `main`. Le lien reste identique, et le fichier comme son empreinte changent à chaque publication. L'empreinte du **certificat de signature**, elle, ne change pas.

## Installer sur Android

1. Téléchargez l'APK depuis le lien officiel.
2. Ouvrez le fichier depuis les téléchargements Android.
3. Autorisez temporairement l'installation depuis cette source si Android le demande.
4. Vérifiez que l'application installée se nomme RavenEmu.

L'APK Test conserve les diagnostics et laisse Android optimiser le moteur. Il reste destiné aux essais, pas à une distribution de production. Son identifiant d'application est `com.ravenemu.app.profil` : il cohabite avec une éventuelle version Release sans l'écraser.

## Les avertissements d'Android sont normaux

Android affiche des avertissements pour **tout** APK installé en dehors du Play Store. Ils apparaîtront donc à chaque installation de RavenEmu, et ils ne signalent rien d'anormal sur le fichier.

Il faut cependant distinguer deux situations très différentes, que les libellés d'Android ne séparent pas toujours clairement.

### Messages attendus, liés à l'absence d'information

**« Autoriser cette source » ou « Installer des applications inconnues ».** Android exige une autorisation explicite pour l'application depuis laquelle vous installez : navigateur, gestionnaire de fichiers, messagerie. L'autorisation est donnée par source, pas globalement.

**« Application non vérifiée », « développeur inconnu », « analyse impossible », ou une proposition d'envoyer le fichier à Google pour analyse.** Play Protect ne connaît ni ce certificat de signature ni ce développeur. Ces messages ne signalent pas un problème trouvé dans le fichier : ils signalent que Google n'a rien à en dire.

Une application publiée sur le Play Store ne déclenche pas ces messages, non parce qu'elle serait plus sûre, mais parce que Google l'a vue passer. Un APK signé par une clé auto-signée et distribué depuis GitHub les déclenchera toujours.

### Alerte à ne jamais ignorer

**« Application dangereuse », « application nuisible bloquée », « cette application peut endommager votre appareil ».** C'est un **verdict**, pas une absence d'information : Play Protect annonce avoir détecté un comportement qu'il juge nuisible dans le fichier analysé.

Si ce message apparaît, n'installez pas l'application, supprimez le fichier, et signalez-le dans une issue du projet en précisant depuis quelle adresse vous l'avez téléchargé. Une préversion officielle de RavenEmu ne devrait pas déclencher ce verdict ; s'il apparaît, quelque chose mérite d'être compris avant d'aller plus loin.

### Ce qu'il ne faut pas faire

- Ne désactivez pas Play Protect de façon générale. Il continue de rendre service pour tout le reste, y compris pour détecter ce que la vérification d'empreinte ne détecte pas.
- Ne laissez pas l'autorisation « sources inconnues » active en permanence sur votre navigateur. Accordez-la au moment de l'installation, puis retirez-la.

### Ce que la vérification d'empreinte prouve, et ce qu'elle ne prouve pas

Elle prouve **qui a signé** le fichier : si l'empreinte du certificat correspond à celle publiée par le projet, l'APK vient bien de la clé de RavenEmu et n'a pas été modifié depuis.

Elle ne dit **rien du contenu** lui-même. Elle répond aux messages d'absence d'information, qui portent justement sur l'origine du fichier. Elle ne répond pas à un verdict de détection, et ne doit jamais servir à en écarter un.

## Vérifier l'APK avant de l'installer

Chaque préversion publie quatre informations : l'empreinte SHA-256 du fichier, l'empreinte SHA-256 du certificat de signature, le commit source et le nom du package.

```bash
sha256sum -c RavenEmu-test.apk.sha256
apksigner verify --print-certs RavenEmu-test.apk
```

L'empreinte du certificat doit être :

```
c439aed3f5210f88d92f435f949614cacbb4105ed7967a246abfa051d59feee1
```

Elle est identique pour toutes les préversions Test, et rappelée dans les notes de chacune. Si `apksigner` en affiche une autre, n'installez pas l'APK.

## Mettre à jour

L'APK Test est signé par une clé dédiée et stable : une nouvelle version s'installe par-dessus la précédente, sans désinstallation et sans perte de données.

Si Android refuse la mise à jour en signalant une signature incompatible, l'application déjà installée ne vient pas de ce dépôt, ou date d'avant l'adoption de cette clé dédiée. Dans ce dernier cas, sauvegardez vos données, désinstallez, puis réinstallez : c'est une opération à faire une seule fois.

Avant toute désinstallation :

- copiez les sauvegardes importantes ;
- vérifiez l'emplacement choisi pour les fichiers ;
- notez les réglages personnalisés.

Une désinstallation peut supprimer les données internes de l'application.

## Compiler soi-même

Seul l'APK Test est publié. Un APK Debug se construit localement avec le Gradle Wrapper (voir [[Compilation]]) et ne demande aucun secret de signature. Il n'est pas destiné à circuler.

## Contenu non fourni

RavenEmu ne télécharge et ne distribue ni ROM ni BIOS. Utilisez uniquement des copies de jeux que vous êtes autorisé à employer ou des homebrews librement distribués.
