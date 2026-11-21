# Dictée universelle 0.7.0 : recette ciblée Motorola Android 14

## Constats à l'origine de la correction
- Les commandes de la notification existaient mais la permission POST_NOTIFICATIONS n'était pas demandée depuis le clavier quand RECORD_AUDIO était déjà accordée.
- Le canal historique était de faible importance, et l'importance d'un canal existant ne peut pas être modifiée après sa création.
- Android 8+ route les boutons média en priorité vers une session qui a effectivement joué de l'audio. Un enregistreur n'est pas forcément prioritaire.
- La 0.7 ajoute un mode **facultatif** d'accessibilité pour capter les touches. L'utilisateur doit l'activer explicitement.

## Fonctionnalités
- Nouveau canal `microphone_controls_v2`, importance DEFAULT, visibilité publique, sans son ni vibration.
- Autorisation de notifications demandée depuis le clavier et depuis l'app (Android 13+).
- État des autorisations et diagnostics dans l'application.
- Service d'accessibilité à portée limitée : ne demande pas l'accès au contenu des fenêtres ; intercepte seulement HEADSETHOOK, PLAY_PAUSE, PLAY, PAUSE quand une capture est en cours ; ne touche pas aux autres touches.
- Un événement d'appui provoque une seule transition Pause/Reprendre ; les répétitions et relâchements sont ignorés.
- MediaSession conservée comme solution sans autorisation spéciale mais non fiable sur tous les téléphones.
- Le téléphone ne doit jamais démarrer une nouvelle dictée via une touche d'écouteurs quand la capture est arrêtée.

## Procédure Motorola
1. Installer l'APK de test 0.7.0 (application séparée de 0.6.0 ; suffixe testv07).
2. Ouvrir Dictée universelle 0.7, activer les notifications et autoriser l'affichage sur écran verrouillé dans Android/Motorola.
3. Toucher « Configurer la commande des écouteurs » ; rechercher « Dictée : boutons des écouteurs » dans Accessibilité ; activer volontairement le service si l'utilisateur accepte ses implications.
4. Démarrer une dictée puis vérifier en déverrouillé qu'une pression du bouton central provoque une seule pause, puis une reprise.
5. Regarder le statut « Dernière touche » dans l'application ; s'il reste inchangé, le système n'a pas délivré l'événement au service.
6. Verrouiller le Motorola, vérifier que la notification est visible et ses actions utilisables, tester ensuite la touche des écouteurs.
7. Vérifier qu'une lecture musicale normale fonctionne quand aucune dictée n'est en cours.
8. Arrêter et confirmer que l'audio est sauvegardé et que la transcription n'a pas été envoyée automatiquement.

## Limites
- Android et la couche Motorola restent maîtres de l'affichage sur écran verrouillé.
- Un service d'accessibilité peut ne pas recevoir toutes les touches matérielles lorsque l'écran est éteint ou si un autre service intercepte déjà les touches.
- Il n'y a aucune garantie d'efficacité sans test sur le véritable téléphone.
