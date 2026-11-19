# Dictée universelle : prototype Android 0.1.0

Application Android **distincte** de l'Atelier BD. Code source Kotlin natif, sans Neon et sans nouveau projet Vercel.

## Fonctions dans cette première version

- Enregistreur AAC/M4A avec service microphone Android de premier plan, notification avec action **Terminer**, compatible avec le verrouillage de l'écran tant que le système ne tue pas le service.
- Enregistrements conservés en dossiers privés sur l'appareil, segmentés environ toutes les 3 minutes, sans synchronisation dans le cloud.
- Historique local, lecture, suppression, copie et partage des transcriptions.
- Clavier AZERTY Android simple, activable comme méthode de saisie. En cliquant sur *Dicter* puis *Terminer*, il tente d'insérer la transcription dans le champ encore actif ; sinon l'audio et le texte restent dans l'historique.
- Envoi manuel ou après dictée depuis le clavier vers une API Vercel personnelle en HTTPS, segmentation limitée à 3 Mo par requête.

**Important :** cette première version est un prototype à tester sur le Motorola. Il n'inclut pas de vérification automatique sur appareil réel, ni une reprise fiable après arrêt forcé d'Android. L'IME ne remplace pas toutes les fonctions avancées de Gboard (suggestions, correction, emojis, etc.).

## Préparer le backend

1. Copier `atelier-bd-backend/api/android-dictation.ts` vers `api/android-dictation.ts` dans le dépôt existant `Vincladef/atelier-bd`. Ce fichier ajoute une route sans modifier la transcription déjà présente.
2. Configurer `DICTATION_ANDROID_TOKEN` avec une valeur aléatoire privée d'au moins 32 caractères dans les variables d'environnement **serveur** Vercel du projet Atelier BD, puis déployer ce changement.
3. Réutiliser la variable **serveur** `OPENAI_API_KEY` existante. Ne jamais la mettre dans l'application Android.
4. Installer l'APK sur le téléphone, accorder l'accès au microphone, entrer la valeur `DICTATION_ANDROID_TOKEN` dans l'application et enregistrer les paramètres.
5. Enregistrer une dictée et essayer la transcription après l'arrêt. Vérifier le plafond de facturation OpenAI dans les paramètres du projet OpenAI.

Le code d'accès est enregistré dans les préférences privées de l'application Android. Cela protège de l'exposition accidentelle dans l'APK, mais ne remplace pas une authentification à rotation ou la gestion de quotas si l'application devait être distribuée publiquement. **Ne pas distribuer l'APK avec un secret prérempli.**

## Compiler

Avec Android SDK 35, JDK 17 et Gradle 8.12 :

```bash
cd android
gradle --no-daemon :app:assembleDebug
```

APK généré : `android/app/build/outputs/apk/debug/app-debug.apk`.

Un workflow GitHub Actions est aussi fourni à la racine pour générer un APK de test sans SDK local.

## Respect de l'existant

Aucune migration Neon, aucune modification de documents existants ni des routes de transcription Atelier BD. Seule la nouvelle route Vercel doit être ajoutée à la demande, avant que l'IA puisse fonctionner.