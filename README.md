# Dictée universelle Android

Application Android de dictée, avec clavier intégré, microphone en arrière-plan, audio local et API de transcription séparée de l'Atelier BD.

## Mise à jour automatique : architecture de la v0.3

- Le clavier reste natif Kotlin. Contrairement à l'interface web de l'Atelier BD, une modification de l'APK exige une installation Android.
- **Identité permanente** : `fr.denizot.dictee.inline` ; ne plus modifier ce `applicationId` après la migration.
- **Signature permanente** : certificat PKCS12 alias `dictee`, chargé uniquement depuis les secrets privés de GitHub Actions. Ne jamais publier le keystore ni le mot de passe dans le dépôt ou l'APK.
- **Canal public d'actualisation** : `Vincladef/dictee-universelle-updates`, qui ne contient que le fichier `latest.json` et les APK de diffusion signées ; aucun audio ni secret.
- **Détection** : lancement de l'application, ouverture du clavier (contrôle plafonné à 6 h) et JobScheduler Android toutes les 12 h si le réseau est accessible.
- **Téléchargement** : HTTPS, URL de diffusion autorisée, checksum SHA-256 et limite de taille ; la notification invite à installer.
- **Installation** : l'utilisateur confirme la mise à jour via l'installateur Android. Une APK installée hors Google Play ne peut pas toujours s'installer silencieusement.

### Point bloquant à lever une seule fois

La version 0.2 était signée automatiquement par une clé de débogage GitHub Actions éphémère. Cette clé n'a pas été conservée. **Une nouvelle version signée de manière permanente ne peut pas remplacer la 0.2 en place** : il faudra migrer une fois, en sauvegardant les dictées de la 0.2 si nécessaire avant désinstallation. Les futures versions conserveront `applicationId` et la même signature.

Pour activer le canal signé, un administrateur doit provisionner en sécurité les trois secrets suivants dans [GitHub Actions secrets](https://github.com/Vincladef/dictee-universelle-android/settings/secrets/actions) :

1. `DICTEE_SIGNING_KEYSTORE_B64` : certificat Android privé PKCS12 (encodé Base64), alias `dictee`.
2. `DICTEE_SIGNING_PASSWORD` : mot de passe du keystore ; utilisé aussi comme mot de passe de la clé.
3. `DICTEE_DISTRIBUTION_TOKEN` : jeton GitHub à périmètre strict `Vincladef/dictee-universelle-updates` et seule permission `Contents: read/write`.

La création de ces secrets dans le compte GitHub ne doit pas être contournée par une clé intégrée au code source ou un secret enregistré en clair. Tant que cette configuration n'est pas terminée, **le workflow de diffusion est inactif** et `latest.json` annonce la version 0.

Le workflow [`Publier une mise à jour Android signée`](.github/workflows/publish-ota.yml) produit une APK de diffusion, vérifie sa signature et, lorsque les secrets sont présents, publie l'actif GitHub Releases puis modifie le manifeste. Les builds de vérification `assembleDebug` restent distincts et ne doivent pas servir aux mises à jour.

### Activation du clavier sur Android

Télécharger ne signifie pas installer et installer ne signifie pas sélectionner : dans l'application, toucher **Activer le clavier**, autoriser Dictée universelle, puis **Choisir le clavier actif**. La page affiche explicitement si le clavier est activé et sélectionné.

### Google Play

Pour une installation entièrement automatique sans demande d'approbation Android à chaque version, privilégier à terme une diffusion via Google Play (test interne ou fermé puis automatisation des mises à jour), avec un compte développeur et une signature cohérente.

---

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