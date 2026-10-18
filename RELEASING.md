# Distribution de RavenEmu

La version de référence est **v0.1.0 - bêta** ; son contenu et ses limites sont décrits dans [CHANGELOG.md](CHANGELOG.md). Le workflow [multiplateforme.yml](.github/workflows/multiplateforme.yml) publie l'APK Android signé dans les [Releases](https://github.com/GhostPunishR/RavenEmu/releases) après validation de tous les jobs sur `main`.

| Élément | Valeur |
| --- | --- |
| Commande | `./gradlew :androidApp:assembleBeta` |
| Variante | Bêta sans drapeau débogable, signée pour la distribution |
| Identifiant Android | `com.ravenemu.preview` |
| Nom affiché | RavenEmu |
| Artefact Actions | `ravenemu-android-beta` |
| APK distribué | `ravenemu-beta.apk` |
| Préversion | `android-beta-N`, où N est le numéro de la CI et le `versionCode` Android |
| Empreinte | `SHA256SUMS.txt` joint à la préversion |
| Conservation | APK conservé dans chaque Release ; artefact Actions de secours pendant 7 jours |

Les téléchargements des Releases sont accessibles sans compte GitHub ni archive ZIP. Les APK de test des pull requests restent des artefacts Actions distincts. Voir [Installation](wiki/Installation.md).

## Signature et données

Les APK distribués conservent le même certificat de signature et le même identifiant Android. Le `versionCode` augmente avec le numéro de CI pour permettre les mises à jour sans désinstallation. Un ancien APK signé avec un certificat différent peut exiger une première désinstallation ; les préversions suivantes s'installent par-dessus la précédente.

Avant toute désinstallation ou effacement de données, exportez les sauvegardes. Les ZIP historiques et les `.sav` compatibles peuvent être réimportés avec les ROM correspondantes. Les formats natifs restent ceux du RavenCore épinglé.

## Avant de distribuer un build

- Relever l'exécution, le commit et le résultat des jobs.
- Tester installation, audio, commandes, sauvegardes, reprise et transferts sur appareil.
- Distinguer compilation, tests automatisés et validation réelle des jeux.
- Documenter les limites et incompatibilités de signature ou d'états.

Les jobs iOS construisent le framework partagé et le SDK natif pour simulateur et vérifient les adaptateurs Swift. Ils ne distribuent pas d'application iOS signée ; le lecteur complet reste à raccorder.

## Versions et publications

Le changelog décrit la bêta initiale. Après réussite des jobs règles du dépôt, natif, Android et iOS sur `main`, la CI crée automatiquement le tag `android-beta-N` sur le commit validé et publie sa préversion avec l'APK signé et son empreinte. Une relance reprend un éventuel brouillon sans remplacer une préversion déjà publiée. Les anciennes préversions restent disponibles. La création manuelle d'un tag ne déclenche pas de build ; la publication part de la CI de `main`.
