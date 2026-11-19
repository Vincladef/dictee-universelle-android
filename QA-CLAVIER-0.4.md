# Dictée universelle 0.4 : protocole de validation Android

## Ce que la version contient

- Clavier AZERTY à touches arrondies, mode clair/sombre, majuscules simples ou verrouillées, accents au maintien, touches chiffrées et symboles, emojis.
- Effacement : sélection entière en un geste, appui répété, effacement rapide au maintien ; glissement vers la gauche depuis ⌫ pour sélectionner puis supprimer des mots.
- Déplacement du curseur par glissement horizontal sur la barre espace ; actions Entrée, Envoyer, Rechercher, Suivant selon le champ.
- Suggestions françaises locales et quelques corrections conservatrices ; presse-papiers système accessible.
- Dictée à quatre états : Enregistrer, Pause, Reprendre, Arrêter. STOP conserve l'audio et ne lance aucun appel réseau. Transcrire est une action explicite ; Insérer en est une autre.
- Pause/Reprendre/Arrêter disponibles depuis la notification écran verrouillé.
- Historique local, récupération de métadonnées après interruption et conservation des fragments audio validés.

## Tests manuels indispensables sur Motorola

1. **Effacer tout** : sélectionner trois paragraphes dans ChatGPT, puis toucher ⌫ une fois. Le passage entier disparaît, sans effacer seulement un caractère adjacent.
2. **Effacer au maintien** : maintenir ⌫ deux secondes ; le retrait se répète et s'arrête au relâchement.
3. **Glisser depuis ⌫** : glisser vers la gauche pour sélectionner plusieurs mots, puis relâcher ; contrôler le résultat. Le support varie selon l'éditeur.
4. **Caractères composés** : écrire « é👨‍👩‍👧‍👦❤️ » et effacer un élément ; vérifier le comportement dans plusieurs champs Android.
5. **Saisie ordinaire** : tester Shift, double Shift, accents de « éèêë », chiffres, ponctuation, emoji, retour à ABC.
6. **Touche d'action** : tester Rechercher (barre de recherche), Envoyer (chat), Suivant (formulaire), retour à la ligne (document).
7. **Champ sensible** : vérifier qu'on peut saisir un mot de passe au clavier sans déclencher la dictée ou les suggestions.
8. **Curseur** : glisser à droite/gauche sur la barre espace dans plusieurs éditeurs.
9. **Dictée** : commencer, mettre en pause, attendre, reprendre, arrêter ; vérifier que la durée n'augmente pas pendant la pause et qu'aucun appel IA n'est lancé avant Transcrire.
10. **Écran verrouillé** : démarrer, verrouiller, mettre en pause/reprendre via notification, arrêter, déverrouiller, retrouver l'audio.
11. **Résilience** : sans réseau, arrêter une dictée et constater qu'elle reste locale ; rétablir Internet, toucher Transcrire puis Insérer.
12. **Changement de champ** : enregistrer dans une application, ouvrir une autre, transcrire puis choisir explicitement le champ d'insertion.
13. **Compatibilité** : répéter sur WhatsApp, ChatGPT, Gmail, Chrome et un éditeur de documents.

## Périmètre honnête

Le clavier est inspiré des interactions et proportions habituelles de Gboard, sans être une copie de son code, de ses assets ou de son moteur. La prédiction locale est volontairement limitée ; le glissement permettant de former des mots, la correction grammaticale complexe et les dictionnaires multilingues de Gboard ne sont pas encore disponibles.

## Distribution

- Identité finale inchangée : fr.denizot.dictee.inline ; version 0.4.0.
- Build de test : applicationIdSuffix ".test" et nom « Dictée universelle TEST », afin de cohabiter avec l'ancienne application et protéger ses audios.
- **Ne pas désinstaller l'ancienne application pour tester.** Les données locales ne sont pas partagées entre les deux versions.
- Les mises à jour durables de l'application finale nécessitent une chaîne de signature Android permanente ; le canal OTA est actuellement préparé dans le dépôt mais non activé si les secrets de signature et de diffusion ne sont pas provisionnés.
