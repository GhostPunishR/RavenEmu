# Architecture

RavenEmu est une application Android native, organisée comme un frontend Libretro : une interface Java, un frontend en C et les moteurs de RavenCore, chargés comme cores Libretro.

| Dossier | Rôle |
| --- | --- |
| `android/src/main/java/` | Interface Java : bibliothèque, fiche du jeu, lecteur, réglages, stockage privé et services Android |
| `android/src/main/cpp/` | Pont JNI et pilotes natifs en C : image GLES2, son AAudio, commandes |
| `frontend/` | Frontend C : chargement des cores Libretro, sessions, sauvegardes, états, RetroAchievements |
| `tests/` | Tests des pilotes natifs sur ordinateur |
| `policy/` | Règles du dépôt vérifiées en CI |

Les moteurs GB, GBC et GBA restent dans RavenCore, en C++20 : l'application ne les appelle que par l'ABI Libretro. Chaque jeu a un seul fil d'émulation, et les données persistantes restent identifiées par le SHA-256 de la ROM. L'application est écrite en Java, JNI et C, sans Kotlin ni Compose.

L'ancienne bêta Kotlin/Compose et l'application iOS ont été retirées le 6 novembre 2026 ; leur code reste dans l'historique git. Voir [ARCHITECTURE.md](https://github.com/GhostPunishR/RavenEmu/blob/main/ARCHITECTURE.md) pour les contrats et limites.
