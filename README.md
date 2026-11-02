# Print Pro HP — application Android

Impression Wi-Fi directe depuis Android vers les imprimantes HP (et autres imprimantes IPP / AirPrint / Mopria), sans PC ni pilote.

## Fonctions

| Fonction | Réalisation |
|---|---|
| 📄 Documents | PDF, images (JPG, PNG, WebP, HEIC…), Word .docx (conversion intégrée), plusieurs fichiers fusionnés |
| 🖨️ Imprimantes | Détection automatique sur le Wi-Fi (mDNS `_ipp._tcp`), filtre « HP uniquement », ajout par adresse IP |
| 📑 Pages par feuille | 1, 2, 4, 6, 9, 16 avec rotation automatique des pages |
| 🔁 Répétition | Mode « Répéter la même page » : remplit la feuille avec la même page |
| 📚 Copies | 1 à 99, assemblées (gérées par l'imprimante ou par l'application) |
| 🔄 Recto verso | Automatique (bord long / bord court) si l'imprimante le propose, sinon manuel guidé |
| 📐 Formats | A4, A3, A5, Letter, Legal, Photo 10×15 (filtrés selon l'imprimante) |
| ↔️ Orientation | Portrait / paysage |
| 📏 Marges | Normales, réduites, aucune, personnalisées (0–30 mm) |
| 🔍 Échelle | Ajuster à la page, taille réelle, pourcentage personnalisé |
| 🎨 Couleur | Couleur / noir et blanc (selon l'imprimante) |
| 🖼️ Qualité | Brouillon / normale / haute (selon ce que l'imprimante annonce) |
| 👁️ Aperçu | Rendu réel de chaque face avant impression |
| 📃 Pages | Plages du type `1-5, 8, 10-12`, `10-` (jusqu'à la fin), `5-1` (ordre inverse) |

L'application prépare elle-même un PDF mis en page (vectoriel, le texte reste net) puis l'envoie à l'imprimante en IPP. Le bouton **Via Android** envoie ce même PDF au service d'impression du téléphone (HP Print Service Plugin, Mopria) en solution de secours.

## Obtenir l'APK

### Option A — Sans PC, avec GitHub (gratuit)
1. Créez un dépôt sur github.com et envoyez-y tout le contenu de ce dossier (le fichier `.github/workflows/build-apk.yml` inclus).
2. Onglet **Actions** → « Construire l'APK » → la compilation démarre automatiquement (≈ 5 min).
3. Téléchargez l'artefact **PrintProHP-apk**, décompressez-le et installez `app-debug.apk` sur le téléphone (autorisez « sources inconnues »).

### Option B — Android Studio
1. Ouvrez ce dossier dans Android Studio (Koala ou plus récent).
2. Laissez la synchronisation Gradle se faire, puis **Build → Build APK(s)**.
3. L'APK se trouve dans `app/build/outputs/apk/debug/`.

Configuration : minSdk 26 (Android 8.0+), targetSdk 34, Kotlin + Jetpack Compose, PdfBox-Android.

## Limites connues
- **Word** : la conversion intégrée garde le texte, gras/italique/souligné, titres, listes, sauts de page et tableaux simplifiés. Les images, colonnes, en-têtes/pieds de page et mises en forme avancées ne sont pas reproduits. Pour un rendu fidèle, exportez le document en PDF depuis Word ou Google Docs (« Partager » → Print Pro HP). Le format `.doc` ancien n'est pas pris en charge.
- **Recto verso manuel** : le sens de réinsertion dépend du modèle ; l'option « Versos en ordre inverse » permet de s'adapter.
- Les imprimantes qui n'acceptent pas le PDF en direct (rare sur les HP Wi-Fi récentes) passent par le bouton **Via Android**.
- Les connexions chiffrées seules (IPPS obligatoire) passent aussi par **Via Android**.

## Structure
```
app/src/main/java/com/printpro/app/
├── MainActivity.kt          Écran principal, réception « Partager / Ouvrir avec »
├── model/PrintSettings.kt   Tous les réglages d'impression
├── net/PrinterDiscovery.kt  Détection Wi-Fi des imprimantes
├── net/IppClient.kt         Protocole IPP : capacités, envoi, suivi des travaux
├── pdf/DocumentConverter.kt PDF / images / Word → PDF
├── pdf/Imposer.kt           Mise en page N-up, répétition, marges, échelle, copies, recto verso
├── pdf/PageRangeParser.kt   Analyse « 1-5, 8, 10-12 »
├── pdf/PreviewRenderer.kt   Aperçu
├── print/SystemPrint.kt     Secours via le service d'impression Android
└── ui/                      Interface Compose et logique (ViewModel)
```
