# Google Drive : configuration du projet Google

Les sauvegardes cloud par Google Drive passent par l'API d'autorisation des
services Google Play. Google n'accepte une application que si un **projet
Google Cloud** la déclare : son nom de paquet et l'empreinte SHA-1 du
certificat qui la signe. Ce projet appartient au propriétaire de RavenEmu ;
aucun secret n'est à mettre dans le dépôt ni dans les secrets GitHub.

Sans cette configuration, **Se connecter avec Google** échoue avec « Google ne
reconnaît pas encore cette version de RavenEmu (identifiant OAuth à
déclarer) ».

## 1. Projet et API Drive

1. Sur [console.cloud.google.com](https://console.cloud.google.com/), créez un
   projet, par exemple « RavenEmu ».
2. **APIs et services → Bibliothèque** : activez **Google Drive API**.

## 2. Écran de consentement OAuth

1. **APIs et services → Écran de consentement OAuth** (Google Auth Platform) :
   type **Externe**.
2. Nom de l'application **RavenEmu**, adresse d'assistance, liens vers la
   [politique de confidentialité](PRIVACY.md) et les
   [conditions](TERMS.md) publiées.
3. **Accès aux données** : ajoutez seulement la portée
   `https://www.googleapis.com/auth/drive.file`. Google la classe parmi les
   portées non sensibles : pas d'audit de sécurité.
4. **Audience** : en mode **Test**, seuls les comptes ajoutés comme testeurs
   (100 au plus) peuvent se connecter, et leur accord expire après 7 jours.
   Pour tous les joueurs, passez l'application **En production**. Google peut
   demander une vérification de la marque (nom, logo, domaine).

## 3. Identifiants OAuth Android

**APIs et services → Identifiants → Créer des identifiants → ID client OAuth →
Android**, un par application signée :

| Application | Nom de paquet | Empreinte SHA-1 |
| --- | --- | --- |
| RavenEmu (Release, GitHub Releases) | `com.ravenemu` | celle de la clé de `ANDROID_KEYSTORE_BASE64` |
| RavenEmu Dev (compilation locale) | `com.ravenemu.dev` | celle de votre clé de débogage |

Empreinte de la clé de publication, sur votre ordinateur, avec le `.p12`
d'origine (Windows : `keytool` est dans le dossier `bin` du JDK) :

```bash
keytool -list -v -storetype PKCS12 -keystore ravenemu-release.p12
```

Copiez la ligne `SHA1:`. Pour la clé de débogage :
`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`.

L'APK de développement de la CI est signé avec une clé de débogage créée à
chaque compilation : son empreinte change, et Google Drive n'y fonctionne pas.
Testez Google Drive avec l'APK Release signé, ou avec une compilation locale
dont vous avez déclaré la clé.

Aucun identifiant client n'est à copier dans RavenEmu : Google reconnaît
l'application par son paquet et sa signature.

## 4. Afficher l'option dans RavenEmu

Tant que ce projet n'existe pas, **Paramètres → Sauvegardes cloud** ne propose
pas Google Drive. Une fois les étapes 1 à 3 faites, passez
`ravenemuGoogleDrive=true` dans `gradle.properties` par une pull request,
puis publiez une version ([RELEASING.md](RELEASING.md)).

## 5. Vérifier

Si la connexion échoue, RavenEmu affiche la réponse de Google :

- **« identifiant OAuth Android, code 10 » ou « code 12500 »** : l'identifiant OAuth Android manque, ou son nom de paquet ou son empreinte SHA-1 ne correspond pas à l'APK installé ;
- **« Google a refusé la connexion (code N) »** : un autre refus de Google, dont le code se cherche dans la documentation de Google Play services ;
- **« Connexion à Google annulée »** : l'écran de Google a été fermé sans choix.

Une fois la configuration faite :


Installez l'APK Release signé, ouvrez **Paramètres → Sauvegardes cloud →
Se connecter avec Google**, choisissez le compte et acceptez. Le dossier
**RavenEmu** apparaît à la racine du Drive, avec `ravenemu-saves.json`.
