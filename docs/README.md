# Documentation RavenEmu

Ce dossier regroupe la documentation technique et communautaire du projet.

## Démarrer

- [Compilation](BUILD.md)
- [Parcours rapide pour contribuer](CONTRIBUTOR_GUIDE.md)
- [Guide de contribution](CONTRIBUTING.md)
- [Checklist de pull request](PULL_REQUEST_CHECKLIST.md)
- [Cahier des charges](CAHIER_DES_CHARGES.md)
- [Décisions d'architecture](ARCHITECTURE.md)

## Communauté

- [Code de conduite](CODE_OF_CONDUCT.md)
- [Politique de sécurité](SECURITY.md)

## Avant une contribution

1. Lire le guide de contribution.
2. Vérifier les décisions d'architecture.
3. Ajouter des tests adaptés.
4. Exécuter les commandes de validation.
5. Décrire clairement les limites restantes dans la pull request.

## Règles de documentation

- Décrire l'état réel du projet.
- Ne pas annoncer une compatibilité non testée.
- Ne pas inclure de ROM, BIOS, sauvegarde ou donnée protégée.
- Citer les références techniques publiques utilisées.
- Utiliser des titres et une ponctuation simples.
- Ne pas utiliser le tiret cadratin dans les fichiers du dossier `docs`.

## Validation minimale

```bash
./gradlew jvmTest
./gradlew test
./gradlew lint
./gradlew assembleDebug
```
