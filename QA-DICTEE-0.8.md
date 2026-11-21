# Dictée universelle 0.8.0 : MediaStyle expérimental

## Objectif
Tester un panneau Android plus visible sur écran verrouillé, sans audio fictif ni musique silencieuse.
Une notification classique reste sélectionnable à tout moment depuis l'application.

## Implémentation
- Notification.MediaStyle avec MediaSession.Token et deux actions compactes.
- PlaybackState et MediaMetadata mis à jour à chaque pause / reprise ; commandes de session Play, Pause, Stop et action personnalisée Arrêter et sauvegarder.
- Illustration générée localement en mémoire : microphone rouge durant la capture ; pause grise lorsque suspendue.
- Préférence stockée en local, sans toucher à l'audio ni au backend.
- Notification classique et ses deux actions conservées en repli automatique si MediaStyle échoue, ainsi qu'en mode manuel.
- Initialisation de MediaSession avant startForeground afin de disposer de son token.
- Bouton d'écouteurs : PLAY ne peut que reprendre, PAUSE ne peut que suspendre ; HEADSETHOOK et PLAY_PAUSE alternent.

## Recette Motorola Android 14
1. Installer la v0.8 TEST ; elle s'installe à côté des autres versions (.testv08).
2. Autoriser le microphone et les notifications, puis activer l'affichage sur écran verrouillé.
3. Démarrer une dictée. Écran allumé : vérifier le panneau compact et l'action Pause. Pause doit cesser la capture.
4. Verrouiller le téléphone. Tester la visibilité sans ouvrir le volet. Les décisions d'affichage restent du ressort d'Android / Motorola.
5. Reprendre depuis l'écran verrouillé ; vérifier que la capture reprend et que le panneau change d'état.
6. Si le panneau est invisible ou inutilisable, ouvrir l'application et choisir « Présentation : notification classique » ; vérifier que Pause/Reprendre redeviennent accessibles.
7. Arrêter et sauvegarder ; vérifier que l'audio existe et que la transcription n'est pas déclenchée automatiquement.
8. Tester les écouteurs si l'autorisation d'accessibilité a été activée pour **cette version**.
9. Tester YouTube / Spotify ; ne pas tenter de récupérer prioritairement leurs commandes hors dictée.
10. Vérifier qu'un redémarrage de la dictée n'ouvre pas deux panneaux et que les commandes de la session disparaissent après l'arrêt.

## Limites
- MediaStyle est conçu pour la lecture multimédia ; Android 13+ détermine la disposition réelle depuis PlaybackState.
- Aucune garantie que le panneau soit directement visible sur le verrouillage.
- État testé automatiquement : compilation Kotlin et tests unitaires. Recette réelle Motorola non réalisée.
