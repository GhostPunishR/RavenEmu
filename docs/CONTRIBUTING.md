# RavenEmu : guide de contribution

Merci de contribuer à RavenEmu.

RavenEmu est distribué sous licence MIT, copyright GhostPunishR. Toute contribution acceptée est publiée sous cette même licence.

En participant au projet, vous acceptez également le [code de conduite](CODE_OF_CONDUCT.md) et les règles de sécurité décrites dans [SECURITY.md](SECURITY.md).

## Principes du projet

RavenEmu suit les règles suivantes :

1. Aucun code provenant d'un autre émulateur ne doit être copié, traduit, adapté ou intégré.
2. Le comportement matériel doit être implémenté à partir de documentation technique publique et de tests originaux.
3. Aucun BIOS, ROM commerciale, pochette, clé, sauvegarde personnelle ou autre contenu protégé ne doit être ajouté au dépôt.
4. Les tests doivent utiliser des ROM synthétiques produites par le code de test ou des données libres dont la licence est clairement compatible.
5. Les modules de moteur doivent rester indépendants d'Android lorsque leur architecture le prévoit.
6. Les chemins chauds de l'émulation doivent éviter les allocations et les opérations bloquantes.
7. Toute limite connue doit être documentée honnêtement.

Consultez également le [cahier des charges](CAHIER_DES_CHARGES.md) et les [décisions d'architecture](ARCHITECTURE.md).

## Avant de commencer

Pour une correction simple, vous pouvez ouvrir directement une pull request.

Pour un changement important, ouvrez d'abord une issue afin de discuter :

- du problème à résoudre ;
- du comportement matériel attendu ;
- des modules concernés ;
- des risques de régression ;
- de la stratégie de test.

Les changements importants comprennent notamment :

- une nouvelle console ;
- une modification du contrat `EmulatorCore` ;
- un nouveau format de sauvegarde ou d'état instantané ;
- une dépendance supplémentaire ;
- une modification importante de la boucle d'émulation ;
- un changement de sécurité ou de confidentialité.

## Préparer l'environnement

Consultez [BUILD.md](BUILD.md) pour les prérequis et les commandes complètes.

Validation minimale :

```bash
./gradlew jvmTest
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Avec les secrets de signature configurés :

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

Ne placez jamais de keystore, de mot de passe ou de secret dans le dépôt.

## Choisir une branche

Utilisez une branche courte et descriptive :

```text
fix/gba-huffman
feat/control-skins
test/dma-timing
docs/security-policy
```

Évitez de mélanger plusieurs sujets sans rapport dans une même branche.

## Style de code

- Respecter les conventions Kotlin existantes.
- Utiliser des noms explicites.
- Ajouter du KDoc pour les contrats publics et les comportements matériels non évidents.
- Éviter les captures d'exception silencieuses.
- Ne pas ajouter de télémétrie ou de trafic réseau sans discussion préalable.
- Ne pas journaliser le contenu des ROM, sauvegardes, clés ou chemins privés.
- Préserver le déterminisme du moteur.
- Éviter les allocations dans les boucles CPU, PPU, APU, DMA et audio.
- Utiliser des structures réutilisables dans les chemins chauds.

## Documentation technique

Toute modification matérielle importante doit préciser :

- la documentation publique utilisée ;
- le comportement attendu ;
- les simplifications éventuelles ;
- les cas limites connus ;
- les tests ajoutés.

Une décision structurante doit être ajoutée ou mise à jour dans `docs/ARCHITECTURE.md`.

La documentation du dépôt utilise des titres et séparateurs simples. Le tiret cadratin ne doit pas être utilisé dans les fichiers du dossier `docs`.

## Tests

Chaque correction doit inclure un test de non-régression lorsque cela est raisonnablement possible.

Les tests peuvent couvrir :

- CPU et drapeaux ;
- mémoire et alignement ;
- interruptions ;
- timers ;
- DMA ;
- PPU et composition ;
- APU et FIFO ;
- sauvegardes ;
- états instantanés ;
- parsing des en-têtes ;
- intégration Android ;
- performances des chemins chauds.

Les tests de performance doivent éviter les seuils trop stricts dépendant de la machine. Préférez les comparaisons relatives et les détections de régression évidente.

## Commits

Utilisez des messages courts et explicites, par exemple :

```text
fix(gba): corrige le décodage Huffman
perf(gba): accélère les lectures ROM alignées
test(gb): couvre le réveil STOP
docs: ajoute la politique de sécurité
```

Un commit doit rester cohérent et facile à examiner.

## Pull requests

Une pull request doit contenir :

- un résumé clair ;
- la cause du problème ou le besoin ;
- la solution retenue ;
- la liste des modules concernés ;
- les tests ajoutés ;
- les commandes exécutées ;
- les limites restantes ;
- les risques de régression ;
- des captures ou mesures lorsque l'interface ou les performances changent.

La CI doit être verte avant fusion. Une CI verte ne remplace pas la revue fonctionnelle.

## Dépendances

Les dépendances autorisées sont limitées aux composants nécessaires au projet, notamment Android SDK, Kotlin, Gradle, AndroidX, Material et bibliothèques officielles JetBrains déjà approuvées.

Toute nouvelle dépendance doit être justifiée dans la pull request avec :

- sa licence ;
- son usage ;
- son impact sur la taille ;
- son impact sur la confidentialité ;
- les alternatives étudiées.

Aucune bibliothèque contenant un coeur d'émulation tiers ne sera acceptée.

## Signalement de sécurité

Ne publiez pas une vulnérabilité exploitable dans une issue publique.

Suivez la procédure de [SECURITY.md](SECURITY.md). Les rapports doivent décrire le problème, son impact, les versions concernées et une méthode de reproduction minimale.

## Comportement attendu

Les échanges doivent rester techniques, respectueux et accueillants. Le harcèlement, les attaques personnelles et la publication de données privées ne sont pas acceptés.

Consultez [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) pour les règles détaillées et la procédure de signalement.
