# Matrice de compatibilité des jeux

Cette page rassemble des résultats reproductibles pour les jeux testés avec RavenEmu. Elle ne contient aucun lien de téléchargement et ne doit recevoir aucune ROM, aucun BIOS ni aucune sauvegarde protégée.

La prise en charge générale des consoles est décrite dans [[Consoles et compatibilité|Consoles-et-compatibilite]].

## Statuts

| Statut | Signification |
|---|---|
| Non testé | Aucun résultat vérifiable |
| Démarre | Le jeu atteint un écran identifiable |
| Jouable avec problèmes | La progression est possible avec des défauts importants |
| Jouable | Les fonctions principales sont utilisables |
| Terminé | Une partie complète a été validée |
| Incompatible | Le jeu ne démarre pas ou bloque immédiatement |

## Résultats validés

Aucun jeu ne doit être ajouté sans version RavenEmu, appareil, région ou révision et résultat reproductible.

| Console | Jeu | Région ou révision | Empreinte SHA-256 | Version RavenEmu | Appareil et Android | Vidéo | Audio | Sauvegarde | Statut | Issue |
|---|---|---|---|---|---|---|---|---|---|---|

## Protocole de test

1. Utiliser la dernière version concernée de RavenEmu.
2. Noter le numéro de version ou le commit exact.
3. Identifier la console, la région et la révision du jeu.
4. Calculer une empreinte SHA-256 sans partager le fichier.
5. Tester le démarrage, les commandes, la vidéo, l’audio et la sauvegarde.
6. Recommencer sans charger un ancien état instantané si un blocage apparaît.
7. Ouvrir un rapport de bug pour tout défaut reproductible.
8. Ajouter le lien de l’issue dans la matrice.

## Informations à fournir

- modèle de l’appareil ;
- version Android ;
- version ou commit RavenEmu ;
- console et révision du jeu ;
- empreinte SHA-256 ;
- durée approximative du test ;
- statut atteint ;
- problèmes vidéo, audio ou de commandes ;
- résultat de la sauvegarde en jeu ;
- utilisation éventuelle de l’avance rapide ou d’un état instantané.

## Règles de publication

- ne jamais joindre une ROM ou un BIOS ;
- ne pas publier une sauvegarde contenant des données protégées ;
- retirer les chemins et identifiants personnels des captures et journaux ;
- distinguer un défaut du moteur d’un fichier corrompu ;
- ne pas déclarer un jeu terminé sans test complet ;
- utiliser uniquement des copies autorisées ou des homebrews légalement distribués.

Utilisez le [formulaire de rapport de bug](https://github.com/GhostPunishR/RavenEmu/issues/new/choose) pour proposer un résultat ou signaler une régression.
