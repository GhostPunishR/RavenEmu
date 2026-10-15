# Contribution

Les contributions de code, de documentation et de validation sont bienvenues. Consultez le [guide du dépôt](https://github.com/GhostPunishR/RavenEmu/blob/main/CONTRIBUTING.md) et le [code de conduite](https://github.com/GhostPunishR/RavenEmu/blob/main/CODE_OF_CONDUCT.md).

## Choisir le bon périmètre

| Sujet | Emplacement |
|---|---|
| Interface et logique communes | `shared/` |
| Lecteur, fichiers et services Android | `androidApp/` |
| Hôte Apple et branchements | `iosApp/` |
| Ponts JNI/Swift et configuration native | `native/` |
| Correction d'un moteur | Dépôt RavenCore ; mise à jour du commit épinglé traitée séparément |
| Documentation Wiki | `wiki/*.md` dans RavenEmu |

Recherchez une issue existante et discutez les chantiers importants avant de les commencer.

## Code et tests

N'intégrez pas de code copié, traduit ou adapté d'un autre émulateur. Utilisez documentation matérielle publique, observations reproductibles et tests synthétiques.

Aucune ROM commerciale, aucun BIOS protégé, aucune clé ni donnée personnelle ne doit entrer dans les tests ou le dépôt.

Adaptez les contrôles au changement. Pour Android et les règles, les commandes actuelles sont :

```sh
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
./gradlew :policy:test
```

Voir [Compilation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compilation.md) pour les cibles communes et iOS. Les commandes ci-dessus correspondent à la CI ; documentez celles réellement exécutées dans votre PR, sans assimiler compilation et validation sur appareil.

## Tester sur téléphone

Un retour utile indique le commit du build, l'appareil, la version Android, la ROM/région et les étapes observées. Documentez audio, vidéo, commandes et sauvegardes séparément. Pour RA, confirmez la reconnaissance et le résultat côté serveur sans publier le compte ou son jeton.

Voir [Compatibilité des jeux](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compatibilite-des-jeux.md) et [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md).

## Modifier le wiki

Les pages sont maintenues dans `wiki/`. Le workflow [Publier le Wiki](https://github.com/GhostPunishR/RavenEmu/blob/main/.github/workflows/wiki.yml) les copie vers le dépôt Wiki après un push de ces fichiers sur `main`.

- Conservez les noms de page existants pour ne pas casser leurs liens.
- Mettez à jour `_Sidebar.md` lorsqu'une page est ajoutée.
- Vérifiez les cibles des liens Wiki (par exemple `[Accueil](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Home.md)`) et les liens source.
- N'utilisez pas de tiret cadratin : le workflow le refuse.
- Distinguez fonction implémentée, validation réelle et travail restant.

## Pull request et sécurité

Décrivez problème, comportement obtenu, modules touchés, preuves exécutées et limites. Évitez les changements indépendants regroupés dans la même PR.

Une vulnérabilité se signale par [avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new), selon la [politique de sécurité](https://github.com/GhostPunishR/RavenEmu/blob/main/SECURITY.md).
