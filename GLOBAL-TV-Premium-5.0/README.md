# GLOBAL TV Premium 5.0 — Android TV, Google TV et Android

Cette archive est une **base de développement 5.0.0** construite à partir de `GLOBAL-TV-Premium-4.1-MTV-Edition`.

## Objectifs de cette base
- Navigation D-pad avec styles de focus visibles.
- Défilement vertical avec barre de défilement visible.
- Amélioration des délais réseau et des en-têtes HTTP.
- Lecteur Media3/ExoPlayer conservé et cycle de vie centralisé.
- Bouton dédié de mode plein écran/écran normal.
- Tentative de lecture du média suivant lorsqu'un flux échoue.
- Conservation des sections M3U/Xtream et de la section MTV dédiée aux sources déclarées par l'utilisateur.
- Version Android `5.0.0` / `versionCode 6`.

## Limites à connaître
Cette base est une première intégration des fonctions 5.0, pas une version certifiée. Le passage automatique au suivant dépend de l'ordre du catalogue et ne peut pas garantir que le flux suivant soit valide. La navigation D-pad doit être vérifiée sur plusieurs télécommandes et tailles d'écran. Le mode plein écran masque les autres zones de l'interface pendant la lecture.

La navigation horizontale des filtres, une grille de focus entièrement déterministe, les tests de buffering sur réseaux instables, la validation des pages oxax.tv en WebView et les tests de non-régression restent à compléter.

## Compilation
- Android Studio récent
- JDK 17
- Android SDK Platform 35
- Gradle 8.9 (workflow GitHub Actions fourni)

Le projet ne contient pas de Gradle Wrapper. Sur GitHub : pousser le contenu du dossier à la racine du dépôt puis lancer **Actions → Build Android APK**. L'artefact attendu est `global-tv-debug-apk`.

## Validation obligatoire
Le code doit être compilé et testé sur appareils réels (Android TV/Google TV et téléphone Android) avant diffusion. Ce ZIP contient le projet source, pas un APK validé.
