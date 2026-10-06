# Releases veröffentlichen

Zwei Wege – beide erzeugen dieselbe, mit dem Release-Schlüssel signierte APK.

## A) Lokal mit der GitHub CLI (funktioniert immer)

```bash
./gradlew assembleRelease          # signiert mit ../SimpleConverter-Signing
cp app/build/outputs/apk/release/app-release.apk SimpleConverter-v0.2.0.apk
sha256sum SimpleConverter-v0.2.0.apk > SimpleConverter-v0.2.0.apk.sha256
git tag -a v0.2.0 -m "Simple Converter 0.2.0" && git push origin v0.2.0
gh release create v0.2.0 SimpleConverter-v0.2.0.apk SimpleConverter-v0.2.0.apk.sha256 --title "Simple Converter 0.2.0" --notes-file notes.md
```

Vorher die Release-APK auf einem Gerät testen (R8 entfernt Code – Fehler zeigen sich nur im Release-Build).

## B) Automatisch per GitHub Actions

Tag pushen → `.github/workflows/release.yml` baut die signierte APK und legt sie unter **Releases** ab.
Voraussetzung: GitHub Actions läuft für das Konto, und die Secrets unten sind eingerichtet.

```bash
git tag -a v0.2.0 -m "Simple Converter 0.2.0"
git push origin v0.2.0
```

## Für B einmalig: Signaturschlüssel als Secrets hinterlegen

Der Schlüssel liegt **nicht** im Repo, sondern in `../SimpleConverter-Signing/` (neben dem Projektordner).
Unter *Settings → Secrets and variables → Actions → New repository secret* vier Einträge anlegen:

| Secret | Inhalt |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `release.jks` als Base64, siehe unten |
| `SIGNING_STORE_PASSWORD` | `storePassword` aus `keystore.properties` |
| `SIGNING_KEY_ALIAS` | `simpleconverter` |
| `SIGNING_KEY_PASSWORD` | `keyPassword` aus `keystore.properties` |

Base64 erzeugen (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\Documents\SimpleConverter-Signing\release.jks")) | Set-Clipboard
```

## Lokal bauen

```bash
./gradlew assembleRelease
```

Liegt `../SimpleConverter-Signing/keystore.properties` vor, wird die APK damit signiert; sonst bleibt sie unsigniert.

## Zertifikat

Alle offiziellen APKs sind mit diesem Zertifikat signiert:

```
SHA-256: 65:F2:4A:35:D8:EB:8C:E2:48:97:7A:5D:03:15:8F:42:26:32:40:C4:D2:F3:2C:C9:EB:28:EF:C9:B2:79:25:50
```

Prüfen: `apksigner verify --print-certs SimpleConverter-vX.Y.Z.apk`
