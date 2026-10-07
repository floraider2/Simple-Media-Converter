# Verwendete Fremdsoftware

## LAME 3.100 – MP3-Encoder

- Quelle: https://lame.sourceforge.io/ (`lame-3.100.tar.gz`, SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`)
- Lizenz: GNU Lesser General Public License 2 oder neuer (LGPL), siehe `app/src/main/cpp/lame/COPYING`
- Im Repo: nur der Encoder (`libmp3lame`) und `include/lame.h`, unverändert. Neu ist nur `config.h`, das hier `./configure` ersetzt.
- Einbindung: LAME wird als eigene Shared Library (`libmp3.so`) gebaut und zur Laufzeit geladen. So lässt sie sich durch eine eigene Version ersetzen, wie es die LGPL verlangt.

## FFmpeg 9.0.2 – WMV/WMA lesen

- Quelle: https://ffmpeg.org/releases/ (`ffmpeg-9.0.2.tar.xz`, SHA-256 `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e`, Signatur mit dem FFmpeg-Release-Schlüssel `FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8` geprüft)
- Lizenz: GNU Lesser General Public License 2.1 oder neuer (LGPL), siehe `app/src/main/cpp/ffmpeg/src/COPYING.LGPLv2.1` und `LICENSE.md`. Ohne GPL- und „nonfree“-Teile gebaut.
- Im Repo: nur die Dateien, die für den ASF-Leser und die Decoder WMV1/2/3, VC-1 und WMA (v1, v2, Pro, Voice, Lossless) gebraucht werden, unverändert; dazu die von `configure` erzeugte Konfiguration je ABI. Wie sie erzeugt wurden, steht in `app/src/main/cpp/ffmpeg/README.md`.
- Einbindung: als eigene Shared Library (`libasf.so`), zur Laufzeit geladen – austauschbar, wie es die LGPL verlangt.
- Patente: WMV/VC-1 und WMA können in manchen Ländern noch Patenten unterliegen.

## AndroidX / Jetpack (Media3, Compose, WorkManager, …)

- Lizenz: Apache License 2.0
- Werden über Gradle aus dem Google-Maven-Repository bezogen.
