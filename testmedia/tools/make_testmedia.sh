#!/bin/bash
# Erzeugt die Testdateien auf einem Android-Gerät oder Emulator mit dem Test-Konverter
# (siehe README.md: tools/configure_testconverter.sh). Aufruf:
#   ADB=adb SERIAL=<gerät> ./make_testmedia.sh
# Ergebnis in /data/local/tmp auf dem Gerät:
#   sctest_sync.wmv  30 s, 1280×720, Testbild, weißer Blitz + Piep auf jeder vollen Sekunde
#   sctest_sync.wma  derselbe Ton als WMA
#   sctest_long.wmv  10 min (für die Geschwindigkeitsmessung in PerfTest)
#   test.wmv / test.wma  8 s, 640×360 (für AsfConversionTest, liegen in app/src/androidTest/assets)
# Ton: links 1 kHz mit 4-kHz-Piep, rechts 500 Hz mit 2-kHz-Piep (zeigt vertauschte Kanäle).
set -e
ADB="${ADB:-adb}"
SERIAL="${SERIAL:?Gerät angeben, z. B. SERIAL=emulator-5554}"
export MSYS_NO_PATHCONV=1

"$ADB" -s "$SERIAL" shell 'cd /data/local/tmp && graph() {
  echo "testsrc2=size=$1:rate=30:duration=$2[t];color=white:size=$1:rate=30:duration=$2[w];[t][w]overlay=enable=lt(mod(t+0.001\,1)\,0.03),format=yuv420p[v];sine=frequency=1000:beep_factor=4:sample_rate=44100:duration=$2[l];sine=frequency=500:beep_factor=4:sample_rate=44100:duration=$2[r];[l][r]amerge=inputs=2[a]"
}
FF="./sc-ffmpeg -hide_banner -loglevel error -y"
$FF -filter_complex "$(graph 1280x720 30)" -map "[v]" -map "[a]" -c:v wmv2 -b:v 4M -c:a wmav2 -b:a 192k sctest_sync.wmv
$FF -i sctest_sync.wmv -vn -c:a wmav2 -b:a 192k -f asf sctest_sync.wma
$FF -filter_complex "$(graph 1280x720 600)" -map "[v]" -map "[a]" -c:v wmv2 -b:v 3M -c:a wmav2 -b:a 128k sctest_long.wmv
$FF -filter_complex "$(graph 640x360 8)" -map "[v]" -map "[a]" -c:v wmv2 -b:v 800k -c:a wmav2 -b:a 96k test.wmv
$FF -i test.wmv -vn -c:a wmav2 -b:a 96k -f asf test.wma
ls -la sctest_* test.wm*'
