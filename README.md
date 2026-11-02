# Blink Scroll

Appli Android perso pour faire défiler TikTok et Instagram sans les mains, par exemple pendant qu’on travaille au clavier avec le téléphone à côté.

## Déclencheurs (combinables)

| Déclencheur | Geste | Capteur |
|---|---|---|
| **Bruit appris** (par défaut) | ton bruit (clic de langue, « tss »…) : 1 fois = suivante, 2 fois = précédente | micro |
| **Main au-dessus du téléphone** | passer la main au-dessus du haut de l’écran : 1 fois = suivante, 2 fois = précédente | capteur de proximité |
| **Clin d’œil** | clin d’œil, yeux fermés longtemps ou double clignement | caméra frontale |

L’option « Deux fois de suite = vidéo précédente » peut être désactivée : chaque bruit ou passage de main fait alors avancer immédiatement.

## Apprendre son bruit

« Apprendre mon bruit » : (1) faire son bruit 5 fois, (2) taper au clavier 10 secondes (avec le son des vidéos si on les écoute), (3) tester, puis enregistrer. L’appli calcule un seuil entre ton bruit et le bruit du clavier, et affiche une fiabilité. Le curseur « Sensibilité » ajuste ensuite ce seuil ; l’écran de test affiche la ressemblance de chaque bruit détecté.

## Fonctionnement

- **Bruit** : le micro (16 kHz) est analysé en continu sur le téléphone. Chaque son bref et net est comparé à tes 5 échantillons (empreinte spectrale log-mel sur 64 ms) et aux bruits de clavier enregistrés. Rien n’est enregistré ni envoyé.
- **Défilement** : un service d’accessibilité simule un balayage. Il regarde seulement quelle appli est au premier plan ; il ne lit pas le contenu de l’écran.
- **Veille** : micro, capteur et caméra s’arrêtent dès que l’écran s’éteint, et 3 secondes après avoir quitté TikTok/Instagram. Ils redémarrent seuls au retour.
- **Confidentialité** : la permission Internet est retirée de l’appli, rien ne quitte le téléphone.

## Installation

1. Télécharger `BlinkScroll.apk` depuis la release *latest* du dépôt, l’ouvrir sur le téléphone (Play Protect : « Plus de détails » → « Installer quand même »).
2. Accessibilité → Applications installées → Blink Scroll. Si c’est grisé (« paramètre restreint ») : le toucher une fois, puis Paramètres → Applications → Blink Scroll → ⋮ → « Autoriser les paramètres restreints ».
3. Dans l’appli : « Apprendre mon bruit », puis « Démarrer ».

## Build

Chaque push sur `main` lance GitHub Actions : tests unitaires, APK signé publié en release *latest* et sur la branche `apk`, détails des tests et journal sur la branche `ci-results`. En local : `./gradlew assembleRelease` (JDK 17).

Code : `app/src/main/java/app/blinkscroll/` — `audio/` (capture, détection, empreintes, calibrage), `PulsePattern.kt` (simple/double), `ProximityTrigger.kt`, `BlinkDetector.kt` (clins d’œil), `DetectionService.kt` (service), `ScrollAccessibilityService.kt` (balayage), `MainActivity.kt` et `CalibrationActivity.kt` (écrans).
