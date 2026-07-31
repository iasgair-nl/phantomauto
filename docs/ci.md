# CI/CD

## Workflows

| Workflow | Trigger | Doet |
|---|---|---|
| `.github/workflows/ci.yml` | push/PR op `development` + `main`, manueel | JDK 21 (temurin), Gradle cache, `assembleDebug`, `test`, `lint`. Uploadt debug-APK + lint-rapport als artifacts. |
| `.github/workflows/release.yml` | tag `v*`, of manueel met tag-input | `assembleRelease`, signt de APK en publiceert een GitHub Release met de APK erbij. |

Beide draaien op GitHub-hosted `ubuntu-latest`.

## Release maken

```bash
git tag v0.1.0
git push origin v0.1.0
```

De workflow bouwt `phantomauto-v0.1.0.apk` en hangt die aan de Release.

## Signing secrets

De release-signing wordt via environment variables gevoed; er staat niets in de
repo. Zet deze **repository secrets** (Settings → Secrets and variables →
Actions):

| Secret | Inhoud |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | keystore-wachtwoord |
| `KEY_ALIAS` | alias van de key |
| `KEY_PASSWORD` | wachtwoord van de key |

Keystore aanmaken (bewaar dit bestand veilig — zonder deze key kun je geen
updates meer uitbrengen):

```bash
keytool -genkeypair -v \
  -keystore release.keystore -alias phantomauto \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore   # → KEYSTORE_BASE64
```

Zonder deze secrets faalt de release **niet**: de APK wordt dan unsigned
gebouwd (alleen installeerbaar via `adb install-multiple`/`adb install` met
handmatig signen). Zodra de secrets er zijn, is elke release correct gesigneerd
en upgradebaar.

Lokaal een gesigneerde release bouwen:

```bash
RELEASE_KEYSTORE=$PWD/release.keystore \
RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=phantomauto \
RELEASE_KEY_PASSWORD=... \
./gradlew assembleRelease
```
