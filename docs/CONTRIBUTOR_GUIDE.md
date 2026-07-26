# Parcours rapide pour contribuer

Ce document résume le chemin recommandé pour une première contribution.

## 1. Choisir un sujet

Commencez par une issue existante ou une correction limitée.

Évitez de commencer par une réécriture complète d'un moteur ou par une nouvelle
console sans discussion préalable.

## 2. Lire les documents utiles

- [Guide de contribution](CONTRIBUTING.md)
- [Décisions d'architecture](ARCHITECTURE.md)
- [Compilation](BUILD.md)
- [Code de conduite](CODE_OF_CONDUCT.md)
- [Politique de sécurité](SECURITY.md)

## 3. Créer une branche

```text
fix/description-courte
feat/description-courte
test/description-courte
docs/description-courte
```

## 4. Développer

- Garder le changement limité au sujet choisi.
- Ajouter un test de non-régression.
- Documenter les comportements matériels non évidents.
- Éviter les allocations dans les chemins chauds.
- Ne pas ajouter de contenu protégé.

## 5. Valider

```bash
./gradlew jvmTest
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

## 6. Ouvrir la pull request

Utilisez la [checklist](PULL_REQUEST_CHECKLIST.md) et indiquez :

- le problème ;
- la solution ;
- les tests ;
- les commandes exécutées ;
- les limites restantes ;
- les risques de régression.

## 7. Répondre à la revue

Répondez aux commentaires avec des faits, des tests ou des références
techniques. Marquez un fil comme résolu uniquement lorsque la correction est
présente ou qu'une décision claire a été prise.
