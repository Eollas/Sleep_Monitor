# Sleep Monitor — mémoire du projet

Le propriétaire communique en **français** : répondre en français, simplement
(il n'est pas développeur à plein temps), commentaires de code en français.
**Proposer avant de coder**, attendre son feu vert. Il aime les captures d'écran.

## L'application

App Android (Kotlin, Jetpack Compose, Room, DataStore) pour Redmagic 11 Pro,
Android 16 (minSdk 34, targetSdk 36). Paquet `com.eollas.sleepmonitor`.

- **Aucune donnée ne quitte le téléphone** : la permission INTERNET sert
  uniquement à la mise à jour (`update/Updater` : lecture de l'API GitHub
  releases + téléchargement de l'APK, installation via PackageInstaller).
  Accord du propriétaire le 10 nov. 2026 (« tant que ça ne diffuse pas de
  données »). Sauvegarde cloud désactivée. Aucun autre usage réseau sans son accord.
- Service de premier plan `tracking/SleepTrackingService` (types micro + santé),
  tranches de 30 s : mouvement (accéléromètre, milli-g) + niveau sonore.
- `analysis/SleepAnalyzer` : actigraphie (seuil adapté au bruit du capteur).
  Endormissement = 15 min sans mouvement, écran éteint, sans paroles/bruits,
  compté après 5 min de calme et ≥ 5 min après le coucher (ronflement régulier
  = sommeil). Réveil = fin des 10 dernières min calmes. Profond ≤ 25 %.
  Heures Mi Fitness (si dispo) encadrent l'estimation. `VERSION` : à augmenter
  quand l'analyse change → les anciennes nuits sont recalculées au démarrage.
  Tests : `SleepAnalyzerTest`.
- Sons : `AudioMonitor` (16 kHz) + `SoundClassifier` (YAMNet, `assets/yamnet.tflite`,
  LiteRT) → ronflement / paroles / toux / bruit perturbant / pause respiratoire
  (`SoundRules` ; bruits = liste blanche de sons précis, catégories vagues
  « Animal », moteur… ignorées ; noms français dans `Labels`, noms anglais
  des 521 catégories dans `assets/yamnet_labels.txt`).
  Porte : niveau moyen de la dernière seconde vs bruit de fond (15e centile
  sur 60 s). Analyse toutes les 0,5 s ; un son n'est retenu que s'il est
  confirmé dans une fenêtre de 6 s (12 analyses : ronflement/paroles 3, toux/
  bruit 2) et plus probable que le fond (`SoundRules.BACKGROUND` : silence,
  ventilateur, souffle…). Seuils = curseur « Sensibilité aux bruits » 0..1
  (`SoundRules.Tuning`, défaut 0,5) ; l'ancien réglage 3 niveaux
  (`sensitivity`) ne sert plus qu'aux mouvements (`SleepAnalyzer`).
  Chaque son garde ses 3 catégories YAMNet les plus probables (`top3`) et
  l'écart au fond (`aboveDb`). Tests : `SoundRulesTest`.
  Pause respiratoire possible = ≥ 3 ronflements dans la minute, puis 10-60 s
  sans souffle, puis reprise brusque (halètement / reniflement / ronflement fort).
  « Souffle » = respiration/ronflement reconnu OU niveau ≥ fond + 5 dB
  (`BREATH_DB`) : la respiration calme n'est pas toujours nommée par YAMNet.
  Extraits AAC `.m4a` provisoires dans `files/clips/{nuit}/` ; le matin
  `analysis/Highlights` garde 10-20 « moments de la nuit » (pauses, somniloquie
  pendant le sommeil, ronflements longs, bruits forts), le reste est effacé.
  Conservation réglable (3 j → toujours).
- Export : Réglages → « Exporter mes données » (`data/Exporter`) : ZIP dans
  `cache/export/` (CSV « ; » virgule décimale : nuits, tranches 30 s, sons +
  extraits encore gardés), partagé via le menu Partager (FileProvider) : c'est
  l'utilisateur qui choisit la destination. Sert à régler les seuils.
- Arrêt automatique : téléphone pris en main 3 min après ≥ 3 h de sommeil.
- Mi Fitness : via **Health Connect** (`health/HealthConnectManager`) : lecture
  sommeil + rythme cardiaque, écriture de nos nuits (option).
- Interface : effet vitre (`ui/components/Glass.kt` redessine le fond flouté
  derrière chaque carte), fonds dessinés (`Wallpapers`) ou photo perso.

## Compiler / tester

- SDK Android dans `/opt/android-sdk` (`local.properties`), environnement cloud
  « Sleep_Monitor » (accès à dl.google.com).
- Maven Central renvoie souvent 429 : miroir Google ajouté dans `settings.gradle.kts`.
- Lint de release local en 429 : `./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease`.
- Captures sans émulateur (pas de KVM) : `ScreenshotTest` (Robolectric + Roborazzi)
  → `screenshots/`.
- Versions : AGP 8.13, Kotlin 2.4, Compose BOM 2026.06.01 (les BOM plus récents
  exigent AGP 9 / SDK 37).
- GitHub Actions compile l'APK à chaque push ; sur `main`, publié en Release « latest »
  (tag recréé sur le commit compilé, `--target`). Numéro de version = numéro de
  compilation GitHub (1.0.N) ; le bouton « Mettre à jour » compare ce N.
- Base Room v3 (migrations 1→2→3 testées par `MigrationTest`, lancé par la CI ;
  `schemas/…/3.json` est régénéré par une compilation locale, à committer) : ne jamais casser
  les données existantes, toujours écrire une migration.
- Clé de signature fixe `keystore/sleepmonitor.jks` : ne pas la changer, sinon
  les mises à jour ne s'installent plus par-dessus.

## Préférences du propriétaire

- Réponses courtes, aperçus (captures) appréciés.
- Pas de PR obligatoire ici : il peut demander de pousser directement sur `main`.
  Le travail se fait directement sur `main` (une PR n'a de sens que pour une
  branche qui contient des commits absents de `main`).
- Ancienne branche `claude/nifty-curie-tk9hmj` : entièrement contenue dans
  `main` (vérifié le 10 nov. 2026). Sa suppression depuis la session (`git push
  --delete`) a été refusée (erreur 403, cause non confirmée) : le propriétaire
  la supprime lui-même sur GitHub.
- **Ne jamais inventer** (menus, réglages, causes d'erreur) : si on ne sait pas,
  le dire et vérifier avant d'affirmer.
- Rien ne doit quitter le téléphone sans son accord (pas de réseau, pas de cloud).

## Installer / mettre à jour

APK publié à chaque push sur `main` :
https://github.com/Eollas/Sleep_Monitor/releases/download/latest/SleepMonitor.apk
(même lien à chaque version, installation par-dessus, données conservées).

## Historique

- 9-10 nov. 2026 : création de l'app de zéro (suivi par mouvements, sons classés
  avec YAMNet, récapitulatif, Health Connect / Mi Fitness, interface vitre
  inspirée d'une capture de « Sleep Island » envoyée par le propriétaire).
  Pas encore testée sur le vrai téléphone au moment de la première version.
- 10 nov. 2026 (après la 1re nuit réelle) : endormissement trop tôt → règles
  sons + écran ; trop de faux « Animal » → liste blanche ; 350 extraits → 10-20
  moments forts ; détection des pauses respiratoires ; profond plafonné ;
  bouton « Mettre à jour » intégré (Internet autorisé pour ça seulement) ;
  voile sous la barre d'état. Mise à jour 1.0.4 → 1.0.5 depuis l'app : validée
  sur le téléphone par le propriétaire.

- 12 nov. 2026 : trop de fausses détections → porte sur niveau moyen + fond
  par centile, fenêtre de confirmation 6 s, comparaison au fond, curseur de
  sensibilité, top 3 YAMNet par son, export ZIP de toutes les nuits.

- 12 nov. 2026, analyse du 1er export (3 nuits enregistrées avant 1.0.9, curseur
  à 75 % car ancien réglage « Élevée ») : YAMNet rejoué sur les 61 extraits →
  les « paroles », « toux » et « musique » étaient du ronflement, de la
  respiration ou du silence (toutes rejetées par les nouvelles règles) ; les
  ronflements sont réels ; 6 « pauses » sur 16 avaient une respiration régulière
  (bouffées +8-12 dB toutes les 3 s) → règle `BREATH_DB`.

## Reste à faire / pistes

- Analyser l'export de quelques nuits pour régler les seuils ; envisager de
  sortir Soupir / Gémissement / Grognement / Fredonnement des « paroles » et
  d'essayer la source micro `UNPROCESSED` / `VOICE_RECOGNITION` (gain auto).

- Retours après les premières nuits réelles : ajuster seuils de mouvement
  (`SleepAnalyzer`) et de sons (`SoundRules`, sensibilité).
- **Play Store (reporté, « on verra plus tard »)** : compte développeur (~25 $),
  test fermé 12 testeurs × 14 jours, déclarations micro / service de premier
  plan / Health Connect, politique de confidentialité publique (idée :
  photo.eollas.com), format `.aab`, **nouvelle clé d'envoi secrète** (l'actuelle
  est publique dans le dépôt → la version Play ne s'installera pas par-dessus,
  données perdues), nom plus distinctif (ex. « Eollas Sleep »), fiche Play
  (textes, icône 512 px, bannière, captures).
