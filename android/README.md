# Discover Patcher 2.9.2-beta (Android source)

Option 1 of [Enabler for Emby](../README.md).

Android app (phone and Android TV / Chromecast) that downloads the latest official Emby Android beta, adds the Discover loader, signs it with its own key and installs it. Emby Premiere and server permissions are left untouched.

## Languages
- The UI follows the phone language: English (default), Dutch, German and Norwegian Bokmål — the same languages as the Discover plugin.
- Texts live in `app/src/main/res/values*/strings.xml`. To add a language, copy `values/strings.xml` to `values-<code>/strings.xml` (e.g. `values-fr`) and translate it.
- The activity log, code comments and the loader's console output are always in English (easier for support).

## New in 2.9.2-beta
- The status checks what is actually installed: no Emby, Emby with a different signature (official app or another patch), or a patched Emby from this patcher. Only for a patched Emby is the online update check done.
- If an Emby app with a different signature is installed, an **Uninstall existing Emby app** button appears. Android cannot convert an existing app in place; after uninstalling you have to sign in to Emby again (server and library are not changed).
- After Emby has been uninstalled, the normal button installs again without needing "force".
- Patcher icon: the Discover magnifier with a white gear badge, so it is clearly different from the plugin; adaptive and monochrome (themed) icons; TV banner with "⚙ PATCHER" under the Discover logo; Discover notification icon.
- The real Emby icon comes from the installed Emby app itself and is only used in the "Emby is installed" popup (with an **Open Emby** button) and as the large icon of the Emby update notification. No Emby logo is shipped.
- New switch **Show the server menu (server settings) in the app** (on by default). The choice is written to `modules/discover-options.js` at patch time; rebuild after changing it.
- versionCode 11 / versionName 2.9.2-beta.

## Notes
- The signing key is stored in the patcher's app data. Uninstalling the patcher loses the key; an Emby patched earlier then counts as "different signature" and has to be uninstalled first.
- The Emby dashboard, server settings and plugin permissions are decided by the server/account; the patcher does not bypass server authorization.
- Full Android TV / Chromecast D-pad testing has to be done on real hardware.

## Release signing (CI)
The patcher app itself must always be signed with the **same** key, otherwise a new patcher version cannot be installed over the old one
(and the Emby signing key stored in its app data would be lost). CI signs release builds when these repository secrets exist:

| Secret | Value |
|---|---|
| `PATCHER_KEYSTORE_B64` | the keystore file, base64-encoded (`base64 -w0 patcher.jks`) |
| `PATCHER_KEYSTORE_PASSWORD` | keystore password |
| `PATCHER_KEY_ALIAS` | key alias |
| `PATCHER_KEY_PASSWORD` | key password (optional, defaults to the keystore password) |

Without them CI builds a debug-signed `DiscoverPatcher-test.apk` (test builds only, not attached to releases).

## Building
Use Android Studio with JDK 17. The project uses Android Gradle Plugin 9.4.0 and Gradle 9.6.0 (see `gradle/wrapper/gradle-wrapper.properties`). Run **Sync Project with Gradle Files**, then **Build APK**. The CI workflow in `.github/workflows/build.yml` uses the same versions.
