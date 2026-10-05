# Simple Converter

Offline-Medienkonverter für Android. Keine Werbung, keine Cloud, keine Anmeldung und **keine Internet-Berechtigung**.

## Was v0.1 kann

| Eingabe | Ziel | Engine |
|---|---|---|
| 🎬 Video (MP4, MKV, WebM, MOV, 3GP …) | MP4 (H.264/AAC), Auflösung, Bitrate, Zielgröße, Ton entfernen | Media3 Transformer (Hardware) |
| 🎬 Video / 🎵 Audio | M4A (AAC) mit wählbarer Bitrate | Media3 Transformer |
| 🎬 Video / 🎵 Audio | WAV (16-Bit-PCM) | MediaCodec |
| 🖼️ Bild (JPG, PNG, WebP, HEIC, AVIF*) | JPG, PNG, WebP, Skalieren, Qualität, EXIF wird entfernt | ImageDecoder / Bitmap.compress |

\* HEIC ab Android 9, AVIF ab Android 12, sofern das Gerät es dekodiert.

**Dateien öffnen:** in der App über die Galerie (Photo Picker) oder „Datei suchen“ (Systemdateiauswahl, auch für Musik), oder von außen über **Teilen an** / **Öffnen mit**.

**Vorgaben:** Für WhatsApp · Für E-Mail (< 25 MB) · Kleinste Datei · Max. Qualität. Unter „Erweitert“ gibt es Auflösung, Zielgröße, Bitrate und Qualität.

Die Umwandlung läuft in WorkManager mit einem Foreground Service und zeigt den Fortschritt in der Benachrichtigung. Ergebnisse landen in `Movies/`, `Music/` oder `Pictures/SimpleConverter`. Unter Android 8 und 9 liegen sie im App-Ordner.

## Bauen

Benötigt JDK 17 und das Android SDK (Plattform 35).

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Struktur

```
app/src/main/java/com/simpleconverter/app/
├── MainActivity.kt          Teilen-/Öffnen-Intents, Compose-Einstieg
├── model/Model.kt           Formate, Einstellungen, Vorgaben
├── data/                    Datei-Infos, Verlauf
├── convert/                 Video-, WAV- und Bild-Konverter, Speichern
├── work/ConversionWorker.kt Hintergrundarbeit + Benachrichtigung
└── ui/                      ViewModel, Bildschirme, Theme
```

## Fahrplan

- **v0.2:** Stapelverarbeitung (mehrere Dateien teilen), FFmpeg für MP3/FLAC/OGG/WebM-Ausgabe
- **v0.3:** Kürzen mit Vorschau, Lautstärke angleichen, Widget
- **v1.0:** F-Droid und Play Store, Open Source
