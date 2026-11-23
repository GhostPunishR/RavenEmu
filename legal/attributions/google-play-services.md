# Google Play services : connexion à Google Drive

Les sauvegardes cloud par **Google Drive** (**Paramètres → Sauvegardes cloud**)
utilisent la bibliothèque **Google Play services** de Google pour la connexion
au compte. Le propriétaire a accepté cette dépendance le 23 novembre 2026, en
connaissance de ses conséquences.

| Composant | Version | Licence | Sources |
| --- | --- | --- | --- |
| `com.google.android.gms:play-services-auth` | 22.0.0 | [Android Software Development Kit License](https://developer.android.com/studio/terms) | non publiées par Google |
| `play-services-base`, `-basement`, `-tasks`, `-auth-base`, `-auth-api-phone`, `-fido` (dépendances) | celles résolues par Gradle | même licence | non publiées |
| `kotlinx-coroutines-*` (dépendances, JetBrains) | 1.8.0 | Apache 2.0 | publiées |

- **Usage** : uniquement l'API d'autorisation (`Identity.getAuthorizationClient`)
  de `platform/GoogleDriveAuth.java`, pour l'autorisation `drive.file`. Les
  échanges avec Google Drive passent par l'API REST v3 en HTTPS, écrits pour
  RavenEmu (`platform/GoogleDriveHttp.java`), sans le client Java de Google.
- **Sans copie ni modification** : la bibliothèque est résolue par Gradle
  depuis le dépôt Maven de Google, à une version épinglée
  (`gradle/libs.versions.toml`). Aucun fichier n'est copié dans le dépôt.
- **Sources** : Google ne publie pas les sources de ces modules. L'archive de
  sources jointe à l'APK contient leur POM et leur binaire, et
  `DEPENDENCIES.tsv` indique « - » pour leurs sources ; seul le groupe
  `com.google.android.gms` y est autorisé (`scripts/package_native_sources.py`).
- **Crédits** : `android/src/main/assets/licenses/NOTICE.txt`, onglet
  « Crédits » de **Paramètres → À propos → Licences et crédits**.
- **Téléphones sans services Google** (Huawei récents, ROM sans Google) :
  Google Drive y est indisponible et l'écran le dit ; le dossier WebDAV reste
  possible. Une distribution par F-Droid exclurait cette dépendance.

## Point à faire vérifier avant une publication

RavenEmu est sous **AGPLv3**, et ses pilotes AAudio et GLES2 reprennent des
portions de RetroArch sous **GPLv3 ou ultérieure** ([retroarch.md](retroarch.md)).
Ces licences demandent les « sources correspondantes » de l'œuvre distribuée,
avec une exception pour les « bibliothèques système ». Il faut savoir si cette
exception couvre la bibliothèque cliente des services Google Play, qui
s'appuie sur l'application Google Play services installée sur le téléphone.
Pour le code dont il est l'auteur, le propriétaire peut ajouter une
permission additionnelle (AGPLv3, section 7). Pour les portions de RetroArch,
il ne le peut pas. **Ce point relève d'un avis juridique, que ce document ne
remplace pas** ; il est signalé au propriétaire avant la publication d'une
version qui contient Google Drive.
