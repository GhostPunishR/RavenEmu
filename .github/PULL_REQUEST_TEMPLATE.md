## Description

Décrivez clairement ce qui a été modifié et le résultat attendu.

## Motivation

Expliquez le problème résolu ou le besoin couvert.

Issue associée : #

## Modules concernés

Les frontières sont celles d'[ARCHITECTURE.md](https://github.com/GhostPunishR/RavenEmu/blob/main/ARCHITECTURE.md),
du bas de la pile vers le haut. Cochez les couches réellement touchées : une
modification qui en traverse plusieurs mérite d'être justifiée dans la
description.

Cœurs C++ (`cores/`, aucune connaissance de JNI, de la JVM ni d'Android) :

- [ ] `cores/common`
- [ ] `cores/gb`
- [ ] `cores/gbc`
- [ ] `cores/gba`

Frontière native :

- [ ] `native/api`
- [ ] `native/jni`

Moteur (`engine/`, Kotlin/JVM pur, jamais dépendant d'Android) :

- [ ] `engine/api`
- [ ] `engine/runtime`
- [ ] `engine/session`
- [ ] `engine/state`, `engine/save`, `engine/audio` ou `engine/diagnostics`

Plateforme et produit :

- [ ] `platform/android/*` (audio, renderer, input, storage, vibration, lifecycle)
- [ ] `features/*` (library, player, settings, skins, savestates, diagnostics)
- [ ] `app/android`

Outillage :

- [ ] `build-logic`, `gradle` ou `tools`
- [ ] Documentation, site ou Wiki
- [ ] GitHub Actions

## Type de modification

- [ ] Correction
- [ ] Fonctionnalité
- [ ] Refactorisation
- [ ] Performance
- [ ] Tests
- [ ] Documentation
- [ ] Construction ou automatisation

## Validation

Indiquez les commandes exécutées et leurs résultats. Les commandes sont celles
du [guide de contribution](https://github.com/GhostPunishR/RavenEmu/blob/main/CONTRIBUTING.md).

Sans SDK Android :

- [ ] `./gradlew jvmTest`
- [ ] `cmake -S cores -B build/native-host -DRAVENEMU_BUILD_TESTS=ON` puis `ctest --test-dir build/native-host --output-on-failure`

Avec SDK Android :

- [ ] `./gradlew test`
- [ ] `./gradlew lint`
- [ ] `./gradlew assembleDebug`
- [ ] Test manuel sur Android

Autre :

- [ ] Documentation mise à jour si nécessaire

Si une commande n'a pas pu être exécutée, dites-le ici plutôt que de laisser la
case vide sans explication : une validation manquante qu'on annonce se rattrape,
une validation manquante qu'on tait se découvre en production.

## Références techniques

Listez les documents publics utilisés pour les comportements matériels ou les formats de fichiers.

## Captures d’écran

Ajoutez des captures pour toute modification visible de l’interface. Retirez les données personnelles avant publication.

## Limites connues

Décrivez les comportements encore incomplets, les risques et les cas non testés.

## Vérifications juridiques et communautaires

- [ ] Le code proposé est original et écrit pour RavenEmu.
- [ ] Aucun code d’un autre émulateur n’a été copié, traduit ou adapté.
- [ ] Aucun BIOS, aucune ROM, aucune clé et aucun contenu protégé n’est ajouté.
- [ ] Les tests utilisent uniquement des données synthétiques ou redistribuables.
- [ ] La contribution respecte le guide de contribution et le code de conduite.
