"""Kopiert die benötigten FFmpeg-Dateien ins Repo und schreibt sources.cmake.

Aufruf: python install_into_repo.py <arbeitsordner> <ziel>
  arbeitsordner: enthält ffmpeg-<version>/, build-<abi>/ und collect.json (siehe collect.py)
  ziel:          app/src/main/cpp/ffmpeg
"""
import json, os, re, shutil, sys

BASE = os.path.abspath(sys.argv[1])
SRC = os.path.join(BASE, "ffmpeg-9.0.2")
DEST = os.path.abspath(sys.argv[2])
r = json.load(open(os.path.join(BASE, "collect.json"), encoding="utf-8"))

for sub in ["src", "config", "sources.cmake"]:
    path = os.path.join(DEST, sub)
    if os.path.isdir(path):
        shutil.rmtree(path)
    elif os.path.exists(path):
        os.remove(path)

# 1. Quellen und Header (Vereinigung aller ABIs) + Lizenz
for rel in r["src_files"] + ["LICENSE.md", "COPYING.LGPLv2.1", "VERSION", "CREDITS"]:
    dst = os.path.join(DEST, "src", rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(os.path.join(SRC, rel), dst)

# 2. Generierte Konfiguration je ABI – ohne lokale Pfade
for abi, info in r["abis"].items():
    for rel in info["generated"]:
        dst = os.path.join(DEST, "config", abi, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(os.path.join(BASE, "build-" + abi, rel), dst)
    cfg = os.path.join(DEST, "config", abi, "config.h")
    text = open(cfg, encoding="utf-8").read()
    text = re.sub(r"[A-Za-z]:/[^ ']*/toolchains/llvm/prebuilt/[^ ']*/bin/", "$NDK_TOOLCHAIN/", text)
    open(cfg, "w", encoding="utf-8", newline="\n").write(text)

# 3. Zu kompilierende Dateien je ABI (ARM mit NEON-Assembler, x86_64 nur C)
with open(os.path.join(DEST, "sources.cmake"), "w", encoding="utf-8", newline="\n") as f:
    f.write("# Erzeugt von tools/install_into_repo.py - nicht von Hand ändern.\n")
    for abi, info in r["abis"].items():
        assert all(not c.startswith("@") for c in info["compiled"]), "generierte Quellen werden eingebunden, nicht kompiliert"
        f.write("set(FFMPEG_SOURCES_%s\n" % abi)
        for c in info["compiled"]:
            f.write("    ${FFMPEG_SRC}/%s\n" % c)
        f.write(")\n")

# 4. Werkzeuge zum Neu-Erzeugen
tools = os.path.join(DEST, "tools")
os.makedirs(tools, exist_ok=True)
for t in ["configure_abi.sh", "collect.py", "install_into_repo.py"]:
    shutil.copy2(os.path.join(BASE, t), tools)

size = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(DEST) for f in fs)
count = sum(len(fs) for _, _, fs in os.walk(DEST))
print("Dateien:", count, "Größe: %.1f MB" % (size / 1e6),
      "kompiliert:", {abi: len(i["compiled"]) for abi, i in r["abis"].items()})
