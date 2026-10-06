# Simple Converter

Offline-Medienkonverter für Android. Keine Werbung, keine Cloud, keine Anmeldung und **keine Internet-Berechtigung**.

## Was die App kann

| Eingabe | Ziel | Engine |
|---|---|---|
| 🎬 Video (MP4, MKV, WebM, MOV, 3GP …) | MP4 (H.264/AAC), Auflösung, Bitrate, Zielgröße, Ton entfernen | Media3 Transformer (Hardware) |
| 🎬 Video / 🎵 Audio | MP3 (32–320 kbit/s) | LAME 3.100 (nativ, im Repo) |
| 🎬 Video / 🎵 Audio | M4A (AAC) mit wählbarer Bitrate | Media3 Transformer |
| 🎬 Video / 🎵 Audio | Opus in OGG (ab Android 10) | MediaCodec + MediaMuxer |
| 🎬 Video / 🎵 Audio | FLAC (verlustfrei) | MediaCodec |
| 🎬 Video / 🎵 Audio | WAV (16-Bit-PCM) | MediaCodec |
| 🖼️ Bild (JPG, PNG, WebP, HEIC, AVIF*) | JPG, PNG, WebP, Skalieren, Qualität, EXIF wird entfernt | ImageDecoder / Bitmap.compress |

\* HEIC ab Android 9, AVIF ab Android 12, sofern das Gerät es dekodiert.

**Mehrere Dateien:** bis zu 100 auf einmal auswählen oder teilen, z. B. 50 HEIC-Fotos → JPG. Schlägt eine Datei fehl, laufen die anderen weiter.

**Dateien öffnen:** in der App über die Galerie (Photo Picker) oder „Datei suchen“ (Systemdateiauswahl, auch für Musik), oder von außen über **Teilen an** / **Öffnen mit**.

**Vorgaben:** Für WhatsApp · Für E-Mail (< 25 MB) · Kleinste Datei · Max. Qualität. Unter „Erweitert“ gibt es Auflösung, Zielgröße, Bitrate und Qualität.

Die Umwandlung läuft in WorkManager mit einem Foreground Service und zeigt den Fortschritt in der Benachrichtigung. Ergebnisse landen in `Movies/`, `Music/` oder `Pictures/SimpleConverter`. Unter Android 8 und 9 liegen sie im App-Ordner.

## Bauen

Benötigt JDK 17 und das Android SDK (Plattform 35) mit NDK 27.2 und CMake 3.22.1 (für LAME).

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Tests:

```bash
./gradlew testDebugUnitTest          # Unit-Tests
./gradlew connectedDebugAndroidTest  # Ende-zu-Ende auf angeschlossenem Gerät
```

## Lizenz

Simple Converter ist freie Software unter der **GNU General Public License v3.0**, siehe [LICENSE](LICENSE).
MP3 nutzt LAME (LGPL), siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Download

Fertige APKs gibt es unter [Releases](https://github.com/floraider2/Simple-Media-Converter/releases). Wie Releases entstehen: [RELEASING.md](RELEASING.md).

## Struktur

```
app/src/main/java/com/simpleconverter/app/
├── MainActivity.kt          Teilen-/Öffnen-Intents, Compose-Einstieg
├── model/Model.kt           Formate, Einstellungen, Vorgaben
├── data/                    Datei-Infos, Verlauf
├── convert/                 Video- und Bild-Konverter, Speichern
│   └── audio/               Dekoder → WAV/FLAC/Opus/MP3, Downmix, Resampling
├── work/ConversionWorker.kt Hintergrundarbeit + Benachrichtigung
└── ui/                      ViewModel, Bildschirme, Theme
app/src/main/cpp/            JNI-Brücke + LAME-Quellcode
```

## Fahrplan

- **v0.2:** ~~Stapelverarbeitung~~ ✅, ~~MP3/Opus/FLAC~~ ✅, WebM-Video offen
- **v0.3:** Kürzen mit Vorschau, Lautstärke angleichen, Widget
- **v1.0:** F-Droid und Play Store, Open Source
