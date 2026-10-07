# Simple Converter

**[English](#english) · [Deutsch](#deutsch)**

<a id="english"></a>

I just needed a simple media converter app. That's it.

Everything I found on the Play Store either had ads, needed internet, processed my files in the cloud or, even worse, cost money.

So I vibe-coded one with Claude. **No ads, no cloud, no sign-up and no internet permission.** Your files never leave your phone.

That's all. If you have ideas, feel free to [open an issue](https://github.com/floraider2/Simple-Media-Converter/issues/new). Maybe I still have some Claude tokens left 😄

**[⬇️ Download the latest APK](https://github.com/floraider2/Simple-Media-Converter/releases/latest)**

## What it does

| Input | Output | Engine |
|---|---|---|
| 🎬 Video (MP4, MKV, WebM, MOV, 3GP, **WMV** …) | MP4 (H.264 or H.265 + AAC): resolution, bitrate, target size, remove audio; HDR is converted to SDR. “Keep original”: trim or remove audio without re-encoding | Media3 Transformer (hardware) |
| 🎬 Video | WebM (VP9 + Opus, Android 10+) | Media3 Transformer + MediaMuxer |
| 🎬 Video / 🎵 Audio | MP3 (32–320 kbit/s) | LAME 3.100 (native, in this repo) |
| 🎬 Video / 🎵 Audio | M4A (AAC); “Original audio” copies an AAC track without re-encoding | MediaCodec + MediaMuxer; copying: Media3 Transformer |
| 🎬 Video / 🎵 Audio | Opus in OGG (Android 10+) | MediaCodec + MediaMuxer |
| 🎬 Video / 🎵 Audio | FLAC (lossless) | MediaCodec, own FLAC reader |
| 🎬 Video / 🎵 Audio | WAV (16-bit PCM) | MediaCodec |
| 🖼️ Image (JPG, PNG, WebP, HEIC, AVIF*) | JPG, PNG, WebP: resize, quality; EXIF is removed, optionally camera details are kept (never the location) | ImageDecoder / Bitmap.compress |

\* HEIC from Android 9, AVIF from Android 12, if the device can decode it.

### How each format is handled

Hardware = the phone's own media chip. Android software = codecs that ship with every Android. Own = code in this repository, works the same on every phone.

**Reading and decoding**

| Input | Reading the file | Decoding | Fallback |
|---|---|---|---|
| Video: MP4, MKV, WebM, MOV, 3GP, AVI, TS | Media3 | Phone's decoder (usually hardware) | Android's own reader with the phone's decoders, if Media3 can't read the file |
| Video: **WMV, ASF** | Own (FFmpeg, ASF reader) | Own, software (FFmpeg: WMV1/2/3, VC-1; NEON-optimized on ARM) | – |
| Audio: MP3, AAC/M4A, Opus, Vorbis, AMR … | Media3 | Android software (batched on Android 15+) | Android's own reader; normal decoder if batching fails |
| Audio: FLAC | Own FLAC reader (checks every frame) | Android software | Media3 or Android's own reader |
| Audio: WAV (16-bit) | Own | – (already PCM) | – |
| Audio: **WMA** (v1, v2, Pro, Voice, Lossless) | Own (FFmpeg) | Own, software (FFmpeg) | – |
| Image: JPG, PNG, WebP, HEIC, AVIF | Android | Android (ImageDecoder) | BitmapFactory on Android 8 |

**Encoding and writing**

| Output | Encoding | Writing the file | Fallback |
|---|---|---|---|
| MP4 (H.264/H.265 + AAC) | Phone's encoder (usually hardware) | Media3 | another encoder of the phone; “Keep original” falls back to re-encoding |
| MP4 “Keep original”, M4A “Original audio” | – (copied, no re-encoding) | Media3 | re-encoding in best quality |
| WebM (VP9 + Opus) | Phone's encoder | Android (MediaMuxer) | – |
| MP3 | Own (LAME 3.100) | Own | – |
| M4A (AAC) | Android software | Android (MediaMuxer) | – |
| Opus (OGG) | Android software (batched on Android 15+) | Android (MediaMuxer) | normal encoder if batching fails |
| FLAC | Android software | Own (adds frame sizes and MD5 to the header) | – |
| WAV | – | Own | – |
| JPG, PNG, WebP | Android (Bitmap.compress) | Android | – |

- **Share to the app** from your gallery, WhatsApp or file manager, or pick files inside the app (gallery or file search, music too).
- **Presets instead of jargon:** For WhatsApp · For email (< 25 MB) · Smallest file · Max. quality. Codec, bitrate and resolution are under “Advanced”.
- **Many files at once:** up to 100, e.g. 50 HEIC photos → JPG. If one file fails, the others keep going.
- **Runs in the background** with progress in the notification.
- **Where files go:** `Movies/`, `Music/` or `Pictures/SimpleConverter` by default. In the settings you can pick a default folder per file type, and change the folder for a single conversion. Results can be shared, opened or saved anywhere with “Save as …”.
- **Trim** videos and audio with a preview, and **normalize loudness** to −14 LUFS (like streaming services).
- **History** with search, filter and thumbnails.
- **Settings:** default format per file type, save location, keep camera details, theme (system / light / dark), thumbnails on/off.
- **Accessible:** works with TalkBack and large font sizes.
- **Fast:** e.g. 10 minutes of AAC audio → MP3 with loudness normalization in about 15 seconds; trimming a video with “Keep original” takes about 2 seconds.
- **Languages:** English and German. On Android 13+ you can choose the language for this app only (*Settings → Apps → Simple Converter → Language*).

Requires Android 8 or newer.

## Building

Needs JDK 17 and the Android SDK (platform 37) with NDK 30 and CMake 4.1.2 (for LAME).

```bash
./gradlew assembleDebug
```

The APK in `app/build/outputs/apk/debug/` installs as **“Simple Converter Debug”** (`com.simpleconverter.app.debug`), next to an official version.

```bash
./gradlew testDebugUnitTest          # unit tests
./gradlew connectedDebugAndroidTest  # end-to-end tests on a connected device
./gradlew :app:generateReleaseBaselineProfile  # record the startup profile (device with Android 13+)
```

Profile and startup measurements install a separate `com.simpleconverter.app.benchmark`, so an installed version is never replaced.

How releases are built and signed: [RELEASING.md](RELEASING.md).

## Project structure

```
app/src/main/java/com/simpleconverter/app/
├── MainActivity.kt          share/open intents, Compose entry point
├── model/Model.kt           formats, settings, presets
├── data/                    file info, history, app settings
├── convert/                 video and image converters, saving
│   └── audio/               decoder → WAV/FLAC/Opus/MP3/M4A, own FLAC reader, downmix, resampling
├── work/ConversionWorker.kt background work + notifications
└── ui/                      ViewModel, screens, theme
app/src/main/cpp/            JNI bridges, LAME source, FFmpeg subset for WMV/WMA
testmedia/                   self-made WMV/WMA test files, sync measurement
baselineprofile/             records the Baseline Profile, measures app start
```

## Translations

Translations are welcome: copy `app/src/main/res/values/strings.xml` to `values-<language code>/strings.xml`, translate it and open a pull request.

## Roadmap

- **v0.1** ✅ Video, audio and image conversion, sharing, presets
- **v0.2** ✅ Batch conversion, MP3/Opus/FLAC, WebM, H.265, HDR → SDR, “Save as”, keep camera details
- **v0.3** ✅ English + German, settings, save location
- **v0.4** ✅ Trim with preview, loudness normalization (−14 LUFS), history with search, thumbnails, accessibility
- **v0.5** ✅ Speed: “Keep original” without re-encoding, much faster audio, parallel image batches, smaller APK
- **v0.6** ✅ Everything updated (Android 17 SDK, Media3, Compose, Kotlin, Gradle), faster app start (Baseline Profile), much faster M4A
- **v0.7** ✅ WMV and WMA on every phone (FFmpeg, software), clearer messages for unreadable files
- **Maybe:** F-Droid and Play Store – not decided yet

Details: [PLAN.md](PLAN.md) (German).

## License

Simple Converter is free software under the **GNU General Public License v3.0**, see [LICENSE](LICENSE).
MP3 encoding uses LAME (LGPL), WMV/WMA reading uses FFmpeg (LGPL), see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

<a id="deutsch"></a>

## 🇩🇪 Deutsch

Ich brauchte nur eine simple Medien-Converter-App. Das war's.

Alles, was man im Play Store findet, hat Werbung, braucht Internet, verarbeitet die Dateien in der Cloud oder kostet, noch blöder, Geld.

Also habe ich sie mit Claude gevibecodet. **Keine Werbung, keine Cloud, keine Anmeldung und keine Internet-Berechtigung.** Deine Dateien verlassen nie dein Handy.

Das war's. Wenn ihr noch Ideen habt, [schreibt gern ein Issue](https://github.com/floraider2/Simple-Media-Converter/issues/new). Vielleicht hab ich ja noch Claude-Tokens frei XD

**[⬇️ Neueste APK herunterladen](https://github.com/floraider2/Simple-Media-Converter/releases/latest)**

### Was die App kann

- 🎬 **Video** (auch WMV) → MP4 (H.264/H.265) oder WebM: Auflösung ändern, Zielgröße wie „unter 25 MB“, Ton entfernen
- 🎵 **Audio** (auch WMA und der Ton aus einem Video) → MP3, M4A, Opus, FLAC oder WAV
- **WMV/WMA auf jedem Handy:** liest die App selbst (FFmpeg, in Software) – unabhängig davon, ob der Hersteller passende Decoder mitbringt. Welches Format wie gelesen und geschrieben wird (Hardware, Android, eigene Software, Rückfallwege), steht in den Tabellen im englischen Teil oben.
- 🖼️ **Bild** (auch HEIC) → JPG, PNG oder WebP: verkleinern, Qualität wählen; Standort und EXIF-Daten werden entfernt
- **Teilen an die App** aus Galerie, WhatsApp oder Dateimanager, oder Dateien in der App suchen
- **Vorgaben statt Fachbegriffe:** Für WhatsApp · Für E-Mail · Kleinste Datei · Max. Qualität
- **Viele Dateien auf einmal**, z. B. 50 HEIC-Fotos → JPG
- Läuft **im Hintergrund** mit Fortschritt in der Benachrichtigung
- **Kürzen** mit Vorschau und **Lautstärke angleichen** (−14 LUFS)
- **Schnell:** „Original behalten“ kürzt Videos oder entfernt den Ton ohne Neu-Kodieren in Sekunden; Audio wird ein Vielfaches schneller umgewandelt als in früheren Versionen
- **Verlauf** mit Suche und Vorschaubildern
- **Einstellungen:** Standardformat und Speicherort je Dateityp, Design (System/Hell/Dunkel)
- **Barrierefrei:** mit TalkBack und großer Schrift nutzbar
- **Sprachen:** Deutsch und Englisch

Ab Android 8.

Technische Details (Formate, Bauen, Projektstruktur) stehen oben im englischen Teil, der ausführliche Fahrplan in [PLAN.md](PLAN.md).

### Lizenz

Freie Software unter der **GNU General Public License v3.0**, siehe [LICENSE](LICENSE). MP3 nutzt LAME (LGPL), WMV/WMA liest FFmpeg (LGPL), siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
