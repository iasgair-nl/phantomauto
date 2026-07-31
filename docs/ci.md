# CI/CD

## Workflows

| Workflow | Trigger | Doet |
|---|---|---|
| `.github/workflows/ci.yml` | push/PR op `development`, `main`, `release`, manueel | JDK 21 (temurin), Gradle cache, `assembleDebug`, `test`, `lint`. Uploadt debug-APK + lint-rapport als artifacts. |
| `.github/workflows/release.yml` | push op `release` (of manueel) | `assembleRelease`, signt de APK en publiceert een GitHub Release met de APK erbij. |

Beide draaien op GitHub-hosted `ubuntu-latest`.

## Release maken

Een release ontstaat alleen door code naar de `release`-branch te brengen:

```bash
# 1. bump versionName in app/build.gradle.kts (bv. 0.1.0 -> 0.2.0)
# 2. merge development -> release via een PR
```

Zodra er op `release` gepusht wordt, leest de workflow `versionName` uit
`app/build.gradle.kts`, maakt de tag `v<versionName>` aan, bouwt
`phantomauto-v<versionName>.apk` en publiceert die als GitHub Release.

Bestaat die tag al, dan doet de workflow niets (geen dubbele release).
Bump dus `versionName` voor elke nieuwe release.

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
