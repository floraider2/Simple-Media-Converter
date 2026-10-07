"""Ermittelt aus den .d-Dateien eines FFmpeg-Builds, welche Quellen und Header gebraucht werden.

Ausgabe je ABI: Liste der zu kompilierenden .c-Dateien (relativ zur Quelle) und der generierten Dateien
(relativ zum Build-Ordner). Dazu die Vereinigung aller Quell- und Header-Dateien für alle ABIs.
"""
import json, os, re, sys

BASE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".").replace("\\", "/")
SRC = BASE + "/ffmpeg-9.0.2"
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]


def norm(p):
    return os.path.normpath(p).replace("\\", "/")


result = {"abis": {}, "src_files": set()}
for abi in ABIS:
    build = BASE + "/build-" + abi
    compiled, generated = [], set()
    for root, _, files in os.walk(build):
        for f in files:
            if not f.endswith(".d"):
                continue
            text = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
            text = text.replace("\\\n", " ")
            target, deps = text.split(":", 1) if not re.match(r"^[A-Za-z]:", text) else text.split(": ", 1)
            deps = deps.split()
            first = True
            for d in deps:
                d = d.strip()
                if not d:
                    continue
                full = norm(d if re.match(r"^[A-Za-z]:", d) else os.path.join(build, d))
                if full.startswith(norm(SRC) + "/"):
                    rel = full[len(norm(SRC)) + 1:]
                    result["src_files"].add(rel)
                    if first and rel.endswith((".c", ".S")):
                        compiled.append(rel)
                elif full.startswith(norm(build) + "/"):
                    rel = full[len(norm(build)) + 1:]
                    generated.add(rel)
                    if first and rel.endswith((".c", ".S")):
                        compiled.append("@" + rel)  # generierte Quelle
                first = False
    result["abis"][abi] = {"compiled": sorted(set(compiled)), "generated": sorted(generated)}

result["src_files"] = sorted(result["src_files"])
json.dump(result, open(BASE + "/collect.json", "w"), indent=1)
for abi, r in result["abis"].items():
    print(abi, "kompiliert:", len(r["compiled"]), "generiert:", r["generated"])
print("Quell- und Header-Dateien gesamt:", len(result["src_files"]))
size = sum(os.path.getsize(SRC + "/" + f) for f in result["src_files"])
print("Größe: %.1f MB" % (size / 1e6))
