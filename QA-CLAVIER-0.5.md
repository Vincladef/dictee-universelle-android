# Dictée universelle 0.5.0 TEST : plan de recette Android

## Changements réalisés
- Bandeau d'actions compact, icônes seules, descriptions d'accessibilité et infobulles.
- Suppression de la ligne permanente d'aide et du presse-papiers redondant ; suggestions limitées aux propositions disponibles.
- Touches à fond dynamique dès ACTION_DOWN ; prévisualisation de la lettre au toucher ; retour haptique et son conformes aux réglages Android.
- Maintien de la touche Retour arrière pour répéter, effacement complet de la sélection, déplacement du curseur par glissement sur Espace.
- Pause / Reprise / Arrêt indépendants ; STOP ne déclenche jamais la transcription.
- « Transcrire » insère directement le texte dans le champ actif ; jamais d'envoi du message.
- Si le champ change pendant la transcription, aucune insertion automatique ne se produit ; seule une commande de secours ⇥ est proposée.

## Tests à effectuer sur Motorola (Android 14)
1. Dans WhatsApp, ChatGPT, Gmail et un éditeur, taper AZERTY à deux mains ; contrôler feedback pressé, aperçu lettre, haptique et réactivité. Vérifier que le popup ne masque pas la zone de saisie.
2. Tester accents au maintien, Majuscule et double Majuscule ; vérifier que l'aperçu disparaît au relâchement, même après changement de clavier.
3. Faire un appui court puis maintenir ⌫ 2 s ; vérifier la répétition, puis sélectionner un paragraphe et le supprimer d'une touche.
4. Glisser sur la barre Espace pour déplacer le curseur, puis insérer des lettres.
5. Dictée : démarrer, pause, reprise, arrêt ; la sauvegarde doit rester locale et ne pas déclencher d'appel réseau.
6. Dans le même champ, toucher ✍ une seule fois : transcription réseau puis insertion automatique au curseur, sans toucher ⇥, sans action « Envoyer ».
7. Pendant la transcription, changer de champ puis revenir : aucune insertion intempestive ; ⇥ devient visible et insère le texte uniquement sur appui.
8. Désactiver la connexion durant l'appel : l'audio demeure dans l'historique et aucune insertion vide ne se produit.
9. Tester les champs protégés : aucune dictée ou suggestion personnelle, mais les frappes ordinaires restent possibles.
10. Sur les thèmes clair et sombre : contraste, hauteur du bandeau, visibilité des icônes et raccourcis d'accessibilité.
11. Vérifier enregistrement avec écran verrouillé, pause/reprise depuis la notification, retour à l'IME.
12. Contrôler qu'une transcription déjà insérée ne se réinsère pas spontanément après un nouveau focus.

## Distribution et état de validation
- Version de code : 0.5.0 ; versionCode 6 ; build debug en applicationId suffix « .test ».
- Ne pas remplacer l'application principale ni perdre ses enregistrements.
- Un APK debug signé par une nouvelle clé peut entrer en conflit avec une ancienne installation TEST ; il faut conserver une clé de signature stable pour de véritables mises à jour.
- Les modifications source ont été enregistrées, mais cette recette sur matériel réel et la compilation restent à valider.
