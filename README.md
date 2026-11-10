# Discover Patcher

Makes the official **Emby for Android** app (phone/tablet, also runs on Android TV) load the
[Discover Media](https://github.com/mrmuiz/Discover-Media) plugin:

- **Discover** shows in the app's menu (the app normally hides plugin pages);
- the **Discover tile** opens the Discover page, and the **Request** button works on Emby's own detail pages.

Nothing else changes: Emby Premiere, playback and everything else stay exactly as in the original app.
You still need an Emby Premiere license where Emby asks for one.

## How to use (Windows)

1. Download the APK for your device from **[emby.media → Emby for Android](https://emby.media/emby-for-android.html)**
   (most phones and TV boxes: *arm64*).
2. Start `DiscoverPatcher.exe`, choose the APK (or drop it on the window) and click **Patch**.
3. Uninstall the official Emby app once, then install the new `…-discover.apk`.

The patcher signs the app with its own key, kept on your computer
(`%APPDATA%\DiscoverPatcher\signing-key.pfx`). Patch later Emby updates on the **same computer**
(or copy that file), then they install over the patched app without uninstalling again.

The Emby server must have the Discover Media plugin installed.

## What it changes

| File in the APK | Change |
|---|---|
| `assets/www/index.html` | one `<script>` line that loads `discover-web-loader.js` |
| `assets/www/discover-web-loader.js` | added (the same loader the plugin puts in the server's web client) |
| `assets/www/native/android/apphost.js` | `restrictedplugins` set to `false` |

## Not supported

- The separate **Emby for Android TV** app (it has no built-in web client); use the phone/tablet APK on Android TV instead.
- **iOS**, **Roku**: apps cannot be patched.
- **LG webOS**: Emby offers no app file to download.

## Command line

```
discover-patcher <emby.apk> [output.apk] [--key signing-key.pfx]
```
