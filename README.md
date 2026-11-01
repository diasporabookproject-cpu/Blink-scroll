# Blink Scroll

Appli Android qui fait défiler TikTok et Instagram d’un clin d’œil, grâce à la caméra frontale.

## Fonctionnement

- **Détection** : la caméra frontale tourne dans un service de premier plan (notification « Blink Scroll actif »). ML Kit (Google, en local, rien n’est envoyé) estime à chaque image si chaque œil est ouvert.
- **Défilement** : un service d’accessibilité simule un balayage vers le haut (vidéo suivante) ou vers le bas (précédente). Il regarde seulement quelle appli est au premier plan ; il ne lit pas le contenu de l’écran.
- **Économie** : hors de TikTok/Instagram, ou écran éteint, l’analyse est suspendue.

## Déclencheurs

| Mode | Geste |
|---|---|
| Clin d’œil (défaut) | un œil fermé ~0,25 s → suivante |
| Gauche/droite | œil droit → suivante, œil gauche → précédente |
| Yeux fermés longtemps | les deux yeux fermés ≥ 0,5 s → suivante |
| Double clignement | deux clignements rapides → suivante |

Les clignements naturels (les deux yeux, ~0,1–0,4 s) sont ignorés en mode clin d’œil.

## Installation

1. Télécharger `BlinkScroll.apk` (onglet *Actions* → dernier build → artefact *BlinkScroll-apk*), l’ouvrir sur le téléphone, accepter l’installation d’applis inconnues (et « Installer quand même » si Play Protect avertit).
2. Ouvrir Blink Scroll → **Autoriser** la caméra.
3. **Activer** le service d’accessibilité. Si Android parle de « paramètre restreint » : Infos de l’appli → menu ⋮ → « Autoriser les paramètres restreints », puis réessayer.
4. **Démarrer**, tester le clin d’œil dans l’écran de test, puis ouvrir TikTok ou Instagram.

## Build

Chaque push sur `main` lance GitHub Actions (tests unitaires + APK signé). En local : `./gradlew assembleRelease` (Android Studio, JDK 17).

Code principal : `app/src/main/java/app/blinkscroll/` — `BlinkDetector.kt` (logique de détection, testée), `BlinkCameraService.kt` (caméra + ML Kit), `ScrollAccessibilityService.kt` (balayage), `MainActivity.kt` (écran de réglages).
