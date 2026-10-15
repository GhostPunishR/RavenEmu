# Politique de sécurité

## Versions prises en charge

| Version | Prise en charge |
| --- | --- |
| Branche `main` et bêta v0.1.0 | Oui, version de développement |
| Anciennes préversions | Non maintenues dans ce dépôt |

Le canal actuel publie l'APK `ravenemu-android-beta` depuis GitHub Actions, avec une signature Debug de build. Il ne constitue pas une version stable ni une distribution iOS signée. Voir [RELEASING.md](RELEASING.md).

## Signaler une vulnérabilité

Ne publiez pas de vulnérabilité dans une issue, une discussion ou une pull request. Utilisez [un avis de sécurité privé GitHub](https://github.com/GhostPunishR/RavenEmu/security/advisories/new) pour un signalement confidentiel.

Indiquez une description, les commits concernés, les conditions d'exploitation, les étapes de reproduction et l'impact. N'incluez aucune ROM commerciale, aucun BIOS protégé, aucune clé privée ni donnée personnelle.

Le mainteneur vérifiera le problème et pourra demander des précisions. Une correction confirmée sera préparée avant une publication coordonnée ; merci de laisser un délai raisonnable avant divulgation publique. Le crédit du chercheur pourra être ajouté avec son accord.

## Périmètre

Application, analyse de fichiers non fiables, sauvegardes et états, dépendances, chaîne de construction, workflows, site et protection des secrets. Les bugs de compatibilité des jeux sans impact de sécurité et les demandes de fonctionnalité relèvent des [issues](https://github.com/GhostPunishR/RavenEmu/issues).
