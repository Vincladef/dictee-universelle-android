# Migration vers un seul clavier Dictée universelle

## Principe

La nouvelle application de production porte **`fr.denizot.dictee.inline`** et conserve un certificat de signature permanent. Les APK de test (`.testv07`, `.testv08`, etc.) étaient des applications Android distinctes. Android interdit à la nouvelle application de lire leurs fichiers privés.

**Ne désinstallez pas l'ancienne application tant que la sauvegarde n'est pas vérifiée.**

## Sauvegarde sans modification de l'appareil

1. Sur Windows, installez Python 3 et les [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools), puis vérifiez que `adb` fonctionne dans un terminal.
2. Sur le téléphone, activez les options de développement et le débogage USB. Branchez-le à l'ordinateur, déverrouillez l'écran et acceptez l'autorisation USB.
3. Dans le dossier du dépôt GitHub, lancez `python tools/backup_legacy_dictations.py`.
4. Le script découvre les packages de Dictée universelle déjà installés. Il tente d'exporter leurs dossiers `files/dictations` dans un ZIP local ; il ne modifie ni les applications ni leurs fichiers.
5. Si l'ancien APK est débogable et qu'une sauvegarde est créée, copiez le ZIP sur le téléphone.
6. Ouvrez l'application de production 0.9.0 et choisissez **Importer une sauvegarde (.zip)**. Les sessions déjà présentes ne sont jamais remplacées.
7. Vérifiez plusieurs audios longs, leurs transcriptions et la liste complète de vos dictées. Activez ensuite, si vous le souhaitez, la synchronisation vers votre bibliothèque web privée.
8. **Seulement après contrôle**, choisissez Dictée universelle comme clavier Android actif et désinstallez les anciennes variantes de test pour ne conserver qu'un clavier.

## Limites importantes

La commande `run-as` d'ADB fonctionne pour les anciennes APK débogables. Si Android répond « package not debuggable », le script ne pourra pas lire les données privées. **Ne désinstallez pas l'ancienne application dans ce cas.** Il faudra chercher une autre possibilité d'export avant de poursuivre.

Une APK de production qui utilise l'identifiant d'une ancienne application mais un certificat différent sera refusée comme mise à jour par Android. Modifier le numéro de version ne contourne pas cette restriction.

Le ZIP contient des audios et transcriptions personnels. Conservez-le dans un emplacement privé, de préférence chiffré, et supprimez les copies temporaires après la vérification de la migration.

## Versions suivantes

Une fois la version signée `fr.denizot.dictee.inline` installée et utilisée, toutes les futures mises à jour devront conserver le même identifiant, le même certificat et augmenter `versionCode`. Elles pourront alors être installées par-dessus la version précédente sans créer de second clavier ni effacer le stockage interne.
