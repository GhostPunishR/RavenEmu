# Contribuer à RavenEmu

Merci de votre intérêt pour RavenEmu. Les contributions de code, de documentation, de tests et de rapports de compatibilité sont les bienvenues.

## Principes du projet

Toute contribution doit respecter les règles suivantes :

- écrire du code original pour RavenEmu ;
- utiliser uniquement de la documentation technique publique et légalement accessible ;
- ne pas copier, traduire ou adapter le code d'un autre émulateur ;
- ne jamais ajouter de ROM commerciale, de BIOS protégé, de clé, de firmware ou de contenu soumis au droit d'auteur ;
- conserver une architecture modulaire et extensible ;
- documenter honnêtement les limites et les comportements encore incomplets ;
- accepter que la contribution soit distribuée sous la licence MIT du dépôt.

Les homebrews et les données de test peuvent être utilisés uniquement si leur licence autorise clairement leur redistribution.

## Avant de commencer

Pour une correction simple, vérifiez d'abord qu'une issue similaire n'existe pas.

Pour une modification importante, ouvrez une issue afin de décrire :

- le problème ou le besoin ;
- la solution envisagée ;
- les modules concernés ;
- les risques de régression ;
- la méthode de validation prévue.

Les vulnérabilités ne doivent pas être publiées dans une issue. Consultez plutôt [la politique de sécurité](SECURITY.md).

## Environnement de développement

Prérequis recommandés :

- JDK 21 ;
- Gradle Wrapper fourni avec le dépôt ;
- SDK Android avec `compileSdk 35` pour les modules Android.

Les modules Kotlin/JVM peuvent être construits et testés sans SDK Android.

Commandes utiles :

```bash
# Tests des modules JVM
./gradlew jvmTest

# Tous les tests
./gradlew test

# Analyse Android
./gradlew lint

# APK Debug
./gradlew assembleDebug
```

## Règles de développement

- Respectez le style du code existant.
- Utilisez des noms explicites pour les classes, fonctions et variables.
- Gardez les moteurs d'émulation indépendants d'Android.
- Évitez les dépendances entre moteurs de consoles.
- Placez les fonctionnalités partagées derrière les contrats de `emulation-api`.
- Ajoutez des tests synthétiques, déterministes et reproductibles.
- Ne masquez pas un comportement incomplet derrière une valeur arbitraire.
- Commentez les choix matériels complexes et indiquez la documentation consultée.
- Ne journalisez aucune donnée personnelle ni aucun chemin sensible.

## Proposer une pull request

1. Créez une branche à partir de `main`.
2. Limitez la modification à un objectif clair.
3. Ajoutez ou mettez à jour les tests nécessaires.
4. Exécutez les tâches Gradle adaptées.
5. Mettez à jour la documentation si le comportement visible change.
6. Ouvrez une pull request avec une description précise.

La description doit indiquer :

- ce qui a été modifié ;
- pourquoi la modification est nécessaire ;
- comment elle a été testée ;
- les limites connues ;
- les issues associées.

Les captures d'écran sont recommandées pour toute modification de l'interface.

## Rapports de compatibilité

Un rapport utile doit préciser :

- la console concernée ;
- la version ou le commit testé ;
- l'appareil et la version Android ;
- le comportement attendu ;
- le comportement observé ;
- les étapes permettant de reproduire le problème.

Ne joignez jamais une ROM commerciale, un BIOS ou une sauvegarde contenant des données protégées.

## Relecture

Une contribution peut être refusée si elle introduit du code non original, réduit la séparation entre modules, manque de tests ou présente un risque juridique. Une demande de modification ne constitue pas un rejet définitif.

En participant au projet, vous acceptez également le [code de conduite](CODE_OF_CONDUCT.md).
