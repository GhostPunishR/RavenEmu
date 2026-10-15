# Architecture

Le projet Gradle RavenEmu se trouve à la racine. `settings.gradle.kts` inclut `shared`, `androidApp` et `policy`. L'interface Compose Multiplatform est commune à Android et iOS ; chaque hôte apporte ses adaptateurs système.

| Dossier | Rôle |
| --- | --- |
| `shared/` | Interface, modèles et logique Kotlin commune |
| `androidApp/` | Lecteur, fichiers, audio, commandes, skins et services Android |
| `iosApp/` | Hôte SwiftUI et projet XcodeGen |
| `native/` | API C, JNI Android, module Swift et révision RavenCore épinglée |
| `policy/` | Règles de CI et chemins de la racine |

Les moteurs restent dans RavenCore. La bibliothèque native Android est `ravenemu_android` ; les adaptateurs appellent `ravenemu_*`. Les appels à une instance sont sérialisés et les données persistantes restent identifiées par le SHA-256 de la ROM.

Les documents légaux sont générés depuis `TERMS.md`, `PRIVACY.md` et `LICENSE` à la racine. Le déplacement conserve les identifiants et formats des données. Voir [ARCHITECTURE.md](https://github.com/GhostPunishR/RavenEmu/blob/main/ARCHITECTURE.md) pour les contrats et limites.
