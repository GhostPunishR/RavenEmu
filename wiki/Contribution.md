# Contribution

Les contributions de code, de documentation et de validation sont bienvenues.

## Avant de commencer

- recherchez une issue ou une pull request existante;
- ouvrez une proposition avant un chantier important;
- gardez le périmètre aussi ciblé que possible;
- consultez [[Architecture]];
- identifiez une documentation technique publique pour les comportements matériels.

## Originalité du code

Les moteurs RavenEmu sont écrits pour le projet. Une contribution ne doit pas copier, traduire ou adapter le code d'un autre émulateur.

Les références acceptables comprennent:

- manuels processeur;
- documentation matérielle publique;
- formats de fichiers documentés;
- observations et tests reproductibles;
- programmes synthétiques écrits pour RavenEmu.

## Tests

Une correction doit ajouter un test capable de reproduire le défaut lorsque cela est possible.

Les tests ne doivent contenir:

- aucune ROM commerciale;
- aucun BIOS protégé;
- aucune clé ou donnée personnelle;
- aucun fichier dont la redistribution est interdite.

Préférez des ROM synthétiques construites par le code de test.

## Vérifications

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Adaptez les commandes au périmètre de la modification.

## Pull request

La description doit préciser:

- le problème ou l'objectif;
- la cause technique si elle est connue;
- les fichiers et modules touchés;
- le comportement avant et après;
- les tests exécutés;
- les limites restantes;
- les références techniques utilisées.

Évitez de mélanger une refonte, une nouvelle fonctionnalité et des corrections indépendantes dans la même pull request.

## Sécurité

Une vulnérabilité ne doit pas être publiée dans une issue. Utilisez un [avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new).
