# CI/CD

## Workflows

| Workflow | Trigger | Description |
|---|---|---|
| `.github/workflows/ci.yml` | push/PR to `development`, `main`, `release`, manual | JDK 21 (temurin), Gradle cache, `assembleDebug`, `test`, `lint`. Uploads debug APK + lint report as artifacts. |
| `.github/workflows/release.yml` | push to `release` (or manual) | `assembleRelease`, signs the APK and publishes a GitHub Release with the APK attached. |

Both run on GitHub-hosted `ubuntu-latest`.

## Creating a Release

A release is only created by bringing code to the `release` branch:

```bash
# 1. bump versionName in app/build.gradle.kts (e.g., 0.1.0 -> 0.2.0)
# 2. merge development -> release via a PR
```

As soon as a push occurs on `release`, the workflow reads `versionName` from
`app/build.gradle.kts`, creates the tag `v<versionName>`, builds
`phantomauto-v<versionName>.apk`, and publishes it as a GitHub Release.

If that tag already exists, the workflow does nothing (no duplicate releases).
Therefore, bump `versionName` for every new release.

## Signing Secrets

Release signing is powered by environment variables; nothing is stored in the
repo. Set these **repository secrets** (Settings → Secrets and variables →
Actions):

| Secret | Content |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Creating a keystore (keep this file safe — without this key you cannot release
updates):

```bash
keytool -genkeypair -v \
  -keystore release.keystore -alias phantomauto \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore   # → KEYSTORE_BASE64
```

Without these secrets, the release will **not** fail: the APK will be built
unsigned (only installable via `adb install-multiple`/`adb install` with
manual signing). Once the secrets are present, every release is correctly signed
and upgradeable.

Building a signed release locally:

```bash
RELEASE_KEYSTORE=$PWD/release.keystore \
RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=phantomauto \
RELEASE_KEY_PASSWORD=... \
./gradlew assembleRelease
```
