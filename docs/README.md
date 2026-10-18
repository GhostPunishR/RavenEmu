# Site officiel RavenEmu

Sources du site statique publié par GitHub Pages depuis `main`, dossier `docs/` : <https://ghostpunishr.github.io/RavenEmu/>. Aucune dépendance ni compilation : ouvrez `index.html` ou servez ce dossier avec un serveur HTTP local.

## Direction visuelle

Sombre et violet, comme l'application.
- **Mise en page.** Une page unique en sections : ouverture, capture de l'application, fonctions en grille de cartes, matériel de la cartouche, jeu à deux (câble link), moteurs RavenCore, plateformes, installation, questions fréquentes.
- **Typographie.** Polices système uniquement : la politique de sécurité n'autorise aucune ressource externe.
- **Mouvement.**
  - Les sections apparaissent au défilement, par une animation CSS liée au défilement (`animation-timeline: view()`). Un navigateur qui ne la connaît pas affiche tout d'emblée.
  - La console de l'ouverture flotte doucement.
  - Tout mouvement est coupé quand l'appareil demande moins d'animations (`prefers-reduced-motion`).
- **Mobile.** Le menu devient une rangée défilante sous l'en-tête. Pas de page plus large que l'écran, de 320 px à l'écran de bureau.

## Visuels

- `assets/ravenemu-handheld.webp` : illustration de la marque (console portable au logo RavenEmu), présentée comme une illustration, pas comme un appareil réel.
- `assets/ravenemu-library-android.jpg` : capture réelle de la bibliothèque Android vide, fournie par le créateur, affichée sans retouche dans un cadre de téléphone.
- `assets/ravenemu-icon.png` : icône de l'application, recadrée depuis `ravenemu-logo.png` et aux coins transparents. Elle sert au logo du site, au favicon et à l'icône iOS.
- `assets/ravenemu-og.jpg` : image d'aperçu des liens partagés (1200 × 630).

Aucun jeu fictif ni capture inventée n'est présenté. Les écrans des cartes « consoles » et les nuanciers sont des dégradés abstraits.

## Fichiers

- `index.html` : contenu et structure.
- `assets/styles.css` : styles et adaptation aux écrans.
- `assets/app.js` : copie de la commande de compilation et année du pied de page. Navigation, contenu et questions fréquentes fonctionnent sans JavaScript.

## Contenu

La bêta se télécharge depuis les [Releases](https://github.com/GhostPunishR/RavenEmu/releases), sans compte GitHub : chaque CI réussie de `main` publie `ravenemu-beta.apk` signé dans une préversion `android-beta-N`. Chaque préversion conserve son APK. Le lien `/releases/latest` exclut les préversions ; utilisez `/releases`.

Sources de vérité :
- `README.md`, `BUILDING.md` et `CHANGELOG.md` ;
- le wiki (`wiki/`), en particulier Commandes, Affichage et audio, Sauvegardes et états, RetroAchievements ;
- le workflow et `PRIVACY.md`.

Ne présentez pas comme terminés sans confirmation : iOS jouable, déblocages RetroAchievements, compatibilité GBC/GBA. Quand l'épinglage de RavenCore change, mettez à jour la pastille de l'ouverture (« moteurs RavenCore x.y.z »).

## Cache des navigateurs

GitHub Pages laisse les navigateurs garder les fichiers 10 minutes. Sans précaution, un visiteur peut recevoir la nouvelle page avec l'ancienne feuille de style, et le rendu se casse. `index.html` appelle donc `styles.css` et `app.js` avec un numéro de version (`?v=AAAA-MM-JJ`) : **changez-le à chaque modification de l'un des deux**. Les icônes SVG ont aussi une taille par défaut dans le HTML, pour rester petites si la feuille de style ne charge pas.

## Vérification

- `node --check docs/assets/app.js` et `git diff --check`.
- Ancres, assets et liens GitHub.
- Rendu dans un navigateur à 1440, 820 et 390 px :
  - aucun débordement horizontal ;
  - aucune erreur dans la console ;
  - focus clavier visible.

Le site n'utilise que ses propres assets et aucun outil de suivi.
