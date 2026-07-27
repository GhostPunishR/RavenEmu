# Politique de sécurité

La sécurité de RavenEmu, de ses utilisateurs et de sa chaîne de construction est prise au sérieux.

## Versions prises en charge

| Version | Prise en charge |
|---|---|
| Branche `main` | Oui |
| Préversion `test-latest` | Tests uniquement |
| Anciennes constructions | Non |

La préversion `debug-latest` n'existe plus. Le seul APK publié est l'APK Test, signé par une clé dédiée, distincte de celle des versions Release ; il sert aux essais et ne constitue pas une version stable.

Un APK Debug se construit localement, ne circule pas et n'est couvert par aucune de ces garanties.

## Signaler une vulnérabilité

Ne publiez pas de vulnérabilité dans une issue, une discussion ou une pull request.

Utilisez [un avis de sécurité privé GitHub](https://github.com/GhostPunishR/RavenEmu/security/advisories/new) afin de transmettre le signalement de manière confidentielle.

Le rapport doit contenir autant que possible :

- une description claire de la vulnérabilité ;
- les versions ou commits concernés ;
- les conditions nécessaires à son exploitation ;
- des étapes minimales de reproduction ;
- l'impact potentiel ;
- une proposition de correction si vous en avez une ;
- toute information utile pour vérifier le problème.

N'incluez aucune ROM commerciale, aucun BIOS protégé, aucune clé privée ni aucune donnée personnelle dans le rapport.

## Traitement du signalement

Le mainteneur accusera réception dès que possible, vérifiera le problème et pourra demander des précisions. Si la vulnérabilité est confirmée, la correction sera préparée avant une publication coordonnée.

Merci de laisser un délai raisonnable pour analyser et corriger le problème avant toute divulgation publique. Le crédit du chercheur pourra être ajouté à l'avis de sécurité avec son accord.

## Périmètre

Les signalements pertinents peuvent concerner :

- l'application Android ;
- la lecture et l'analyse de fichiers non fiables ;
- les sauvegardes et états instantanés ;
- les dépendances et la chaîne de construction ;
- les workflows GitHub Actions ;
- le site officiel et sa publication ;
- une fuite de secret ou une permission excessive.

Les problèmes de compatibilité d'un jeu, les défauts graphiques sans impact de sécurité et les demandes de fonctionnalité doivent être signalés dans les issues ordinaires.

## Tests responsables

N'effectuez pas de test susceptible de dégrader un service, d'accéder aux données d'un tiers ou de contourner une autorisation. Utilisez uniquement vos propres appareils, fichiers et comptes.

## Conseils aux utilisateurs

- Téléchargez RavenEmu uniquement depuis le dépôt officiel.
- Vérifiez l'empreinte SHA-256 publiée à côté de l'APK Test.
- Vérifiez le certificat de signature. Son empreinte est publiée dans les notes de chaque préversion et **ne change pas** d'une version à l'autre : si elle change, l'APK ne vient pas de ce dépôt.

  ```bash
  sha256sum -c RavenEmu-test.apk.sha256
  apksigner verify --print-certs RavenEmu-test.apk
  ```

- N'installez pas un APK modifié provenant d'une source inconnue.
- Utilisez uniquement des ROM, BIOS et contenus que vous êtes autorisé à posséder et à employer.
