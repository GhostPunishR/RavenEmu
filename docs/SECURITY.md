# Politique de sécurité de RavenEmu

La sécurité des utilisateurs, des données locales et de la chaîne de
construction fait partie des priorités du projet.

## Versions prises en charge

RavenEmu est en développement actif. Les correctifs de sécurité sont appliqués
en priorité à la branche `main` et aux versions publiées les plus récentes.

Les anciennes versions peuvent ne pas recevoir de correctif séparé.

## Signaler une vulnérabilité

Ne publiez pas de détails exploitables dans une issue, une discussion ou une
pull request publique.

Procédure recommandée :

1. Utilisez le signalement privé de vulnérabilité GitHub lorsque cette option
   est disponible dans l'onglet Security du dépôt.
2. Si cette option n'est pas disponible, contactez le mainteneur
   `@GhostPunishR` par un canal privé connu.
3. Ne joignez aucune ROM commerciale, sauvegarde personnelle, clé, keystore ou
   donnée privée au rapport.

Un bon rapport contient :

- un titre clair ;
- la version ou le commit concerné ;
- le composant affecté ;
- les conditions nécessaires ;
- une méthode de reproduction minimale ;
- l'impact attendu ;
- une proposition de correction, si disponible.

Utilisez des fichiers synthétiques ou libres pour la reproduction.

## Délai de traitement

Le mainteneur essaiera de :

- confirmer la réception du rapport ;
- évaluer sa gravité ;
- demander les informations manquantes ;
- préparer un correctif ;
- coordonner la publication lorsque cela est nécessaire.

Aucun délai fixe n'est garanti, mais les problèmes permettant une exécution de
code, une corruption de données ou une exposition de secrets sont prioritaires.

## Périmètre de sécurité

Les signalements utiles comprennent notamment :

- crash provoqué par une ROM ou un fichier spécialement construit ;
- allocation mémoire excessive ;
- lecture ou écriture hors limites ;
- corruption de sauvegarde ;
- parsing XML, JSON ou binaire non borné ;
- traversée de chemin ;
- mauvaise utilisation du Storage Access Framework ;
- fuite de données locales ;
- secret ou keystore exposé ;
- dépendance compromise ;
- configuration GitHub Actions dangereuse ;
- artefact Release incorrectement signé ;
- contournement d'une validation de format.

Les problèmes de compatibilité d'un jeu, les baisses de FPS et les défauts
graphiques ordinaires ne sont pas des vulnérabilités, sauf s'ils permettent une
corruption, une fuite de données ou un déni de service reproductible.

## Données considérées comme sensibles

Ne publiez jamais :

- ROM commerciales ;
- BIOS propriétaires ;
- sauvegardes personnelles ;
- clés de signature ;
- mots de passe ;
- jetons GitHub ;
- chemins contenant des informations personnelles ;
- journaux contenant des données de jeu privées.

## Règles pour les correctifs

Un correctif de sécurité doit autant que possible :

- ajouter un test de non-régression ;
- borner les tailles avant allocation ;
- valider les longueurs et les offsets ;
- éviter les erreurs silencieuses ;
- conserver les données existantes en cas d'échec ;
- ne pas ajouter de télémétrie ;
- documenter l'impact et les versions concernées.

## Publication responsable

Merci de laisser au mainteneur un temps raisonnable pour analyser et corriger
le problème avant toute publication détaillée.

Une reconnaissance publique peut être ajoutée avec l'accord de la personne
ayant signalé la vulnérabilité.
