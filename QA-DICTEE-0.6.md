# Dictée universelle 0.6.0 : recette sur Motorola (Android 14)

La v0.6 ajoute la commande des écouteurs filaires / Bluetooth. Les deux actions de notification Pause et Reprendre existaient dans la v0.5 ; la notification indique maintenant publiquement son état sur l'écran verrouillé (son affichage dépend des préférences Android).

## Vérifications prioritaires
1. Ouvrir un champ de texte, activer le clavier, démarrer la dictée et verrouiller le téléphone : l'audio continue.
2. Écran verrouillé : afficher la notification ; appuyer sur Pause ; vérifier que le chronomètre d'enregistrement cesse d'avancer et que Reprendre est proposé ; appuyer sur Reprendre.
3. Pendant l'enregistrement, écran verrouillé : appuyer une fois sur lecture/pause des écouteurs ; vérifier l'arrêt de la capture. Appuyer à nouveau : vérifier la reprise. Tester aussi écran allumé.
4. Si le bouton envoie un événement PLAY explicite, il doit reprendre ; si PAUSE, il doit suspendre ; si PLAY_PAUSE/HEADSETOOK, il doit inverser l'état.
5. Vérifier qu'une seule pression ne déclenche jamais deux changements d'état (ACTION_DOWN et ACTION_UP).
6. Répéter avec lecteur de musique actif ; Android peut donner la priorité à une autre session multimédia : dans ce cas, la notification est le contrôle de secours.
7. Arrêter et sauvegarder ; vérifier les segments audio, la durée effective hors pauses et l'accès à la transcription sans envoi automatique.
8. Changer de champ pendant la transcription ; vérifier qu'aucun texte ne s'insère dans un autre champ et que la sauvegarde locale subsiste.
9. Refuser l'autorisation de notification puis la réactiver ; les commandes de l'écran verrouillé peuvent être masquées si l'utilisateur refuse.
10. Vérifier que la session multimédia est libérée à l'arrêt et que les touches de lecture/pause reviennent au lecteur musical.

## Diffusion
- Version 0.6.0, versionCode 7.
- APK de test : applicationIdSuffix `.testv06` ; n'écrase pas l'installation de production.
- Aucun test sur matériel réel n'est revendiqué ici.
