# Simple Converter – Projektplan

Stand: 07.10.2026 · Neueste Version: **v0.7.1** (Unit-Tests + 6 Geräte-Tests grün, Lint sauber)

**Aktueller Schwerpunkt:** v0.7.1 (Software-Rückfall für Dekoder) veröffentlicht. Widget und Schnell-Kachel: später / vielleicht.

Repo: https://github.com/floraider2/Simple-Media-Converter · Branch: `main` (Versionen als Tags, siehe Abschnitt 11)

---

## 1. Vision

Ein Medienkonverter für Android, der **komplett offline** läuft:

- keine Werbung, keine Cloud, keine Anmeldung, **keine Internet-Berechtigung**
- Datei teilen oder in der App suchen → Zielformat wählen → fertig
- Vorgaben in Alltagssprache statt Fachbegriffen; Profis klappen „Erweitert“ auf
- Umfang: **Video, Audio, Bild** (GIF ist bewusst gestrichen)

### Zielgruppe

| Wer | Typischer Fall |
|---|---|
| Alltagsnutzer | „Video ist zu groß für WhatsApp/E-Mail“, „HEIC-Foto geht nicht auf dem PC auf“ |
| Datenschutzbewusste | „Standort aus Fotos entfernen, ohne Online-Dienst“ |
| Power-User | Bitrate, Auflösung, Codec selbst wählen; Stapelverarbeitung |

---

## 2. Funktionsumfang (Zielbild)

### 🎬 Video
- Eingabe: MP4, MKV, WebM, MOV, AVI, 3GP
- Ausgabe: MP4 (H.264/H.265), WebM (VP9 + Opus), MKV
- Auflösung ändern (Original / 1080p / 720p / 480p)
- Zielgröße („kleiner als 16 MB“) → Bitrate wird berechnet
- Kürzen mit Vorschau
- Ton entfernen
- Ton extrahieren (→ Audio)

### 🎵 Audio
- Eingabe: MP3, AAC, M4A, FLAC, OGG, Opus, WAV, AMR
- Ausgabe: MP3, M4A (AAC), FLAC, OGG (Vorbis/Opus), WAV
- Bitrate wählen
- Lautstärke angleichen (EBU R128)

### 🖼️ Bild
- Eingabe: JPG, PNG, WebP, HEIC/HEIF, AVIF, BMP
- Ausgabe: JPG, PNG, WebP, (optional AVIF ab Android 14)
- Skalieren, Qualität einstellen
- EXIF-Daten entfernen (Standard) oder bewusst behalten (Option)

### App-weit
- Teilen an die App (einzeln und mehrere Dateien)
- In der App suchen: Photo Picker (Galerie) + Systemdateiauswahl (auch Musik)
- Stapelverarbeitung (z. B. 50 HEIC → JPG)
- Hintergrundarbeit mit Fortschritt in der Benachrichtigung
- Verlauf („Zuletzt“)
- Material 3, dynamische Farben, Dark Mode
- Deutsch + Englisch

---

## 3. Ist-Stand v0.1

### Fertig

| Bereich | Umgesetzt |
|---|---|
| Projekt | Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12, minSdk 26, targetSdk 35 |
| Video → MP4 | Media3 Transformer, H.264/AAC, Hardware-Encoder, Auflösung, Bitrate, Zielgröße, Ton entfernen |
| Video/Audio → M4A | Media3 Transformer, AAC mit wählbarer Bitrate |
| Video/Audio → WAV | eigener Decoder (MediaExtractor + MediaCodec → 16-Bit-PCM) |
| Bild → JPG/PNG/WebP | ImageDecoder (ab API 28) bzw. BitmapFactory + EXIF-Drehung (API 26/27); EXIF wird entfernt |
| Teilen | `ACTION_SEND` und `ACTION_VIEW` für `video/*`, `audio/*`, `image/*` |
| Suchen in der App | Photo Picker („Aus der Galerie“) + `OpenDocument` („Datei suchen“) |
| Vorgaben | WhatsApp · E-Mail (< 25 MB) · Kleinste Datei · Max. Qualität (Video); Standard/Hoch/Klein (Audio); Standard/Messenger/Klein/Max (Bild) |
| Hintergrund | WorkManager + Foreground Service (`mediaProcessing` ab Android 15, sonst `dataSync`), Abbrechen-Knopf |
| Speichern | MediaStore → `Movies|Music|Pictures/SimpleConverter` (ab Android 10), App-Ordner unter Android 8/9 |
| Verlauf | letzte 20 Umwandlungen (SharedPreferences, JSON) |
| UI | 4 Bildschirme: Start → Einstellungen → Fortschritt → Ergebnis (+ Fehler) |
| Datenschutz | keine `INTERNET`-, keine `ACCESS_NETWORK_STATE`-Berechtigung (im APK geprüft) |

### Bekannte Lücken / Risiken aus v0.1

| # | Problem | Auswirkung | Lösung (Version) |
|---|---|---|---|
| L1 | ✅ Noch nie auf einem Gerät gelaufen | unbekannte Laufzeitfehler | Testlauf auf Emulator + echtem Gerät (v0.1.1) |
| L2 | ✅ URI-Rechte geteilter Dateien hängen an der Activity | Wird die App geschlossen, kann der Worker die Quelle evtl. nicht mehr lesen | Prüfen; ggf. Quelle vorher in den Cache kopieren oder Worker vor Activity-Ende Daten öffnen lassen (v0.1.1) |
| L3 | ✅ Keine „Fertig“-Benachrichtigung | Wer die App verlässt, merkt das Ende nicht | Abschluss-Benachrichtigung mit „Teilen/Öffnen“ (v0.1.1) |
| L4 | ✅ UI verliert laufenden Job nach Prozess-Tod | Fortschrittsbildschirm fehlt nach Neustart | Laufende Arbeit per WorkManager-Tag beim Start wieder aufnehmen (v0.1.1) |
| L5 | ✅ Hochkant-Videos + Skalierung ungetestet (geprüft: 1080×2340 → 720×1560, richtig gedreht) | evtl. Balken oder falsche Ausrichtung | Testvideo hochkant 1080×1920 prüfen (v0.1.1) |
| L6 | ✅ HDR-Videos (HDR10/HLG) | evtl. blasse Farben oder Fehler | Media3 `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` setzen (v0.2) |
| L7 | ✅ Texte fest im Kotlin-Code | keine Übersetzung möglich | Nach `strings.xml` verschieben (v0.3) |
| L8 | ✅ Android 8/9: Ergebnis nur im App-Ordner („Speichern unter …“) | nicht in der Galerie sichtbar | „Speichern unter …“ per `CreateDocument` anbieten (v0.2) |
| L9 | ✅ MP3/FLAC/Opus erledigt (ohne FFmpeg, siehe 5.1) · WebM/MKV als **Ausgabe** fehlen noch | Android hat dafür keine Encoder | FFmpeg (v0.2) |
| L10 | ~~Git-Commit fehlgeschlagen~~ | – | erledigt |

---

## 4. Architektur

```
┌──────────────────────────── UI (Compose, Material 3) ────────────────────────────┐
│  MainActivity ── ConverterApp ── Home / Setup / Working / Done / Failed          │
│        │                 │                                                       │
│   Intents (SEND,   ConverterViewModel ── Screen-Zustand (StateFlow)              │
│   SEND_MULTIPLE,         │                                                       │
│   VIEW)                  ▼                                                       │
├──────────────────────── WorkManager ─────────────────────────────────────────────┤
│  ConversionWorker (Foreground Service, Fortschritt, Abbrechen)                   │
│        │                                                                         │
│        ▼  Engine-Auswahl nach Zielformat                                         │
├──────────────────────── Engines ─────────────────────────────────────────────────┤
│  VideoConverter   Media3 Transformer   MP4, M4A           (Hardware, schnell)    │
│  WavConverter     MediaCodec           WAV                                       │
│  ImageConverter   ImageDecoder/Bitmap  JPG, PNG, WebP                            │
│  FfmpegConverter  FFmpeg (ab v0.2)     MP3, FLAC, OGG, Opus, WebM, MKV            │
├──────────────────────── Speicher ────────────────────────────────────────────────┤
│  OutputStore (MediaStore / FileProvider)  ·  RecentStore  ·  FileInspector       │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Regeln für die Engine-Auswahl

1. **Media3 zuerst**, wenn Ziel MP4/M4A ist – nutzt Hardware, schnell, akkuschonend.
2. **Bord-APIs** für Bilder und WAV.
3. **FFmpeg** nur, wenn Android das Zielformat nicht selbst kann **oder** Media3 die Eingabe nicht dekodieren kann (Fallback bei `ExportException` mit Decoder-Fehler).

### Paketstruktur

```
com.simpleconverter.app
├── MainActivity.kt
├── model/        Formate, Einstellungen, Vorgaben, Datenklassen
├── data/         FileInspector, RecentStore (später Room)
├── convert/      Video-, Wav-, Image-, (Ffmpeg-)Converter, OutputStore, EngineSelector
├── work/         ConversionWorker, BatchWorker, Notifications
└── ui/           ViewModel, Screens, Komponenten, Theme
```

---

## 5. Fahrplan

### v0.1.1 – Stabilisieren (1 Woche)

Ziel: v0.1 läuft zuverlässig auf echten Geräten.

- [x] Git-Identität setzen, ersten Commit anlegen (L10)
- [x] Echtes Gerät: Galaxy S24 Ultra, Android 16 (Emulator für Android 8–13 folgt)
- [ ] Testmatrix aus Abschnitt 7 einmal komplett durchlaufen (Stand: siehe 7.1)
- [x] Abschluss-Benachrichtigung mit „Öffnen“ / „Teilen“ (L3)
- [x] Laufende Umwandlung nach App-Neustart wiederfinden (L4)
- [x] URI-Rechte geteilter Dateien absichern (L2)
- [x] Hochkant-Video-Skalierung prüfen/korrigieren (L5)
- [x] Verständliche Fehlermeldungen statt technischer Exception-Texte
      (z. B. „Dieses Videoformat kann dein Gerät nicht lesen“)
- [x] Unit-Tests: Bitrate-Rechnung, `ConversionSettings` ↔ `Data`, Vorgaben, Dateinamen (13 Tests)
- [x] GitHub Actions: Build, Unit-Tests und Lint bei jedem Push, APK als Artefakt

### v0.2 – Stapel, mehr Formate (3–4 Wochen)

**Stapelverarbeitung**
- [x] `ACTION_SEND_MULTIPLE` im Manifest und ViewModel
- [x] `PickMultipleVisualMedia` (bis 100) und `OpenMultipleDocuments` in der App
- [x] Setup-Bildschirm für mehrere Dateien (gemeinsames Zielformat; nur Formate, die alle Eingaben können; einzelne Dateien entfernbar)
- [x] Ein Worker, der die Liste nacheinander abarbeitet (nicht parallel – Hardware-Encoder sind begrenzt); eine kaputte Datei stoppt den Rest nicht; Auftrag als JSON in `files/jobs/` (WorkManager-Data hat nur 10 KB)
- [x] Fortschritt „Datei 12 von 50 · 34 %“ in UI und Benachrichtigung
- [x] Ergebnis: Liste mit „Alle teilen“, Fehler je Datei sichtbar
- [x] Geteilte Dateien eines Stapels werden vorab in den Cache kopiert, wenn genug Platz frei ist

**Audio-Formate – umgesetzt ohne FFmpeg (Entscheidung 06.10.2026)**

Beim Nachprüfen zeigte sich: Android bringt Encoder für Opus (ab 10) und FLAC selbst mit, MediaMuxer schreibt OGG.
Nur MP3 fehlt – dafür reicht LAME (≈ 270 KB je ABI) statt eines kompletten FFmpeg (mehrere MB, Linux-Build nötig).

- [x] Gemeinsame Kette: `PcmDecoder` (MediaCodec) → optional `StereoDownmix`/`Resampler` (Media3 Sonic) → Ausgabe
- [x] MP3 über LAME 3.100: Quellcode im Repo (`app/src/main/cpp/lame`), Build per CMake im normalen Gradle-Lauf, JNI-Brücke `mp3_jni.c`, Info-Tag für exakte Länge
- [x] Opus in OGG über MediaCodec + MediaMuxer (Android 10+), Resampling auf 48 kHz wenn nötig
- [x] FLAC über MediaCodec, Datei selbst geschrieben, Gesamtlänge nachträglich in STREAMINFO eingetragen
- [x] Vorgaben: MP3 Standard/Hoch/Klein, Opus Standard/Hoch/Sprache, FLAC verlustfrei
- [x] 16-KB-Seiten für Android 15+ (`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES`)

**Noch offen (früher unter „FFmpeg-Engine“)**
- [x] WebM (VP9 + Opus) über Media3 Transformer mit eigenem `WebmMuxer` (MediaMuxer WEBM); Hochkant-Videos werden hochkant kodiert, weil WebM keine Drehung speichert
- [ ] MKV – nur falls wirklich gefragt

<details><summary>Ursprünglicher FFmpeg-Plan (zurückgestellt)</summary>

- [ ] Android NDK installieren (r27+)
- [ ] FFmpeg 7.x selbst bauen – minimal, nur was gebraucht wird:
  - Encoder: `libmp3lame`, `libopus`, `libvorbis`, `flac` (nativ), `libvpx` (VP9 für WebM)
  - Muxer: mp3, ogg, flac, webm, matroska, wav, ipod (m4a)
  - Decoder: gängige Audio-/Video-Decoder, Demuxer für avi/mkv/webm/ogg/flac
  - **kein** libx264/libx265 (GPL, groß) – H.264/H.265 macht Media3 per Hardware
- [ ] Lizenz: alles LGPL-kompatibel halten → FFmpeg als Shared Library, App-Lizenz frei wählbar (siehe Abschnitt 9)
- [ ] Builds für `arm64-v8a`, `armeabi-v7a`, `x86_64`; ABI-Splits, damit das APK klein bleibt
- [ ] Dünne JNI-Brücke (`FfmpegBridge`) mit Fortschritts-Callback (`-progress` auswerten) und Abbruch
- [ ] `FfmpegConverter` + `EngineSelector`
- [ ] Neue Ziele: MP3, FLAC, OGG (Vorbis), Opus, WebM (VP9/Opus), MKV
- [ ] Gradle-Modul `:ffmpeg`, damit der native Build getrennt bleibt
- [ ] Build-Skript ins Repo (`ffmpeg/build.sh`), reproduzierbar für F-Droid

</details>

**Weitere Punkte**
- [x] H.265-Option unter „Erweitert“ (nur anzeigen, wenn Encoder vorhanden)
- [x] HDR → SDR Tone-Mapping (L6) – immer, für beste Kompatibilität
- [x] „Speichern unter …“ als zusätzliche Option (L8), bei Stapeln „Alle in Ordner speichern …“
- [x] Option „Kameradaten behalten“ (Kopieren über `ExifInterface`, GPS wird nie kopiert)
- [x] Debug-Build mit eigenem Paketnamen (`.debug`), läuft neben der installierten Release-Version

### v0.3 – Sprachen & Einstellungen ✅ (Release v0.3.0)

- [x] Alle Texte nach `strings.xml`: **Englisch** als Grundsprache, **Deutsch** in `values-de` (L7); Sprachwahl pro App ab Android 13; Geräte-Tests sprachunabhängig
- [x] **Einstellungen-Seite**: Standardformat je Medientyp, „Kameradaten behalten“ als Standard, Design (System/Hell/Dunkel), Über-Bereich (Version, Lizenz, Quellcode, Fremdsoftware)
- [x] **Speicherort**: Standardordner je Dateityp (Video/Audio/Bild) in den Einstellungen; bei jeder Umwandlung sichtbar und für diese eine Umwandlung änderbar. Ordner per Systemauswahl mit dauerhaften Rechten; ist er nicht mehr erreichbar, landet die Datei im Standardordner

### v0.4 – Komfort ✅ (Release v0.4.0)

- [x] **Kürzen mit Vorschau** (einzelne Video-/Audiodatei): ExoPlayer-Vorschau, Bereichs-Schieberegler (vom System-Zurückwischen ausgenommen), „Anfang hier“/„Ende hier“ an der Abspielstelle. Video über `MediaItem.ClippingConfiguration`, Audio schneidet der `PcmDecoder` sample-genau zu; Zielgröße rechnet mit der gekürzten Länge
- [x] **Lautstärke angleichen** auf −14 LUFS (EBU R128 / BS.1770, eigener Messer ohne FFmpeg), Spitzen max. −1 dBFS; zwei Durchgänge (messen 0–40 %, umwandeln 40–100 %); für alle Audioformate und die Tonspur von Videos
- [x] **Verlauf**: eigene Seite mit Suche (Ziel- und Quellname, mehrere Wörter), Filter nach Dateityp, Menü je Eintrag (Teilen, aus Verlauf entfernen – die Datei bleibt); Start zeigt die letzten 5 + „Alle anzeigen“; bis 200 Einträge. **Kein Room**: bei so wenigen Einträgen reicht die bisherige JSON-Speicherung
- [x] Vorschaubilder in Setup und Verlauf (System-Thumbnails ab Android 10, Zwischenspeicher im RAM); in den Einstellungen abschaltbar (Standard: an)
- [x] **Barrierefreiheit**: Überschriften für TalkBack, Emojis/Vorschaubilder als Deko ausgeblendet, Vorgaben als Auswahlgruppe, Regler beschriftet, Auf-/Zuklappen mit Zustand, Dateiwechsel wird angesagt; Schriftgröße 200 % geprüft (Titel einzeilig mit „…“); automatische Prüfung auf unbeschriftete Bedienelemente; Debug-Testschalter `--ef debugFontScale 2.0`

### v0.5 – Geschwindigkeit ✅ (Release v0.5.0)

Gemessen auf Galaxy S24 Ultra (Android 16) mit `PerfTest` und nicht-debuggbarem Build (`-Pperf`), Ergebnisse in Abschnitt 7.

- [x] **Ohne Neu-Kodieren:** Vorgaben „Original behalten“ (MP4: kürzen / Ton entfernen) und „Original-Ton“ (M4A aus AAC-Tonspur), nur angeboten, wenn die Spuren in den Container passen; Rückfall auf normales Umwandeln bei Fehlern
- [x] **Audio schneller:** MediaCodec asynchron, ab Android 15 gebündelt („Large audio frame“) für AAC/MP3/Opus/Vorbis; WAV ohne Dekoder; Wächter gegen hängende Codecs
- [x] **Dekodieren und Kodieren parallel** (`PipelineSink`): vorher wechselten beide auf einem Thread ab und brauchten zusammen doppelt so lange
- [x] **Lautstärke in einem Dekodier-Durchgang:** beim Messen entsteht eine WAV-Zwischendatei, der zweite Durchgang liest nur noch diese
- [x] **Bild-Stapel parallel** (bis 4 gleichzeitig, je nach Kernen und Speicher)
- [x] **Weniger Speicher-Allokationen** (wiederverwendete Puffer, JNI ohne Kopien)
- [x] **Kleinere APK:** kein 32-Bit-x86 mehr
- [x] **FLAC:** eigener FLAC-Leser (`FlacSource`, prüft jede Frame-Grenze per CRC-8, Nummer und CRC-16), weil die Leser von Android und Media3 1.5 manche Dateien falsch zerlegen; FLAC-Dateien der App tragen jetzt Frame-Größen und MD5 im Kopf

### v0.6 – Alles aktualisiert ✅ (Release v0.6.0)

- [x] **Alles aktualisiert:** Gradle 9.8, Android Gradle Plugin 9.4 (Kotlin jetzt eingebaut), Kotlin 2.4, compileSdk/targetSdk 37 (Android 17), NDK 30, CMake 4.1, Media3 1.11, Compose-BOM 2026.09, AndroidX (Core, Activity, Lifecycle, WorkManager, ExifInterface, Test)
  - Media3: Muxer-API (Track-Nummern statt `TrackToken`), `EditedMediaItemSequence.Builder` mit Spurtypen, `MediaExtractorCompat` jetzt in `media3-inspector`; Material-Symbole als eigene Abhängigkeit
  - Lint der neuen Version: KTX-Funktionen, Backup-Regeln (`dataExtractionRules`: nichts in die Cloud, nichts aufs neue Gerät), Speicherplatz über `StorageManager.getAllocatableBytes`
- [x] **Baseline Profile** (Modul `baselineprofile`, Macrobenchmark): Kaltstart 260 → 221 ms (Median, S24 Ultra). Mess-Varianten heißen `com.simpleconverter.app.benchmark`, damit die installierte App unberührt bleibt
- [x] **M4A über den eigenen Audio-Weg** (`AacSink`, MediaCodec + MediaMuxer, parallel): Media3 1.11 brauchte 5,2 s für 1 min Ton, jetzt 0,6 s. Dabei behoben: Bis v0.5 wurde bei „Nur Ton: M4A“ die gewählte Bitrate ignoriert und nur kopiert
- [x] „Original behalten“/„Original-Ton“: Media3 1.11 kodiert trotz Transmux neu, wenn Encoder-Wünsche gesetzt sind → beim Kopieren keine setzen; reines M4A ohne Platzhalter für schnellen Start (sonst ~⅓ größer)
- [x] Verständliche Meldung für kaputte Dateien statt „Unerwarteter Fehler (IOException)“
- [x] Geräte-Tests räumen ihre Ergebnisse in Music/Pictures/Movies selbst wieder weg
- FLAC liest weiterhin der eigene Leser (`FlacSource`), unabhängig von Media3

### v0.7 – WMV/WMA ✅ (Release v0.7.0)

- [x] **WMV/WMA über FFmpeg** (9.0.2, LGPL, nur ASF-Leser + Decoder WMV1/2/3, VC-1, WMA v1/v2/Pro/Voice/Lossless), komplett in Software – auf jedem Gerät gleich. Auf ARM mit den NEON-Routinen von FFmpeg, x86_64 nur C
  - Quellen-Teilmenge im Repo (`app/src/main/cpp/ffmpeg`, 8 MB statt 105 MB), Gradle baut sie wie LAME; Werkzeuge zum Neu-Erzeugen liegen dabei
  - Video: `AsfAssetLoader` liefert Bilder per `ImageWriter` mit Zeitstempel an Media3 (YUV, sonst RGBA aus C), Ton als PCM; Audio-Ziele über `PcmDecoder`
  - Erkennen über die ASF-Kennung am Dateianfang; Dauer, Spuren und Größe kommen von FFmpeg. „Kürzen“ ohne Vorschau (der Player kann WMV nicht abspielen)
- [x] Allgemeiner Rückfall für Container, die Media3 nicht liest: Leser von Android + Decoder des Geräts (`FrameworkAssetLoader`)
- [x] Klare Meldung für Dateien, die sich nicht öffnen lassen
- [x] Selbst erzeugte Testdateien und Bild-Ton-Messung (`testmedia/`), automatischer Test `AsfConversionTest`
- [x] Test-Emulator: Android 16 (AOSP, x86_64) – prüft „Handy ohne WMV-Decoder“; Leistung weiter auf dem S24 Ultra

### v0.7.1 – Rückfall für Dekoder ✅ (Release v0.7.1)

- [x] **Video (Media3):** `setEnableDecoderFallback(true)` – scheitert der erste Dekoder (meist Hardware), probiert Media3 die übrigen bis zum Software-Dekoder von Android. Vorher brach die Umwandlung sofort ab
- [x] **Ton (eigener Weg):** `PcmDecoder` probiert alle Dekoder des Geräts für das Format der Reihe nach, solange noch nichts beim Ziel angekommen ist (vorher nur gebündelt → normal)
- [x] Test `DecoderFallbackTest`: ein nicht vorhandener Dekoder steht an erster Stelle; Video und Ton klappen trotzdem (im Log belegt)
- Encoder: Media3 fällt schon seit v0.1 auf andere Encoder/Einstellungen zurück (`setEnableFallback`); Ton-Encoder sind Android-Software oder eigene

### Später / vielleicht

- [ ] **Widget** (Jetpack Glance): „Datei wählen“ mit einem Tipp
- [ ] **Quick Settings Tile**

### Vielleicht: Veröffentlichung auf F-Droid / Play Store (noch nicht entschieden)

> Ob die App in F-Droid und den Play Store kommt, ist offen. Die Punkte unten sind eine Sammlung für den Fall, dass – kein fester Plan.


- [ ] App-Name final prüfen (Markenrecherche „Simple Converter“ – evtl. eindeutiger Name)
- [ ] Release-Signatur, `minifyEnabled` testen (R8-Regeln für JNI-Klassen)
- [ ] APK-Größe prüfen (Ziel: < 15 MB pro ABI)
- [ ] Datenschutzerklärung (kurz: „Die App sammelt und sendet keine Daten“)
- [ ] Open Source auf GitHub/Codeberg, Lizenz festlegen
- [ ] **F-Droid**: `fastlane/metadata/android/{de-DE,en-US}` (Beschreibung, Screenshots, Changelogs), reproduzierbarer Build inkl. FFmpeg aus Quellcode, Merge-Request bei fdroiddata
- [ ] **Play Store**: Store-Eintrag, Data-Safety-Formular („keine Daten erhoben/geteilt“), Foreground-Service-Typ `mediaProcessing` begründen, Screenshots Telefon + Tablet
- [ ] Interner Test → geschlossener Test (Play verlangt 12 Tester / 14 Tage für neue Privatkonten) → Produktion

### Später (Ideen, nicht eingeplant)

- Untertitel einbrennen / entfernen
- Mehrere Videos zusammenfügen
- Drehen/Spiegeln
- Audio-Schnitt
- Profile speichern („Mein WhatsApp-Profil“)

---

## 6. UI-Ablauf

```
 Start                      Einstellungen               Fortschritt            Ergebnis
┌──────────────────┐       ┌──────────────────┐       ┌────────────────┐     ┌──────────────────┐
│ Simple Converter │       │ urlaub.mov 84 MB │       │ 🎬             │     │ ✓                │
│ ┌──────────────┐ │       │ Zielformat       │       │ urlaub → MP4   │     │ urlaub.mp4       │
│ │🖼 Galerie    │ │──────▶│ [MP4][Nur Ton…]  │──────▶│ ████████░░ 72% │────▶│ 84 MB → 12 MB    │
│ │🔍 Datei such.│ │       │ Vorgabe          │       │                │     │ [Teilen][Öffnen] │
│ └──────────────┘ │       │ ● Für WhatsApp   │       │ [Abbrechen]    │     │ Noch eine Datei  │
│ Zuletzt:         │       │ ○ Für E-Mail     │       └────────────────┘     └──────────────────┘
│ · song.m4a   ✓   │       │ ▸ Erweitert      │
└──────────────────┘       │ [ Umwandeln ]    │
        ▲                  └──────────────────┘
        │  Teilen aus WhatsApp / Galerie / Dateimanager springt direkt zu „Einstellungen“
```

Ab v0.2 zusätzlich: Mehrfachauswahl → Einstellungen gelten für alle → Fortschritt „12 von 50“ → Ergebnisliste.

---

## 7. Testplan

### Automatisiert

| Ebene | Was | Werkzeug |
|---|---|---|
| Unit | Bitrate aus Zielgröße, Vorgaben, Settings-Serialisierung, Dateinamen | JUnit |
| Unit | ViewModel-Zustände (Setup → Working → Done/Failed/Cancelled) | JUnit + Turbine + WorkManager-Testing |
| Instrumentiert | ✅ `BatchConversionTest`: Teilen an die App (SEND_MULTIPLE) → Umwandeln → Ergebnis prüfen; Audio-Stapel, Bild-Stapel, kaputte Datei im Stapel, gemischte Typen abgelehnt. Testdateien erzeugt der Test selbst. Start: `./gradlew connectedDebugAndroidTest` | AndroidX Test + UiAutomator auf Gerät/Emulator |
| CI | `assembleDebug`, `lintDebug`, Unit-Tests bei jedem Push | GitHub Actions / Forgejo Actions |

### 7.1 Gerätetest 05.10.2026 – Galaxy S24 Ultra (SM-S928B), Android 16

Gesteuert per adb mit selbst erzeugten Testdateien (Ton-WAV, Bildschirmaufnahme, Screenshot).

| # | Test | Ergebnis |
|---|---|---|
| 1 | Video hochkant → MP4 „Für WhatsApp“ | ✅ 720×1560, richtig gedreht |
| 2 | Video → MP4 „Max. Qualität“ | ✅ |
| 3 | WAV → M4A Standard / Klein | ✅ 3,4 MB → 474 KB / 239 KB |
| 4 | WAV → WAV | ✅ |
| 5 | PNG → JPG, WebP „Kleinste Datei“, PNG verkleinert | ✅ |
| 6 | 10 min WAV (110 MB) → M4A im Hintergrund | ✅ ca. 80 s, Fortschritt in der Benachrichtigung |
| 7 | „Fertig“-Benachrichtigung, verschwindet beim Öffnen der App | ✅ (nach Fix) |
| 8 | Abbrechen in der App | ✅ „Umwandlung abgebrochen.“, zurück zu den Einstellungen |
| 9 | „Öffnen mit“ (ACTION_VIEW) | ✅ |
| 10 | Teilen aus Galerie/WhatsApp (ACTION_SEND) | ✅ manuell: PNG aus der Galerie geteilt → JPG, Cache-Kopie danach entfernt |
| 11 | Video **mit** Ton | ⏳ fehlt noch (Bildschirmaufnahmen haben keinen Ton) |

**Stapelverarbeitung (v0.2)** – `BatchConversionTest` 4/4 grün, Einzeldatei-Testreihe erneut bestanden.
Gefunden: Bei Bild-Stapeln lag „Umwandeln“ unter dem Bildschirmrand → Knopf steht jetzt fest unten.

**Audio-Formate (06.10.2026)** – alle Dateien gültig, Dauer vom System korrekt erkannt

| Test | Dauer | Ergebnis |
|---|---|---|
| 20 s WAV → MP3 192k / 320k | je ~2 s | 470 KB / 783 KB, Länge 20,04 s |
| 20 s WAV → Opus 128k / 32k | ~3 s | 342 KB / 106 KB |
| 20 s WAV → FLAC | ~2 s | 393 KB, Länge exakt 20,00 s |
| 10 min WAV (110 MB) → MP3 / Opus / FLAC | 13 s / 25 s / 11 s | 13,7 MB / 10,0 MB / 10,7 MB |

Gefunden: Opus/FLAC anfangs extrem langsam (10 min Audio > 5 min), weil die Encoder-Schleife beim Füttern jedes Mal 10 ms auf Ausgabe wartete → jetzt ohne Warten, 12–20× schneller.

**Video-Optionen & Bilder (06.10.2026)**

| Test | Ergebnis |
|---|---|
| Video hochkant → WebM Standard / Klein | ✅ VP9, hochkant 612×1326 / 480×1040 (VP9-Encoder weicht bei 720×1560 auf kleinere Größe aus) |
| Video → MP4 mit H.265 | ✅ `hvc1` im Container |
| Video → MP4 (neuer Composition-Weg mit HDR-Umrechnung) | ✅ unverändert |
| `ImageMetadataTest`: Standard entfernt Kamera + GPS; „Kameradaten behalten“ übernimmt Kamera/Zeit, nie GPS | ✅ 2/2 |
| „Speichern unter …“ | Knopf vorhanden; Dateiauswahl nicht automatisiert (zeigt private Ordner) |
| WebM **mit Ton** (Opus-Spur), HDR-Quelle | ⏳ kein passendes Testvideo |

**v0.3 (06.10.2026)** – Geräte-Tests 6/6, Unit-Tests, Lint ohne Befund

| Test | Ergebnis |
|---|---|
| App auf Englisch: Start, Einstellungen, Umwandeln, Ergebnis | ✅ keine deutschen Reste |
| Standardformat (Audio → WAV), „Kameradaten behalten“ als Standard | ✅ werden beim Öffnen übernommen |
| Design Hell / Dunkel | ✅ (Statusleiste im hellen Design zuerst unlesbar → behoben) |
| Speicherort für eine Umwandlung ändern (Download/SCTest) | ✅ Datei landet dort, nächste Umwandlung wieder Standard |
| Standardordner Audio in den Einstellungen | ✅ wird beim Öffnen angezeigt und genutzt; „Standard“ setzt zurück |
| „Öffnen“ einer Datei im eigenen Ordner | ✅ App-Auswahl erscheint |
| „Über die App“, Link zum Quellcode | ✅ |

**v0.4 Kürzen (06.10.2026)** – Unit-Tests 24, Geräte-Tests 6/6

| Test | Ergebnis |
|---|---|
| 10 min WAV → MP3, Bereich ca. 10–80 % | ✅ 6:59 |
| Video, Ende bei 50 % / Anfang bei 30 % (10 s) | ✅ 4,75 s / 6,74 s (Testvideo hat nur ~2 Bilder/s) |
| Griff vom Bildschirmrand ziehen | Fehler: löste die Zurück-Geste aus → Regler vom Gestenbereich ausgenommen ✅ |
| Vorschau springt beim Ziehen mit, „Zurücksetzen“ | ✅ |

**v0.4 Lautstärke (06.10.2026)** – Unit-Tests 34 (inkl. Referenzwerte aus BS.1770: 997-Hz-Sinus 0 dBFS = −3,01 LUFS mono / 0 LUFS stereo), Geräte-Tests 6/6

| Test (Messung am PC mit unabhängigem BS.1770-Skript) | Ergebnis |
|---|---|
| Leise Datei (−30 LUFS) → WAV angeglichen | ✅ −14,00 LUFS |
| Testton (−9,33 LUFS) → M4A angeglichen → zurück nach WAV | ✅ −14,02 LUFS (Media3-Weg, gilt auch für Video-Tonspur) |

**v0.4 Barrierefreiheit (06.10.2026)** – Unit-Tests 40, Geräte-Tests 6/6

| Prüfung | Ergebnis |
|---|---|
| Automatisch: unbeschriftete Bedienelemente, vorgelesene Emojis, zu kleine Ziele (alle Bildschirme) | Galerie-Emoji und Qualitätsregler behoben; Rest waren Elemente am Bildrand ✅ |
| Schriftgröße 200 % (Debug-Schalter, Systemeinstellung unverändert) | Titelzeile abgeschnitten → einzeilig mit „…“ ✅, sonst alles lesbar |
| TalkBack live | nicht eingeschaltet (Systemeinstellung des Testgeräts) – Struktur über Semantik geprüft |

**Gefunden und behoben**
- Vorgaben mit fester Bitrate machten sparsam kodierte Videos *größer* → Bitrate wird jetzt auf die des Originals begrenzt; zusätzlich Hinweis, wenn das Ergebnis trotzdem größer ist.
- Videos ohne Tonspur boten „Nur Ton“ an und scheiterten mit technischer Meldung → Tonspur wird per `MediaExtractor` erkannt (Samsung liefert `METADATA_KEY_HAS_AUDIO` nicht), Ton-Optionen werden ausgeblendet.
- „Fertig“-Benachrichtigung wurde sofort wieder entfernt → wird erst gelöscht, wenn die App sichtbar ist.

**v0.5 Geschwindigkeit (06.10.2026)** – Unit-Tests 42, Geräte-Tests 6/6, Lint ohne Befund

Galaxy S24 Ultra, `PerfTest` mit `-Pperf` (nicht debuggbar), beide Versionen gleich gemessen; jede Ausgabe wird wieder eingelesen und ihre Länge geprüft.

| Messung | v0.4 | v0.5 |
|---|---|---|
| 10 min WAV → MP3 | 23,4 s | 12,1 s |
| 10 min WAV → Opus | 58,8 s | 9,0 s |
| 10 min WAV → FLAC | 15,9 s | 3,8 s |
| 10 min FLAC → MP3 | 119,0 s | 12,0 s |
| FLAC 1:00–6:00 → MP3 | 83,2 s | 6,1 s |
| 10 min WAV → WAV | 9,0 s | 0,2 s |
| 10 min WAV → MP3 + Lautstärke | 36,3 s | 12,9 s |
| 10 min M4A → MP3 + Lautstärke | 135,0 s | 14,8 s |
| M4A 1:00–6:00 → MP3 + Lautstärke | 66,2 s | 7,6 s |
| 24 Bilder 12 MP PNG → JPG | 7,7 s | 4,2 s |
| Video 60 s → MP4 gekürzt 10–40 s | 13,3 s | 13,4 s (neu kodiert) / 2,0 s („Original behalten“) |
| Video 60 s → Nur Ton M4A | 1,0 s | 0,9 s |

| Test | Ergebnis |
|---|---|
| Vorgabe „Original behalten“ (294 MB Video) in der App | ✅ 4,9 s, „Erweitert“ ausgeblendet |
| Vorgabe „Original-Ton“ (M4A) in der App | ✅ steht oben, 2,2 s |

**Gefunden und behoben**
- FLAC-Dateien wurden beim Wiedereinlesen um 0,1–0,3 s zu kurz (schon in v0.4: 599,83 s statt 600 s). Ursache: Der FLAC-Encoder lässt Frame-Größen und MD5 im Dateikopf leer, und die Leser von Android und Media3 zerlegen solche (und manche andere) Dateien falsch → Kopf wird vervollständigt, eigener FLAC-Leser.
- Dekodieren und Kodieren auf einem Thread dauerten zusammen doppelt so lange wie einzeln → `PipelineSink`.

**v0.6 Alles aktualisiert (06.10.2026)** – Unit-Tests 42, Geräte-Tests 6/6, Lint ohne Befund

| Messung (S24 Ultra, `-Pperf`) | v0.5 | v0.6 |
|---|---|---|
| App-Kaltstart (Median aus 10) | 260 ms | 221 ms |
| Video 60 s → Nur Ton M4A (192 kbit/s) | 0,9 s (kopiert, Bitrate ignoriert) | 0,6 s (wirklich neu kodiert) |
| 10 min WAV → M4A | – | 5,3 s |
| Video 60 s → „Original-Ton“ | 0,9 s | 0,7 s |
| Video 60 s gekürzt, „Original behalten“ | 2,0 s | 2,1 s |
| 10 min M4A → MP3 + Lautstärke | 14,8 s | 14,8 s |
| 10 min WAV → Opus / FLAC / MP3 | 9,0 / 3,8 / 12,1 s | 9,3 / 3,7 / 12,3 s |

| Test (minifizierter Release-Build als `.releasetest`) | Ergebnis |
|---|---|
| WAV → MP3, M4A, FLAC; Video → MP4, WebM, „Original behalten“, „Original-Ton“, Nur Ton M4A; PNG → JPG | ✅ 9/9 |
| „Original-Ton“ Dateigröße | 1,38 MB (Platzhalter) → 994 KB nach Korrektur ✅ |
| Kaputte Datei im Stapel | ✅ neue Meldung, die anderen laufen weiter |

**v0.7 WMV/WMA (07.10.2026)** – Unit-Tests 42, Geräte-Tests 13/13 (Emulator), Lint ohne Befund

Bild-Ton-Messung mit `testmedia/sctest_sync.wmv` (Blitz + Piep jede Sekunde), Emulator ohne WMV-Decoder:

| Ergebnis | Länge | Ton minus Bild | Stereo |
|---|---|---|---|
| Original | 30,0 s | +12 ms | ✅ |
| → MP4 | 30,0 s | +22 ms, gleichbleibend | ✅ |
| → WebM | 30,0 s | +37 ms, gleichbleibend | ✅ |
| → MP3 / M4A, WMA → MP3 | 29,9–30,0 s | – | ✅ |

| Test | Ergebnis |
|---|---|
| Echte WMV3/WMA2-Datei, 92 min, 720p (S24 Ultra) | ✅ ca. 285 Bilder/s ≈ 9,5× Echtzeit; Engpass Media3 + Hardware-Encoder, nicht FFmpeg |
| `PerfTest.wmv`: 10 min WMV 720p → MP4 / → MP3 (S24 Ultra, `-Pperf`) | 57,7 s (≈ 10× Echtzeit) / 17,8 s, Länge 599,96 s ✅ |
| Release-Build (`.releasetest`) auf dem S24: WMV → MP4, WebM, MP3, M4A; WMA → MP3 | ✅ 5/5; Ton minus Bild MP4 +22 ms, WebM +17 ms, Stereo überall richtig |
| APK-Größe | 3,58 → 8,5 MB (alle drei ABIs); je Gerät ca. +1,6 MB (`libasf.so`) |
| Erste Version ohne NEON | Dekodieren auf einem Kern; mit NEON nur noch die Hälfte der Zeit nötig |
| Samsung-WMA-Decoder | in den Systemdateien vorhanden, aber für Apps nicht freigegeben → Grund für FFmpeg |

**v0.7.1 (07.10.2026)** – Unit-Tests 42, Geräte-Tests 15/15 (S24 Ultra), Lint ohne Befund; Audio-Messwerte unverändert (z. B. 10 min M4A → MP3 + Lautstärke 15,0 s)

### Manuelle Testmatrix

| Gerät | Android | Schwerpunkt |
|---|---|---|
| Emulator | 8.0 (API 26) | Legacy-Bilddekodierung, App-Ordner-Speicherung |
| Emulator | 10 (API 29) | MediaStore, Scoped Storage |
| Emulator | 13 (API 33) | Benachrichtigungs-Berechtigung, Photo Picker |
| Emulator/Gerät | 15 (API 35) | Foreground-Service-Typ `mediaProcessing`, Edge-to-Edge |
| Echtes Gerät | aktuell | Hardware-Encoder, Geschwindigkeit, Akku, HDR-Video |

**Testfälle je Durchlauf**
1. Video 1080p quer → MP4 „Für WhatsApp“ → 720p, abspielbar, Ton ok
2. Video 4K hochkant → MP4 „Für E-Mail“ → < 25 MB, richtige Ausrichtung
3. Video → „Nur Ton: M4A“ und → WAV
4. MP3 → M4A, FLAC → WAV
5. HEIC → JPG, PNG mit Transparenz → JPG (weißer Hintergrund), JPG → WebP
6. Foto mit GPS → JPG → keine EXIF-Daten mehr (mit `exiftool` prüfen)
7. Teilen aus Galerie, aus WhatsApp, aus Dateimanager
8. App während der Umwandlung verlassen → Benachrichtigung, Abbrechen funktioniert
9. Bildschirm drehen während Einstellungen und Fortschritt
10. Kaputte/abgeschnittene Datei → verständliche Fehlermeldung
11. Wenig Speicherplatz → sauberer Fehler, keine Reste in `cache/out`
12. Flugmodus: alles funktioniert gleich (Beweis für „offline“)

---

## 8. Technische Entscheidungen

| Thema | Entscheidung | Begründung |
|---|---|---|
| UI | Jetpack Compose + Material 3 | modern, wenig Code, dynamische Farben |
| Navigation | eigener `Screen`-Zustand im ViewModel | nur 5 Bildschirme, keine Navigation-Bibliothek nötig |
| Video-Engine | Media3 Transformer | Hardware-Beschleunigung, von Google gepflegt |
| Exotische Formate | FFmpeg, selbst gebaut | FFmpegKit wird nicht mehr gepflegt, Binärdateien wurden zurückgezogen |
| Hintergrund | WorkManager + Foreground Service | übersteht App-Wechsel, Abbrechen über Benachrichtigung |
| Dateizugriff | Photo Picker + Storage Access Framework | keine breite Speicherberechtigung nötig |
| Speicherort | MediaStore | Ergebnis erscheint sofort in Galerie/Musik-App |
| Verlauf | v0.1 SharedPreferences, ab v0.3 Room | erst bei Suche/Filter lohnt sich eine Datenbank |
| Mindestversion | Android 8 (API 26) | ~97 % der aktiven Geräte, Media3 läuft ab 21 |

---

## 9. Lizenz & Recht

- **FFmpeg** nur mit LGPL-Komponenten bauen (`--disable-gpl`, kein x264/x265) und als **Shared Library** linken.
  Pflichten: FFmpeg-Lizenztext + Hinweis + Quellcode-Angebot (F-Droid erfüllt das automatisch über den Quellcode-Build).
- **libmp3lame** (LGPL), **libopus**/**libvorbis**/**libvpx** (BSD) → passen dazu.
- **App-Lizenz** (Vorschlag): **GPL-3.0** – passt zu F-Droid und verhindert geschlossene Kopien. Alternative: Apache-2.0, wenn Weiterverwendung erwünscht ist.
- **Patente**: H.264/H.265/AAC über die Geräte-Encoder → Lizenz liegt beim Gerätehersteller. MP3-Patente sind abgelaufen.
- Name/Logo vor Veröffentlichung auf Markenkonflikte prüfen.

---

## 10. Offene Fragen

1. ~~Git-Identität~~ → `floraider2` mit der anonymen GitHub-Adresse (noreply)
2. ~~Remote~~ → GitHub `floraider2/Simple-Media-Converter`, öffentlich. Standard-Branch `main`, Versionen als Tags (`v0.1.0` …), größere Funktionen in kurzlebigen Arbeits-Branches mit Pull Request.
3. **Paketname**: `com.simpleconverter.app` ist ein Platzhalter – eigene Domain/Name gewünscht? (Muss vor dem ersten Store-Release feststehen, danach nicht mehr änderbar.)
4. ~~App-Lizenz~~ → **GPL-3.0** (Vorschlag aus Abschnitt 9, LICENSE liegt im Repo). (LAME ist LGPL und als eigene .so eingebunden – passt zu beiden.)
5. **Sprache**: Englisch ab v0.3 genug, oder weitere Sprachen?
6. **Testgerät**: Welches Android-Handy steht zum Testen zur Verfügung?

---

## 11. Nächste Schritte (konkret)

1. ~~Git-Identität setzen und ersten Commit machen~~
2. Android-Emulator-Image (API 35) installieren, AVD anlegen, App starten
3. Testmatrix-Punkte 1–9 durchgehen, Fehler beheben → **v0.1.1**
4. Stapelverarbeitung umsetzen (ohne FFmpeg möglich)
5. NDK installieren, FFmpeg-Minimalbuild aufsetzen → **v0.2**
