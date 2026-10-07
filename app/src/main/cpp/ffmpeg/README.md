# FFmpeg (nur WMV/WMA)

FFmpeg 9.0.2, LGPL 2.1+. Nur, was die App für WMV/WMA (ASF-Container) braucht:

- Leser: `asf`
- Decoder: `wmv1`, `wmv2`, `wmv3`, `vc1`, `wmav1`, `wmav2`, `wmapro`, `wmavoice`, `wmalossless` (dazu intern `h263`)
- Kein GPL, kein „nonfree“, kein Netzwerk, keine weiteren Formate

| Ordner | Inhalt |
|---|---|
| `src/` | benötigte Quell- und Header-Dateien, unverändert aus `ffmpeg-9.0.2.tar.xz`, dazu Lizenzdateien |
| `config/<abi>/` | von `configure` erzeugte Konfiguration (`config.h`, Komponentenlisten) |
| `sources.cmake` | zu kompilierende Dateien je ABI – ARM mit den NEON-Routinen von FFmpeg, x86_64 nur C |
| `tools/` | Skripte, mit denen das alles erzeugt wurde |

Die App liest WMV/WMA damit vollständig in Software, auf jedem Gerät gleich; siehe
`../asf_jni.c` und `com.simpleconverter.app.convert.wmv`.

## Neu erzeugen (z. B. für eine neue FFmpeg-Version)

Unter Windows mit Git Bash und NDK 30 (Pfade in den Skripten ggf. anpassen):

```bash
# Quelle holen und Signatur prüfen (https://ffmpeg.org/releases/)
tar -xJf ffmpeg-9.0.2.tar.xz
for abi in arm64-v8a armeabi-v7a x86_64; do
  tools/configure_abi.sh $abi "$PWD/ffmpeg-9.0.2" "$PWD/build-$abi"
  make -C build-$abi -j16          # make.exe aus dem NDK
done
python tools/collect.py .                       # welche Dateien wurden gebraucht?
python tools/install_into_repo.py . <repo>/app/src/main/cpp/ffmpeg
```

`install_into_repo.py` entfernt dabei lokale Pfade aus der gespeicherten `configure`-Zeile.
