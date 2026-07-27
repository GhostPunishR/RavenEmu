# Publication des versions de RavenEmu

Ce document décrit l'APK Test continu et les futures versions numérotées signées.

## APK Test continu

Après chaque construction réussie de `main`, GitHub Actions met à jour la préversion `test-latest`.

Cette préversion contient :

- `RavenEmu-test.apk` ;
- `RavenEmu-test.apk.sha256` ;
- le commit exact utilisé pour la construction.

L'APK Test conserve les diagnostics et laisse Android optimiser le moteur. Le tag `test-latest` est mobile et ne représente pas une version stable.

La CI ne publie aucun APK Debug. Les développeurs peuvent toujours le construire localement avec `./gradlew assembleDebug`.

### Signature de l'APK Test

L'APK Test est signé par une **clé dédiée**, distincte de la clé Release. C'est ce qui permet de mettre à jour l'application installée sans la désinstaller : Android refuse une mise à jour dont le certificat a changé. Auparavant, la variante `profil` reprenait le keystore de débogage régénéré à chaque exécution du runner, et chaque nouvelle construction cassait la mise à jour.

Le package reste `com.ravenemu.app.profil` : l'APK Test et une éventuelle installation Release cohabitent sur le même appareil.

Une construction locale n'exige aucun secret : sans clé Test, `./gradlew assembleProfil` retombe sur la clé de débogage. L'APK produit reste utilisable pour soi, mais la CI refuse de le publier.

### Vérifier un APK téléchargé

Chaque préversion `test-latest` publie l'empreinte du fichier, l'empreinte du certificat de signature, le commit source et le nom du package.

```bash
sha256sum -c RavenEmu-test.apk.sha256
apksigner verify --print-certs RavenEmu-test.apk
```

L'empreinte SHA-256 du certificat doit être identique d'une préversion à l'autre. Si elle change, ne pas installer : soit la clé a tourné, soit l'APK ne vient pas de ce dépôt.

## Secrets et variables de signature

Deux jeux de secrets **strictement séparés**. La clé Release ne signe jamais l'APK Test, et réciproquement : un APK Test compromis ne doit pas pouvoir se faire passer pour une mise à jour de l'application publiée.

| Canal | Secrets GitHub |
|---|---|
| Test (`com.ravenemu.app.profil`) | `RAVENEMU_TEST_KEYSTORE_BASE64`, `RAVENEMU_TEST_KEYSTORE_PASSWORD`, `RAVENEMU_TEST_KEY_ALIAS`, `RAVENEMU_TEST_KEY_PASSWORD` |
| Release (`com.ravenemu.app`) | `RAVENEMU_KEYSTORE_BASE64`, `RAVENEMU_KEYSTORE_PASSWORD`, `RAVENEMU_KEY_ALIAS`, `RAVENEMU_KEY_PASSWORD` |

S'y ajoutent deux **variables** de dépôt (`Settings → Secrets and variables → Actions → Variables`). Une empreinte de certificat est une donnée publique : elle n'a rien à faire dans un secret.

| Variable | Rôle |
|---|---|
| `RAVENEMU_TEST_CERT_SHA256` | Empreinte attendue du certificat Test. La publication échoue si l'APK est signé autrement — une rotation de clé accidentelle est ainsi bloquée avant diffusion. |
| `RAVENEMU_RELEASE_CERT_SHA256` | Empreinte du certificat Release. Sert à vérifier que les deux canaux n'utilisent pas la même clé. |

### Créer la clé Test, pas à pas

À faire une seule fois. `keytool` est fourni avec le JDK.

**1. Générer le keystore.** Choisissez un mot de passe et notez-le : il n'est récupérable nulle part.

```bash
keytool -genkeypair -v \
  -keystore ravenemu-test.jks -storetype PKCS12 \
  -alias ravenemu-test \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=RavenEmu Test, O=RavenEmu, C=FR"
```

`-validity 10000` couvre environ 27 ans. Une clé expirée ne permet plus de publier de mise à jour.

> ⚠️ **Sauvegardez ce fichier et son mot de passe hors du dépôt et hors de la machine de développement.** Les perdre signifie ne plus jamais pouvoir mettre à jour un APK Test déjà installé : Android refuse une mise à jour dont le certificat a changé. Il faudrait alors demander à chacun de désinstaller.

**2. Encoder le keystore en base64**, pour le transporter dans un secret GitHub :

```bash
# Linux
base64 -w0 ravenemu-test.jks > ravenemu-test.jks.b64

# macOS
base64 -i ravenemu-test.jks | tr -d '\n' > ravenemu-test.jks.b64
```

```powershell
# Windows, PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ravenemu-test.jks")) |
  Set-Content -NoNewline ravenemu-test.jks.b64
```

**3. Créer les quatre secrets** dans `Settings → Secrets and variables → Actions → Secrets → New repository secret` :

| Secret | Valeur |
|---|---|
| `RAVENEMU_TEST_KEYSTORE_BASE64` | le contenu de `ravenemu-test.jks.b64`, en une seule ligne |
| `RAVENEMU_TEST_KEYSTORE_PASSWORD` | le mot de passe du keystore |
| `RAVENEMU_TEST_KEY_ALIAS` | `ravenemu-test` |
| `RAVENEMU_TEST_KEY_PASSWORD` | le mot de passe de la clé (identique au précédent si `keytool` ne l'a pas demandé séparément) |

**4. Supprimer le fichier `.b64`** une fois les secrets créés. Il contient le keystore en clair.

**5. Pousser sur `main`.** La publication réussit alors, en signalant simplement que l'empreinte de référence n'est pas encore fixée.

**6. Relever l'empreinte du certificat** dans le résumé du job « Tests, lint et APK Test » (tableau « APK Test », ligne « SHA-256 du certificat »), puis la déclarer dans `Settings → Secrets and variables → Actions → Variables` sous le nom `RAVENEMU_TEST_CERT_SHA256`.

C'est la voie la plus sûre : la valeur affichée est celle du certificat réellement utilisé pour signer. La même empreinte peut aussi être relevée depuis le keystore :

```bash
keytool -list -v -keystore ravenemu-test.jks -alias ravenemu-test | grep 'SHA256:'
```

Les deux formats sont acceptés : `keytool` affiche des majuscules séparées par des deux-points, `apksigner` des minuscules d'un seul tenant, et la CI normalise avant de comparer.

Le job de publication Test **échoue explicitement** si l'un des secrets `RAVENEMU_TEST_*` manque, plutôt que de diffuser un APK signé par une clé instable. De même, un tag `v*` sans secret Release fait échouer le job Release au lieu de terminer en succès sans rien publier.

Aucun keystore, mot de passe ni empreinte privée ne doit être ajouté au dépôt, copié dans un journal, ou transmis dans une issue. Les secrets ne sont lus que comme variables d'environnement de la commande qui en a besoin, jamais affichés.

## Protections GitHub à configurer

Ces protections ne sont pas dans le dépôt : elles se règlent dans l'interface GitHub et doivent être vérifiées après toute modification des paramètres.

**Environnement `release`** (`Settings → Environments → release`) :

- exiger une **approbation manuelle** (`Required reviewers`) avant l'exécution du job Release ;
- limiter les branches et tags autorisés à `v*` (`Deployment branches and tags → Selected`) ;
- rattacher les secrets `RAVENEMU_KEYSTORE_*` et `RAVENEMU_KEY_*` **à cet environnement**, et non au dépôt : aucun autre job ne peut alors les lire.

**Branche `main`** (`Settings → Rules`) :

- interdire le push direct, exiger une pull request ;
- exiger la réussite du job « Tests, lint et APK Test » ;
- interdire la suppression et le `force-push`.

**Tags** (`Settings → Rules → Tag ruleset`) :

- restreindre la création des tags `v*` aux mainteneurs ;
- interdire la mise à jour et la suppression d'un tag `v*` existant, pour qu'une version publiée reste attachée au commit vérifié.

**Actions** (`Settings → Actions → General`) :

- conserver `Workflow permissions` sur `Read repository contents`, chaque job élevant ses droits localement ;
- exiger l'approbation des workflows pour les contributions externes.

## Déclenchement du job Release

Le job Release ne s'exécute que dans deux cas :

- push d'un tag `v*` ;
- exécution manuelle du workflow avec l'entrée `publier_release` cochée.

Il ne se déclenche donc **jamais** sur un push de branche de travail. Cette règle est vérifiée automatiquement par les tests du module `ci-policy`, qui évaluent la condition réelle du workflow pour une série de déclenchements simulés.

## Préparer une version numérotée

1. Choisir un numéro respectant le format `MAJEUR.MINEUR.CORRECTIF`.
2. Mettre à jour `versionCode` et `versionName` dans `app/build.gradle.kts`.
3. Mettre à jour la documentation, la matrice de compatibilité et les limites connues.
4. Créer ou compléter `CHANGELOG.md` à partir de la première version numérotée.
5. Exécuter les tests et le lint.
6. Construire l’APK et l’App Bundle Release signés.
7. Vérifier la signature, les empreintes et le contenu des artefacts.
8. Créer un tag `vMAJEUR.MINEUR.CORRECTIF` sur le commit validé. Le push du tag déclenche le job Release, qui attend l'approbation de l'environnement `release`.
9. Approuver le déploiement, puis publier une GitHub Release avec les artefacts et les notes de version.

Commandes de validation :

```bash
./gradlew jvmTest   # modules JVM purs, dont la politique de publication
./gradlew test
./gradlew lint
./gradlew assembleRelease bundleRelease
```

## Vérifier les artefacts

Avant publication :

- vérifier que le workflow associé au commit est réussi ;
- vérifier la signature de l’APK avec les outils Android ;
- calculer et publier une empreinte SHA-256 ;
- installer l’APK sur un appareil de test propre ;
- lancer un test minimal pour chaque console prise en charge ;
- confirmer que l’identifiant de l’application correspond au canal Release ;
- vérifier qu’aucune clé, aucun journal sensible et aucun fichier de test protégé ne sont inclus.

## Notes de version

Les notes doivent indiquer :

- les nouvelles fonctions ;
- les corrections importantes ;
- les changements de compatibilité ;
- les migrations de sauvegardes ou de réglages ;
- les limitations connues ;
- le commit et le tag ;
- les empreintes des fichiers publiés.

## Annulation d’une publication

Si une version présente un problème critique :

1. retirer les artefacts concernés de la page Release ;
2. publier un avis expliquant le risque sans exposer de secret ;
3. corriger le problème sur une branche dédiée ;
4. produire une nouvelle version avec un numéro supérieur ;
5. ne jamais réutiliser un artefact signé devenu incertain.
