# Changelog

Discover Enabler (Windows, Linux, macOS) and Discover Patcher (Android) are released together, under one
version number for the release. The version of each tool is mentioned per entry.

## v1.3.3 — Discover Patcher 2.9.5

**On a TV: readable and operable**

- A device without a touchscreen now counts as a TV as well: some TVs and TV sticks do not report
  themselves as one and got the phone layout. A new **TV mode** switch under *Log & settings* forces it
  either way; the screen rebuilds itself right after.
- Text is 1.5x on a TV, including the small headers and hints that stayed small before. Logo and buttons
  are taller.
- Margins follow the 5% overscan safe area, so nothing falls off the edge of the picture any more.
- More contrast on the panels and the secondary text (they were nearly invisible on a dark TV), and a
  thicker focus ring.
- D-pad: up from the buttons reaches the tabs, and the activity log can now be scrolled with the remote.

**A notice when there is a new version**

- Every release now carries `patcher-version.json`. The app reads it through the fixed download link of
  the newest release, so it needs no GitHub API and runs into no rate limit.
- A new Discover Patcher shows as a green banner above the status, which opens the download page; on a TV
  you can select it with the remote. A new Emby version appears in that banner too.
- The daily check also sends a notification for a new Discover Patcher, next to the one for Emby.

The app's own version check starts working from the release after this one, because this is the first
release that carries the version file.

Discover Enabler (Windows, Linux, macOS) is unchanged since 1.3.0.

## v1.3.2 — Discover Patcher 2.9.4

- Fix: the Emby update check could still fail ("update check failed"). The patcher now asks the APK file
  itself (on the same address as the download) instead of the GitHub API, which is limited to 60 calls per
  hour per network. The GitHub API is only a fallback. When a check fails, the reason is written to the
  activity log.
- New: two tabs. **Emby** shows the status and the actions; **Log & settings** holds the server menu switch
  and the activity log. After an action, the latest log line is also shown under the status.
- Change: the Discover logo in the app now has the gear badge, like the app icon and the banner.
- Note: if your Emby was patched by an older patcher version and the status says "rebuild to update",
  rebuild once; after that the new check takes over.

Discover Enabler (Windows, Linux, macOS) is unchanged since 1.3.0.

## v1.3.1 — Discover Patcher 2.9.3

- Fix: on Chromecast with Google TV and other Android TVs the remote could lose the focus (no button
  highlighted), so nothing could be selected. The focus now always stays on a visible button.
- Fix: the Emby update check always failed ("update check failed").
- Change: the main button now says what it does: "Download Emby and add Discover".

Discover Enabler (Windows, Linux, macOS) is unchanged since 1.3.0.

## v1.3.0 — Discover Enabler 1.3.0 + Discover Patcher 2.9.2 (new)

**New: Discover Patcher for Android.** Install `DiscoverPatcher.apk` on your phone, tablet or TV box. It
downloads the latest official Emby beta, adds Discover, signs it and installs it, right on the device.

- Shows what is installed (no Emby / official Emby / patched Emby) and offers to uninstall the official
  app first.
- Daily check for new Emby versions, one tap to update.
- Choice to show Emby's server menu (server settings) in the app.
- Follows the phone language (English, Dutch, German, Norwegian), works with a TV remote.

**Discover Enabler (Windows, Linux, macOS)**

- New option: show the server menu (server settings) in the app — a checkbox in Windows,
  `--no-server-menu` on the command line.
- The Discover tile always opens the Discover page.
- The window follows the Windows language (English, Dutch, German, Norwegian).
- New icon.

Emby Premiere, playback and everything else stay as in the original app. The server needs the Discover
Media plugin.
