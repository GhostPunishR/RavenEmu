# Distribution de RavenEmu

La version de référence est **v0.2.0 - bêta** ; son contenu et ses limites sont décrits dans [CHANGELOG.md](CHANGELOG.md). Le workflow [RavenEmu / Applications](.github/workflows/ci-applications.yml) publie l'APK Android signé dans les [Releases](https://github.com/GhostPunishR/RavenEmu/releases) après validation de tous les jobs sur `main`.

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

Le job iOS construit le framework partagé, le SDK natif et l'application Xcode, puis exécute les XCTest sur simulateur. L'artefact `ravenemu-ios-simulator` contient une application pour simulateur Apple Silicon ; il ne constitue pas une IPA pour iPhone. Aucun binaire iPhone signé n'est distribué. Voir [la première tranche et ses limites](iosApp/README.md).

## Versions et publications

Le changelog décrit chaque version bêta. Après réussite des jobs règles du dépôt, natif, Android et iOS sur `main`, la CI crée automatiquement le tag `android-beta-N` sur le commit validé et publie sa préversion avec l'APK signé et son empreinte. Une relance reprend un éventuel brouillon sans remplacer une préversion déjà publiée. Les anciennes préversions restent disponibles. La création manuelle d'un tag ne déclenche pas de build ; la publication part de la CI de `main`.

## README et image de marque après migration

Le futur README de publication est pré-rédigé dans [README_RELEASE.md](README_RELEASE.md). Le README actuellement affiché par GitHub reste [README.md](README.md) tant que la migration n'est pas terminée et explicitement validée par GhostPunishR.

**Seul le propriétaire décide de remplacer le README actif.** Aucune étape CI ou agent ne doit publier automatiquement cette maquette, la régénérer ou remplacer le logo officiel. Avant une décision de publication, confirmer les fonctionnalités réellement présentes, les liens, les validations Android/iOS et la compatibilité des sauvegardes. Voir la règle permanente dans [AGENTS.md](AGENTS.md) et les propriétaires définis dans `.github/CODEOWNERS`.
