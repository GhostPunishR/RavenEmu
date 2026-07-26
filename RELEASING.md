# Publication des versions de RavenEmu

Ce document décrit la préparation des APK Debug continus et des futures versions numérotées signées.

## APK Debug continu

Après chaque construction réussie de `main`, GitHub Actions met à jour la préversion `debug-latest`.

Cette préversion contient :

- `RavenEmu-debug.apk` ;
- `RavenEmu-debug.apk.sha256` ;
- le commit exact utilisé pour la construction.

L’APK Debug utilise l’identifiant `com.ravenemu.app.debug` et une signature de développement. Le tag `debug-latest` est mobile et ne représente pas une version stable.

## Secrets de signature

Les secrets suivants sont nécessaires pour produire une version Release signée :

- `RAVENEMU_KEYSTORE_BASE64` ;
- `RAVENEMU_KEYSTORE_PASSWORD` ;
- `RAVENEMU_KEY_ALIAS` ;
- `RAVENEMU_KEY_PASSWORD`.

Le keystore et ses mots de passe ne doivent jamais être ajoutés au dépôt, copiés dans un journal ou transmis dans une issue.

## Préparer une version numérotée

1. Choisir un numéro respectant le format `MAJEUR.MINEUR.CORRECTIF`.
2. Mettre à jour `versionCode` et `versionName` dans `app/build.gradle.kts`.
3. Mettre à jour la documentation, la matrice de compatibilité et les limites connues.
4. Créer ou compléter `CHANGELOG.md` à partir de la première version numérotée.
5. Exécuter les tests et le lint.
6. Construire l’APK et l’App Bundle Release signés.
7. Vérifier la signature, les empreintes et le contenu des artefacts.
8. Créer un tag `vMAJEUR.MINEUR.CORRECTIF` sur le commit validé.
9. Publier une GitHub Release avec les artefacts et les notes de version.

Commandes de validation :

```bash
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
