# Patapps LoRaWAN

Application Android pour la réception et le traitement des alertes LoRaWAN via USB Serial.

## Fonctionnalités

- **Connexion USB Serial** - Auto-connexion aux modules LoRaWAN (CH340, CP2102, FTDI)
- **Système d'alertes à 3 niveaux** :
  - **INFO** - Notification simple
  - **WARNING** - Notification + vibration
  - **CRITICAL** - Écran plein, sonnerie, vibration continue, flash clignotant
- **Réveil automatique** - Les alertes critiques réveillent l'appareil même verrouillé
- **Console série** - Visualisation des messages reçus/envoyés
- **Auto-reconnexion** - Reconnexion automatique en cas de déconnexion

## Installation

### Téléchargement direct

Téléchargez la dernière version APK depuis la page [Releases](../../releases).

### Compilation depuis les sources

```bash
./gradlew assembleRelease
```

L'APK sera généré dans `app/build/outputs/apk/release/`

## Configuration requise

- Android 7.0 (API 24) minimum
- Support USB OTG
- Module LoRaWAN compatible USB Serial

## Permissions requises

- `USB_HOST` - Communication avec les modules USB
- `CAMERA` - Flash pour les alertes critiques
- `VIBRATE` - Vibration pour les alertes
- `POST_NOTIFICATIONS` - Affichage des notifications
- `WAKE_LOCK` - Réveil de l'appareil pour les alertes

## Mots-clés d'alerte

L'application détecte automatiquement le niveau d'alerte selon les mots-clés :

| Niveau | Mots-clés |
|--------|-----------|
| CRITICAL | ALERT, ALERTE, SOS, URGENT, CRITICAL, EMERGENCY |
| WARNING | WARN, WARNING, ATTENTION |
| INFO | INFO, OK, STATUS |

## Licence

© Patapps - Tous droits réservés

## Contact

Pour toute question ou support, contactez Patapps.
