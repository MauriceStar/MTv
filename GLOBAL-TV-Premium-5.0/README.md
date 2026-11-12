# GLOBAL TV Premium 5.0 — Android TV, Google TV et Android

Cette base de développement correspond à GLOBAL TV Premium 5.0.0.

## Configuration de compilation vérifiée

- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- Android SDK Platform 36
- Android Build Tools 36.0.0
- `minSdk 23`, `targetSdk 36`
- `applicationId: com.globaltv.app`
- `versionName: 5.0.0`, `versionCode: 6`

## Compilation locale

Le dépôt ne contient pas de Gradle Wrapper. Il faut donc installer Gradle 8.11.1 et lancer les commandes depuis ce dossier :

```bash
gradle lintDebug assembleDebug --no-daemon
```

Le workflow GitHub Actions `.github/workflows/build-apk.yml` installe automatiquement JDK 17, Gradle 8.11.1 et les composants SDK Android 36. Il exécute également Lint avant de compiler l'APK debug.

Dans GitHub : **Actions → Build Android APK**. L'artefact produit est `global-tv-debug-apk`.

## Version Release

Le projet ne configure pas encore de clé de signature de production. Une APK Release éventuellement compilée sans cette clé ne constitue pas un paquet prêt à publier. La signature de production devra être configurée dans les secrets GitHub, sans déposer de keystore ni de mot de passe dans le dépôt.

## Validation avant diffusion

La réussite de Lint et de la compilation ne remplace pas les essais sur appareils réels. Il reste notamment à valider la navigation à la télécommande, le lecteur interne, les pages WebView, la reprise après interruption, les réseaux instables et les formats d'écran Android TV/téléphone.
