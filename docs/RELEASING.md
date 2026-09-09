# Releasing & self-update

The app self-updates from **GitHub Releases**. Release APKs must all be signed with the **same
keystore** (otherwise Android refuses to install the update over the previous version). CI signs
release builds with a keystore provided via **GitHub Secrets** — set this up once.

## 1. One-time: create a signing keystore (run locally)

```bash
keytool -genkeypair -v -keystore app.keystore -alias dvripcam \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass CHANGE_ME_STORE -keypass CHANGE_ME_KEY \
  -dname "CN=dvrip-cam, O=kwebmn"

base64 -w0 app.keystore > app.keystore.b64   # (macOS: base64 -i app.keystore -o app.keystore.b64)
```

Keep `app.keystore` safe and **never commit it**. Losing it means you can no longer ship updates
that install over existing installs.

## 2. One-time: add GitHub repo Secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `app.keystore.b64` |
| `KEYSTORE_PASSWORD` | your `-storepass` (e.g. `CHANGE_ME_STORE`) |
| `KEY_ALIAS` | `dvripcam` |
| `KEY_PASSWORD` | your `-keypass` (e.g. `CHANGE_ME_KEY`) |

`.github/workflows/release.yml` decodes the keystore and passes these to Gradle; if the secrets are
absent it falls back to the debug key (fine for testing, but debug-signed APKs won't self-update).

## 3. Cut a release

Bump the version in `app/build.gradle.kts` (`versionCode` +1 and `versionName`), commit, then tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

CI (`release.yml`) builds a **signed release APK** and attaches it to a **GitHub Release** with
auto-generated notes. Install that APK on the phone once.

## 4. How self-update works in the app

- On launch the app calls `GET /repos/kwebmn/dvrip-cam/releases/latest`, compares the release tag
  (semver) with `BuildConfig.VERSION_NAME`, and if newer shows an **“Update available”** card with
  the changelog.
- Tapping **Update** downloads the release APK and launches the system installer (via `FileProvider`;
  needs the one-time *Install unknown apps* permission for this app).
- Because every release is signed with the same key, the update installs over the current app and
  keeps its data.

> Security note: for a personal tool the release keystore lives only in GitHub Secrets. Anyone who
> can push a tag to this repo can ship an update; the app only ever fetches from this repo over HTTPS.
