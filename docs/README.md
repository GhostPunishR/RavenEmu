# Site officiel RavenEmu

Sources du site statique, prêtes pour GitHub Pages depuis `main`, dossier `docs/`. L’hébergement Pages doit être activé dans les paramètres du nouveau dépôt pour publier cette adresse. Aucune dépendance ni compilation : ouvrir `index.html` ou servir ce dossier avec un serveur HTTP local.

Direction visuelle : sombre minimaliste, composition centrée sur le produit, violet discret. Pas d'animation, de transition ou de défilement animé. Le visuel de console de l'ancien site n'est plus utilisé. Le bloc de présentation utilise une capture réelle de la bibliothèque Android vide (`assets/ravenemu-library-android.jpg`), fournie par le créateur. Elle est affichée sans retouche, avec un lien pour l'agrandir et des instructions d'import. Aucun jeu fictif ni contrôle décoratif n'est présenté.

- `index.html` : expérience Android, état des plateformes, installation de la bêta, ressources et FAQ.
- `assets/styles.css` : composition et styles responsive.
- `assets/app.js` : copie de la commande et année. Navigation, contenu et FAQ fonctionnent sans JavaScript.

La bêta actuelle est l'artefact `ravenemu-android-beta` de `multiplateforme.yml` (compte GitHub requis, rétention 7 jours).

Sources de vérité : `README.md`, `BUILDING.md` et `CHANGELOG.md`, le workflow et `PRIVACY.md`. Ne pas présenter iOS jouable ou les validations RetroAchievements/GBC/GBA comme terminés sans confirmation.

Vérification : `node --check docs/assets/app.js`, `git diff --check`, ancres, assets et chemins GitHub ; vérifier aussi le rendu desktop/mobile, le focus clavier et les débordements dans un navigateur. Le site utilise uniquement ses assets locaux et aucun outil de suivi.
