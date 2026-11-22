# Distribution de RavenEmu

## État actuel

L'application native n'a **pas encore de version publique**. Les dernières préversions publiées, `android-beta-N` (identifiant `com.ravenemu.preview`), sont celles de la bêta retirée le 6 novembre 2026 ; elles restent sur la page des [Releases](https://github.com/GhostPunishR/RavenEmu/releases). Voir [CHANGELOG.md](CHANGELOG.md).

Le workflow [RavenEmu / Applications](.github/workflows/ci-applications.yml) ne publie aucune version publique. Il construit et teste, puis archive un APK de développement :

| Élément | Valeur |
| --- | --- |
| Commande | `./gradlew :android:assembleDebug` |
| Variante | Debug, code natif optimisé, signée avec la clé de débogage |
| Identifiant Android | `com.ravenemu.dev` |
| Architecture | ARM64 (`arm64-v8a`), Android 8.0 minimum |
| Artefact Actions | `ravenemu-android-dev-arm64` : `android-debug.apk` et l'archive des sources natives |
| Conservation | 7 jours |

Cet APK s'installe à côté de la bêta sans lire ses données. Ce n'est pas une version publique signée.

## APK Release signé dans GitHub Actions (sans publication)

Après la réussite des jobs de règles, des tests C et de l'APK Android Debug,
le job **Android ARM64 - APK Release signé (non publié)** se lance uniquement
sur `main` (push ou déclenchement manuel `workflow_dispatch`). Aucune clé
n'est chargée sur les pull requests. Il produit `assembleRelease`, aligne
l'APK, le signe avec la clé conservée dans les secrets GitHub, puis vérifie
la signature, l'alignement, l'identifiant et les bibliothèques ARM64.

- Secrets attendus : `ANDROID_KEYSTORE_BASE64` (contenu du keystore en Base64)
  et `ANDROID_KEYSTORE_PASSWORD` (mot de passe du magasin **et de sa clé**).
  Le magasin doit contenir **une seule clé privée** : aucun alias n'est
  supposé par la CI. Le workflow ne génère ni ne remplace un keystore.
- Artefact `ravenemu-android-release-candidate-arm64` (14 jours) :
  `ravenemu-release-signed.apk`, `ravenemu-native-sources.tar.gz` associé
  à l'APK signé, et `SHA256SUMS.txt` pour contrôler les téléchargements.
- Identifiant conservé **`com.ravenemu.dev`**, version actuelle `0.1-dev`
  (`versionCode=1`) : cet APK ne met pas à jour la bêta
  `com.ravenemu.preview` et n'est **pas encore** une version publique.
- Aucun tag, aucune GitHub Release, aucun déploiement en magasin ni modification
  du README n'est effectué automatiquement. La compatibilité du certificat,
  l'identifiant définitif, le versionnement et les validations sur appareils
  restent des décisions préalables à toute distribution publique.

Un keystore Base64 incomplet, un mot de passe incorrect, plusieurs clés
privées ou un APK invalide **font échouer** le job de signature au lieu
d'émettre un artefact prétendument publiable. Les secrets ne doivent jamais
être ajoutés au dépôt, copiés dans les logs ni communiqués en clair.

## Avant une première publication

Une publication relève **uniquement d'une décision explicite de GhostPunishR** ([ARCHITECTURE.md](ARCHITECTURE.md), section 8). Elle demande de choisir et de mettre en place :

- l'identifiant Android publié et le certificat de signature : garder `com.ravenemu.preview` permettrait une mise à jour par-dessus la bêta avec son certificat, mais ses données privées ne sont jamais lues ni migrées sans une migration explicite, réversible et testée ;
- une variante de publication signée, le `versionCode` et le nom de version ;
- le workflow de publication (tag, notes, empreintes `SHA256SUMS.txt`).

Avant de distribuer un build :

- relever l'exécution, le commit et le résultat des jobs ;
- tester installation, navigation Android (trois boutons et gestes), portrait et paysage, audio, commandes, sauvegardes, reprise et transferts sur appareil ;
- distinguer compilation, tests automatisés et validation réelle des jeux ;
- documenter les limites et les écarts de parité restants ([docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md)).

Avant toute désinstallation ou effacement de données, exportez les sauvegardes.

## README et image de marque

Le [README](README.md) affiché par GitHub a été promu depuis la maquette `README_RELEASE.md` le 6 novembre 2026, sur demande du propriétaire ; la maquette et les mentions d'iOS ont été retirées le 7 novembre 2026, à sa demande aussi. **Seul le propriétaire décide d'une nouvelle modification du README.** Aucune étape CI ni aucun agent ne doit le régénérer ou remplacer le logo officiel. Voir la règle permanente dans [AGENTS.md](AGENTS.md) et les propriétaires définis dans `.github/CODEOWNERS`.
