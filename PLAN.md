# Simple Converter – Projektplan

Stand: 05.10.2026 · Version im Repo: **v0.1.1 in Arbeit** (baut, 15 Unit-Tests + 4 Geräte-Tests grün, Lint sauber, getestet auf Galaxy S24 Ultra / Android 16)

**Aktueller Schwerpunkt:** v0.2 – Stapelverarbeitung und MP3/Opus/FLAC sind fertig. Offen: WebM-Video, H.265, HDR.

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

## 2. Funktionsumfang (Zielbild v1.0)

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
| L6 | HDR-Videos (HDR10/HLG) | evtl. blasse Farben oder Fehler | Media3 `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` setzen (v0.2) |
| L7 | Texte fest im Kotlin-Code | keine Übersetzung möglich | Nach `strings.xml` verschieben (v0.3) |
| L8 | Android 8/9: Ergebnis nur im App-Ordner | nicht in der Galerie sichtbar | „Speichern unter …“ per `CreateDocument` anbieten (v0.2) |
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
- [ ] WebM (VP9 + Opus) – Kandidat: MediaCodec-VP9 + MediaMuxer WEBM, ohne FFmpeg
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
- [ ] H.265-Option unter „Erweitert“ (nur anzeigen, wenn Encoder vorhanden)
- [ ] HDR → SDR Tone-Mapping (L6)
- [ ] „Speichern unter …“ als zusätzliche Option (L8)
- [ ] Option „EXIF behalten“ (Kopieren über `ExifInterface`, ohne GPS optional)

### v0.3 – Komfort (3 Wochen)

- [ ] **Kürzen mit Vorschau**: ExoPlayer-Vorschau + Bereichs-Schieberegler, Umsetzung über `MediaItem.ClippingConfiguration`
- [ ] **Lautstärke angleichen**: FFmpeg `loudnorm` (zweistufig, EBU R128, Ziel −14 LUFS / −16 LUFS)
- [ ] **Verlauf** auf Room umstellen, Suche/Filter im Verlauf, einzelne Einträge löschen
- [ ] **Einstellungen-Seite**: Standardformat je Medientyp, Speicherort, „EXIF behalten“, Theme (System/Hell/Dunkel)
- [ ] **Widget** (Jetpack Glance): „Datei wählen“ mit einem Tipp
- [ ] **Quick Settings Tile** (optional)
- [ ] Alle Texte nach `strings.xml`; **Englisch** als zweite Sprache (L7)
- [ ] Vorschaubilder (Thumbnails) in Setup und Verlauf
- [ ] Barrierefreiheit: TalkBack-Beschreibungen, Schriftgröße 200 %, Kontrast prüfen

### v1.0 – Veröffentlichung (2–3 Wochen)

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

**Gefunden und behoben**
- Vorgaben mit fester Bitrate machten sparsam kodierte Videos *größer* → Bitrate wird jetzt auf die des Originals begrenzt; zusätzlich Hinweis, wenn das Ergebnis trotzdem größer ist.
- Videos ohne Tonspur boten „Nur Ton“ an und scheiterten mit technischer Meldung → Tonspur wird per `MediaExtractor` erkannt (Samsung liefert `METADATA_KEY_HAS_AUDIO` nicht), Ton-Optionen werden ausgeblendet.
- „Fertig“-Benachrichtigung wurde sofort wieder entfernt → wird erst gelöscht, wenn die App sichtbar ist.

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
