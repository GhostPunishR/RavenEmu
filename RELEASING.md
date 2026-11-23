# Distribution de RavenEmu

## État actuel

L'application native n'a **pas encore de version publique**. Sa première version, **RavenEmu 1.0.0**, est prête à être publiée par un tag `v1.0.0` du propriétaire (voir [Publier une version](#publier-une-version)). Les dernières préversions publiées, `android-beta-N` (identifiant `com.ravenemu.preview`), sont celles de la bêta retirée le 6 novembre 2026 ; elles restent sur la page des [Releases](https://github.com/GhostPunishR/RavenEmu/releases). Voir [CHANGELOG.md](CHANGELOG.md).

## Identifiants et version

| | APK Release (publié) | APK de développement |
| --- | --- | --- |
| Nom affiché | **RavenEmu** | RavenEmu Dev |
| Identifiant Android | **`com.ravenemu`** | `com.ravenemu.dev` |
| Nom de version | `X.Y.Z`, par exemple `1.0.0` | `X.Y.Z-dev` |
| Code de version | `X·10000 + Y·100 + Z` (`10000` pour 1.0.0) | le même |
| Signature | Clé de RavenEmu, dans les secrets GitHub | Clé de débogage de la compilation |
| Commande | `./gradlew :android:assembleRelease`, puis la signature | `./gradlew :android:assembleDebug` |

La version tient en **un seul endroit** : `ravenemuVersion=X.Y.Z` dans [`gradle.properties`](gradle.properties). `android/build.gradle.kts` en tire le nom et le code de version, et refuse toute autre forme ; le mineur et le correctif restent sous 100, pour que le code de version croisse d'une version à la suivante. Le tag qui publie une version doit être exactement `vX.Y.Z`.

Chaque chiffre a un sens ([AGENTS.md](AGENTS.md#numéros-de-version)) :
- **X** marque une **version stable**, une nouvelle génération ;
- **Y** marque une **mise à jour majeure**, avec de nouvelles fonctions ;
- **Z** marque un **correctif** : des bugs corrigés, sans nouvelle fonction.

Augmenter un chiffre remet à zéro ceux qui le suivent : `1.4.2` devient `1.4.3` pour un correctif, `1.5.0` pour une mise à jour majeure, `2.0.0` pour une nouvelle version stable.

Les trois applications s'installent côte à côte et aucune ne lit les données d'une autre : la bêta `com.ravenemu.preview`, l'APK de développement `com.ravenemu.dev` et la version publiée `com.ravenemu`. Les sauvegardes de l'APK de développement ne passent donc pas toutes seules dans `com.ravenemu`. Pour les reprendre, exportez-les depuis la fiche du jeu puis importez-les, ou utilisez **Paramètres → Sauvegardes cloud** dans les deux applications.

## APK de développement

Le workflow [RavenEmu / Applications](.github/workflows/ci-applications.yml) construit et teste chaque push et chaque pull request, puis archive un APK de développement :

| Élément | Valeur |
| --- | --- |
| Variante | Debug, code natif optimisé, signée avec la clé de débogage |
| Architecture | ARM64 (`arm64-v8a`), Android 8.0 minimum |
| Artefact Actions | `ravenemu-android-dev-arm64` : `android-debug.apk` et l'archive des sources natives |
| Conservation | 7 jours |

Ce n'est pas une version publique.

## APK Release signé, sur `main`

Une fois les jobs de règles, des tests C et de l'APK de développement réussis, le job **Android ARM64 - APK Release signé** se lance sur `main` (push ou déclenchement manuel `workflow_dispatch`) et pour les tags de version. Aucune clé n'est chargée pour les pull requests.

Le job compile `assembleRelease`, puis [`scripts/sign_release_apk.sh`](scripts/sign_release_apk.sh) aligne l'APK et le signe avec la clé des secrets GitHub. Le script vérifie ensuite :

- la signature et l'alignement ;
- l'identifiant `com.ravenemu`, le nom « RavenEmu », la version et son code ;
- que l'APK n'est pas débogable ;
- les bibliothèques ARM64, toutes nommées `lib*.so`.

- Secrets attendus : `ANDROID_KEYSTORE_BASE64` (contenu du keystore en Base64)
  et `ANDROID_KEYSTORE_PASSWORD` (mot de passe du magasin **et de sa clé**).
  Le magasin doit contenir **une seule clé privée** : aucun alias n'est
  supposé par la CI. Le workflow ne génère ni ne remplace un keystore.
- Artefact `RavenEmu-X.Y.Z-<commit>-arm64` (14 jours) : `RavenEmu-X.Y.Z.apk`, `RavenEmu-X.Y.Z-sources.tar.gz` (les sources correspondant à cet APK, qu'il cite avec son empreinte) et `SHA256SUMS.txt`.
- Ce build de `main` n'est **pas** une version publiée : il sert à la validation sur appareil. Entre deux versions, il porte le numéro de la prochaine.

Le job **échoue**, au lieu d'émettre un APK présenté comme publiable, dans les cas suivants : keystore Base64 incomplet, mot de passe incorrect, plusieurs clés privées, mauvais identifiant ou mauvaise version, APK débogable ou bibliothèque manquante. Les secrets ne doivent jamais être ajoutés au dépôt, copiés dans les logs ni communiqués en clair.

**Conservez le keystore d'origine (`.p12`) et son mot de passe hors de GitHub.** Android n'accepte une mise à jour de `com.ravenemu` que signée par la même clé : sans elle, aucune mise à jour de la version publiée n'est possible. Ne remplacez jamais `ANDROID_KEYSTORE_BASE64` par une nouvelle clé après une publication. L'identifiant OAuth Android de Google Drive est lui aussi lié à l'empreinte SHA-1 de cette clé ([docs/GOOGLE_DRIVE.md](docs/GOOGLE_DRIVE.md)).

## Publier une version

Seul le propriétaire publie : **un tag `vX.Y.Z` qu'il pousse, ou la publication qu'il lance ou demande explicitement, est la décision de publication** ([ARCHITECTURE.md](ARCHITECTURE.md), section 8). Les agents ne poussent jamais de tag et ne lancent la publication que sur une demande explicite du propriétaire.

1. **Valider sur appareil** l'APK Release signé de `main` ([Avant de distribuer](#avant-de-distribuer)).
2. **Préparer la version** par une pull request vers `main` :
   - `ravenemuVersion=X.Y.Z` dans `gradle.properties` (déjà `1.0.0` pour la première) ;
   - dans [CHANGELOG.md](CHANGELOG.md), renommer `## Non publié` en `## X.Y.Z - AAAA-MM-JJ`, à la date de publication. Son texte devient les notes de la release, et ses liens vers les fichiers du dépôt y pointent sur le tag.
3. **Lancer la publication**, une fois la pull request fusionnée, de l'une de ces deux façons :
   - **sans git** : onglet **Actions → RavenEmu / Release → Run workflow**, branche `main`, version `X.Y.Z`. Le workflow crée le tag `vX.Y.Z` sur le commit de `main` qu'il a compilé et signé, et refuse un tag qui existe déjà ;
   - **avec git**, en taguant le commit de `main` :

     ```bash
     git switch main && git pull
     git tag -a v1.0.0 -m "RavenEmu 1.0.0"
     git push origin v1.0.0
     ```

   N'utilisez pas le formulaire « Draft a new release » de GitHub : il créerait une release sans APK, et le workflow échouerait ensuite sur la release déjà existante.
4. Le workflow [RavenEmu / Release](.github/workflows/release.yml) contrôle d'abord que la version est égale à `ravenemuVersion` et que CHANGELOG.md a sa section datée. Il exécute ensuite **toute la CI** de RavenEmu / Applications sur ce commit ; pour un tag poussé, le job de signature vérifie aussi que le commit appartient à `main`.

   Il crée ensuite la release GitHub **`vX.Y.Z`**, intitulée « RavenEmu X.Y.Z ». Elle porte les notes et trois fichiers : `RavenEmu-X.Y.Z.apk`, `RavenEmu-X.Y.Z-sources.tar.gz` et `SHA256SUMS.txt`, vérifiés avant l'envoi. Seul ce dernier job reçoit le droit d'écrire.
5. **En cas d'échec**, rien n'est publié. Corrigez par une pull request, supprimez le tag (`git push --delete origin v1.0.0` puis `git tag -d v1.0.0`) et taguez de nouveau. Une release déjà existante n'est jamais écrasée : le job échoue.
6. **Pour la version suivante**, augmentez `ravenemuVersion` avant le prochain tag, et rouvrez une section `## Non publié` dans le changelog. Android refuse une mise à jour dont le code de version n'augmente pas.

Recommandé : dans **Settings → Rules → Rulesets** du dépôt GitHub, réservez au propriétaire la création et la suppression des tags `v*`.

## Avant de distribuer

Le reste relève **uniquement d'une décision explicite de GhostPunishR** ([ARCHITECTURE.md](ARCHITECTURE.md), section 8). L'identifiant publié (`com.ravenemu`), la version (`1.0.0`) et le workflow de publication sont choisis ; avant de taguer :

- relever l'exécution, le commit et le résultat des jobs ;
- tester installation, navigation Android (trois boutons et gestes), portrait et paysage, audio, commandes, sauvegardes, reprise et transferts sur appareil, avec l'APK Release signé ;
- distinguer compilation, tests automatisés et validation réelle des jeux ;
- documenter les limites et les écarts de parité restants ([docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md)) ;
- décider des éventuelles modifications du README, que seul le propriétaire fait.

Avant toute désinstallation ou effacement de données, exportez les sauvegardes.

## README et image de marque

Le [README](README.md) affiché par GitHub a été promu depuis la maquette `README_RELEASE.md` le 6 novembre 2026, sur demande du propriétaire ; la maquette et les mentions d'iOS ont été retirées le 7 novembre 2026, à sa demande aussi. **Seul le propriétaire décide d'une nouvelle modification du README.** Aucune étape CI ni aucun agent ne doit le régénérer ou remplacer le logo officiel ; le workflow de publication n'y touche pas. Voir la règle permanente dans [AGENTS.md](AGENTS.md) et les propriétaires définis dans `.github/CODEOWNERS`.
