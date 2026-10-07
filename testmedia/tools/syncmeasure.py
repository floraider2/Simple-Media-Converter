"""Misst Bild-Ton-Versatz einer Testdatei (Blitz + Piep auf jeder vollen Sekunde).

Aufruf: python syncmeasure.py <serial> <datei auf dem Gerät> [--audio-only]
Dekodiert auf dem Gerät mit /data/local/tmp/sc-ffmpeg, wertet auf dem PC aus.
"""
import math, os, struct, subprocess, sys, wave

ADB = os.environ.get("ADB", "adb")
OUT = os.path.dirname(os.path.abspath(__file__))
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
FPS = 30


def adb(serial, *args):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, env=ENV)


def goertzel_power(samples, freq, rate):
    k = 2 * math.cos(2 * math.pi * freq / rate)
    s1 = s2 = 0.0
    for x in samples:
        s0 = x + k * s1 - s2
        s2, s1 = s1, s0
    return s1 * s1 + s2 * s2 - k * s1 * s2


def beep_onsets(channel, rate, freq):
    """Anfänge der Pieptöne (Sekunden) über die Energie bei der Piep-Frequenz in 5-ms-Fenstern."""
    win = int(rate * 0.005)
    powers = [goertzel_power(channel[i:i + win], freq, rate) for i in range(0, len(channel) - win, win)]
    if not powers:
        return []
    threshold = max(powers) * 0.25
    onsets, inside = [], False
    for i, p in enumerate(powers):
        if p > threshold and not inside:
            # genauer: erstes Sample im Fenster, ab dem die Amplitude die Hälfte übersteigt, ist zu teuer –
            # Fensteranfang reicht (5 ms Raster)
            onsets.append(i * win / rate)
        inside = p > threshold
    return onsets


def main():
    serial, path = sys.argv[1], sys.argv[2]
    audio_only = "--audio-only" in sys.argv
    tag = os.path.basename(path).replace(".", "_")
    remote_v, remote_a = "/data/local/tmp/m_v.gray", "/data/local/tmp/m_a.wav"
    cmd = "cd /data/local/tmp && ./sc-ffmpeg -hide_banner -loglevel error -y -i '%s' " % path
    if not audio_only:
        cmd += "-map 0:v:0 -vf scale=8:8,format=gray -fps_mode cfr -r %d -f rawvideo %s " % (FPS, remote_v)
    cmd += "-map 0:a:0 -af aresample=async=1:first_pts=0 -c:a pcm_s16le -f wav %s" % remote_a
    r = adb(serial, "shell", cmd)
    if r.returncode != 0:
        sys.exit("Dekodieren fehlgeschlagen: " + r.stderr.decode(errors="replace") + r.stdout.decode(errors="replace"))
    local_a = os.path.join(OUT, "m_%s.wav" % tag)
    adb(serial, "pull", remote_a, local_a)

    with wave.open(local_a) as w:
        rate, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        raw = w.readframes(n)
    ints = struct.unpack("<%dh" % (len(raw) // 2), raw)
    left = ints[0::ch]
    right = ints[1::ch] if ch > 1 else left
    print("Ton: %.2f s, %d Hz, %d Kanäle" % (n / rate, rate, ch))
    # Links 1 kHz mit 4-kHz-Piep, rechts 500 Hz mit 2-kHz-Piep; nach Resampling kann die Rate anders sein.
    bl = beep_onsets(left, rate, 4000)
    br = beep_onsets(right, rate, 2000)
    print("Piep links (4 kHz): %d, rechts (2 kHz): %d" % (len(bl), len(br)))
    # Stereo: links liegt der 1-kHz-Grundton, rechts der 500-Hz-Ton (gemessen in einer ruhigen halben Sekunde).
    seg = slice(int(rate * 2.3), int(rate * 2.8))
    l1, l5 = goertzel_power(left[seg], 1000, rate), goertzel_power(left[seg], 500, rate)
    r1, r5 = goertzel_power(right[seg], 1000, rate), goertzel_power(right[seg], 500, rate)
    if ch < 2:
        print("Stereo: nur ein Kanal")
    elif l1 > l5 and r5 > r1:
        print("Stereo: richtig (links 1 kHz, rechts 500 Hz)")
    elif l5 > l1 and r1 > r5:
        print("!! Stereo: Kanäle vertauscht")
    else:
        print("Stereo: gemischt (z. B. Mono-Abmischung)")

    if not audio_only:
        local_v = os.path.join(OUT, "m_%s.gray" % tag)
        adb(serial, "pull", remote_v, local_v)
        data = open(local_v, "rb").read()
        frames = [sum(data[i:i + 64]) / 64 for i in range(0, len(data) - 63, 64)]
        flashes = [i / FPS for i, m in enumerate(frames) if m > 200 and (i == 0 or frames[i - 1] <= 200)]
        print("Bild: %.2f s (%d Bilder), Blitze: %d" % (len(frames) / FPS, len(frames), len(flashes)))
        offsets = []
        for f in flashes:
            near = [b for b in bl if abs(b - f) < 0.4]
            if near:
                offsets.append((near[0] - f) * 1000)
        if offsets:
            offsets.sort()
            print("Ton minus Bild: Median %+.0f ms, min %+.0f ms, max %+.0f ms (%d Paare)"
                  % (offsets[len(offsets) // 2], offsets[0], offsets[-1], len(offsets)))
            print("  Anfang: %s" % ", ".join("%+.0f" % o for o in offsets[:3]))
        else:
            print("!! keine Paare aus Blitz und Piep gefunden")
    os.remove(local_a)


if __name__ == "__main__":
    main()
