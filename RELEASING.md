# Distribution de RavenEmu

La version de référence est **v0.1.0 - bêta** ; son contenu et ses limites sont décrits dans [CHANGELOG.md](CHANGELOG.md). Le workflow [multiplateforme.yml](.github/workflows/multiplateforme.yml) publie l'APK Android depuis les builds réussis de `main`.

| Élément | Valeur |
| --- | --- |
| Commande | `./gradlew :androidApp:assembleBeta` |
| Variante | Debug sans drapeau débogable, avec la même signature de build |
| Identifiant Android | `com.ravenemu.preview` |
| Nom affiché | RavenEmu |
| Artefact Actions | `ravenemu-android-beta` |
| APK | `androidApp/build/outputs/apk/beta/androidApp-beta.apk` |
| Rétention | 7 jours |

Un compte GitHub connecté est nécessaire pour télécharger un artefact. Voir [Installation](wiki/Installation.md).

## Signature et données

La bêta utilise la signature Debug du build, sans clé de distribution stable configurée. Une mise à jour exige le même identifiant et le même certificat. Le déplacement du dépôt conserve l'identifiant et les noms de stockage ; il ne garantit pas que le certificat d'un nouveau runner soit identique à celui d'un APK déjà installé.

Avant toute désinstallation ou effacement de données, exportez les sauvegardes. Les ZIP historiques et les `.sav` compatibles peuvent être réimportés avec les ROM correspondantes. Les formats natifs restent ceux du RavenCore épinglé. Aucun secret, keystore ou mot de passe ne doit être ajouté au dépôt pour construire cette variante.

## Avant de distribuer un build

- Relever l'exécution, le commit et le résultat des jobs.
- Tester installation, audio, commandes, sauvegardes, reprise et transferts sur appareil.
- Distinguer compilation, tests automatisés et validation réelle des jeux.
- Documenter les limites et incompatibilités de signature ou d'états.

Les jobs iOS construisent le framework partagé et le SDK natif pour simulateur et vérifient les adaptateurs Swift. Ils ne distribuent pas d'application iOS signée ; le lecteur complet reste à raccorder.

## Versions et publications

Le changelog décrit la bêta initiale. Créer un tag `v*` ne déclenche pas de GitHub Release dans le workflow actuel. Une distribution signée stable nécessitera une clé conservée de façon sécurisée, des artefacts vérifiables et une validation sur appareil.
