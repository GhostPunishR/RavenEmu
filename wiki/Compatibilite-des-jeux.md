# Matrice de compatibilité des jeux

Cette page concerne les tests de la **version multiplateforme** sur Android. Les résultats ancienne application ou ceux d'un moteur RavenCore construit séparément ne sont pas des validations du lecteur actuel.

Aucun jeu testé n'est encore renseigné dans cette matrice. Cela ne signifie pas que tous les jeux sont incompatibles : aucun résultat suffisamment documenté n'a été inscrit ici.

## Statuts

| Statut | Signification |
|---|---|
| Non testé | Aucun résultat vérifiable |
| Démarre | Un écran de jeu identifiable est atteint |
| Jouable avec problèmes | Progression possible, avec défauts importants |
| Jouable | Principales fonctions utilisables pendant le test décrit |
| Terminé | Partie complète validée |
| Incompatible | Échec reproductible de démarrage ou blocage immédiat |

## Résultats

| Console | Jeu et région/révision | SHA-256 ROM | Commit RavenEmu | Appareil et Android | Vidéo/audio | Sauvegarde/états | Statut | Issue |
|---|---|---|---|---|---|---|---|---|

## Protocole

1. Utilisez un build identifié de `multiplateforme.yml` et notez son **commit exact**. Le nom de version de prévisualisation seul ne distingue pas nécessairement deux builds.
2. Notez le modèle de l'appareil, la version Android, la console et la région/révision de la ROM.
3. Calculez le SHA-256 localement sans publier le fichier du jeu.
4. Testez démarrage, commandes, vidéo et audio ; indiquez la durée de l'essai.
5. Testez la sauvegarde normale, un état, puis la reprise après fermeture de l'application.
6. Si un défaut apparaît, essayez aussi un démarrage sans ancien état.
7. Précisez si RetroAchievements, les jaquettes en ligne ou des réglages particuliers sont actifs.
8. Ouvrez une issue reproductible et liez-la au résultat.

Les mesures de frames calculées ne sont pas des FPS d'écran garantis. Un succès RetroAchievements nécessite une vérification séparée de sa reconnaissance et de sa synchronisation côté serveur.

## Publier un rapport

Utilisez les [formulaires d'issues](https://github.com/GhostPunishR/RavenEmu/issues/new/choose). Ne joignez aucune ROM, aucun BIOS, aucune sauvegarde personnelle ni aucun jeton. Retirez les chemins et identifiants privés des journaux et captures.

Voir [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Consoles-et-compatibilite.md), [RetroAchievements](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/RetroAchievements.md) et [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md).
