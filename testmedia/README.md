# Testdateien

Selbst erzeugte Testdateien (frei von Rechten Dritter) für WMV/WMA und die Prüfung, ob Bild und Ton
synchron bleiben.

| Datei | Inhalt |
|---|---|
| `sctest_sync.wmv` | 30 s, 1280×720, 30 fps, WMV2 + WMA2 stereo. Testbild mit Uhr; auf jeder vollen Sekunde blitzt das Bild ein Bild lang weiß, gleichzeitig piept es. |
| `sctest_sync.wma` | Derselbe Ton als WMA |
| `../app/src/androidTest/assets/test.wmv`, `test.wma` | 8 s, 640×360 – für `AsfConversionTest` |

Ton: links 1 kHz mit 4-kHz-Piep, rechts 500 Hz mit 2-kHz-Piep – so fallen vertauschte Kanäle auf.

Die 10-Minuten-Datei für die Geschwindigkeitsmessung (`sctest_long.wmv`, 238 MB) ist zu groß fürs Repo;
`tools/make_testmedia.sh` erzeugt sie in etwa einer Minute neu.

## Bild-Ton-Versatz messen

```bash
ADB=adb python tools/syncmeasure.py <gerät> /data/local/tmp/<datei>          # Video
ADB=adb python tools/syncmeasure.py <gerät> /data/local/tmp/<datei> --audio-only
```

Dekodiert auf dem Gerät mit dem Test-Konverter, findet Blitze und Pieptöne und gibt den Versatz
(Ton minus Bild) in Millisekunden aus, dazu Länge und Stereo-Richtung. Das Original liegt bei etwa +12 ms
(Messraster: 5 ms Ton, 33 ms Bild).

## Test-Konverter

Ein eigenes FFmpeg-Programm **nur für Testdaten**, nicht Teil der App: WMV/WMA-Encoder, Testbild- und
Tonquellen, Dekoder für die Ergebnisse (H.264, H.265, VP9, AAC, Opus, FLAC, MP3). Gebaut mit dem
Android-NDK aus dem FFmpeg-Quellcode (wie in `app/src/main/cpp/ffmpeg/tools` beschrieben):

```bash
tools/configure_testconverter.sh <ffmpeg-quelle> <build-ordner>   # arm64; für den Emulator --arch=x86_64
make -C <build-ordner> ffmpeg
adb push <build-ordner>/ffmpeg /data/local/tmp/sc-ffmpeg && adb shell chmod 755 /data/local/tmp/sc-ffmpeg
```

Unter Windows braucht `make` zwei Ressourcen-Dateien (`fftools/resources/graph.{css,html}.c`), die sonst ein
Hilfsprogramm auf dem PC erzeugt; sie lassen sich als C-Array aus `fftools/resources/graph.css` bzw.
`graph.html` schreiben (`const unsigned char ff_graph_css_data[] = {…}` und `ff_graph_css_len`).
