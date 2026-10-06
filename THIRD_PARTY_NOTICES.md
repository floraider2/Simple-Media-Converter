# Verwendete Fremdsoftware

## LAME 3.100 – MP3-Encoder

- Quelle: https://lame.sourceforge.io/ (`lame-3.100.tar.gz`, SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`)
- Lizenz: GNU Lesser General Public License 2 oder neuer (LGPL), siehe `app/src/main/cpp/lame/COPYING`
- Im Repo: nur der Encoder (`libmp3lame`) und `include/lame.h`, unverändert. Neu ist nur `config.h`, das hier `./configure` ersetzt.
- Einbindung: LAME wird als eigene Shared Library (`libmp3.so`) gebaut und zur Laufzeit geladen. So lässt sie sich durch eine eigene Version ersetzen, wie es die LGPL verlangt.

## AndroidX / Jetpack (Media3, Compose, WorkManager, …)

- Lizenz: Apache License 2.0
- Werden über Gradle aus dem Google-Maven-Repository bezogen.
