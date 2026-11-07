# Distribution de RavenEmu

## État actuel

L'application native n'a **pas encore de version publique**. Les dernières préversions publiées, `android-beta-N` (identifiant `com.ravenemu.preview`), sont celles de la bêta retirée le 6 novembre 2026 ; elles restent sur la page des [Releases](https://github.com/GhostPunishR/RavenEmu/releases). Voir [CHANGELOG.md](CHANGELOG.md).

Le workflow [RavenEmu / Applications](.github/workflows/ci-applications.yml) ne publie rien. Il construit et teste, puis archive un APK de développement :

| Élément | Valeur |
| --- | --- |
| Commande | `./gradlew :android:assembleDebug` |
| Variante | Debug, code natif optimisé, signée avec la clé de débogage |
| Identifiant Android | `com.ravenemu.dev` |
| Architecture | ARM64 (`arm64-v8a`), Android 8.0 minimum |
| Artefact Actions | `ravenemu-android-dev-arm64` : `android-debug.apk` et l'archive des sources natives |
| Conservation | 7 jours |

Cet APK s'installe à côté de la bêta sans lire ses données. Ce n'est pas une version publique signée.

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
