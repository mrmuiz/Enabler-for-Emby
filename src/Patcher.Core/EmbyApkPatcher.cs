using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace DiscoverPatcher
{
    /// <summary>
    /// Patches the official Emby for Android APK (from emby.media / MediaBrowser/Emby.Releases) so it loads
    /// Discover Media. Only two things change in the app's built-in web client:
    ///   1. index.html loads discover-web-loader.js (tile -> Discover page, Request button everywhere);
    ///   2. apphost.js no longer reports "restrictedplugins", so plugin pages (Discover) show in the menu.
    /// Everything else (Emby Premiere, playback, DRM) stays exactly as it is. The APK is then re-signed.
    /// </summary>
    public static class EmbyApkPatcher
    {
        public const string Marker = "discover-web-loader.js";
        const string IndexPath = "assets/www/index.html";
        const string AppHostPath = "assets/www/native/android/apphost.js";
        const string LoaderPath = "assets/www/discover-web-loader.js";

        public sealed class Result
        {
            public string OutputPath;
            public List<string> Log = new List<string>();
        }

        public static Result Patch(string inputApk, string outputApk, string keyPath, Action<string> progress = null)
        {
            var r = new Result { OutputPath = outputApk };
            void Say(string m) { r.Log.Add(m); progress?.Invoke(m); }

            Say("Reading " + Path.GetFileName(inputApk) + " ...");
            var bytes = File.ReadAllBytes(inputApk);
            var entries = ApkZip.Read(bytes);

            var index = entries.FirstOrDefault(e => e.Name == IndexPath);
            var apphost = entries.FirstOrDefault(e => e.Name == AppHostPath);
            if (index == null || apphost == null)
                throw new InvalidDataException("This does not look like the Emby for Android app (phone/tablet version). " +
                    "Download the APK from emby.media (Emby for Android). The separate Android TV app cannot be patched.");

            // 1. index.html
            var html = Encoding.UTF8.GetString(index.GetUncompressed());
            if (html.Contains(Marker))
            {
                Say("index.html: already patched");
            }
            else
            {
                int at = html.LastIndexOf("</body>", StringComparison.OrdinalIgnoreCase);
                if (at < 0) throw new InvalidDataException("index.html has no </body>; unknown Emby version");
                html = html.Substring(0, at) + "    <script src=\"" + Marker + "\" defer></script>\n" + html.Substring(at);
                Say("index.html: Discover loader added");
            }
            Replace(entries, index, Encoding.UTF8.GetBytes(html));

            // 2. apphost.js
            var js = Encoding.UTF8.GetString(apphost.GetUncompressed());
            const string on = "features.restrictedplugins = true;";
            const string off = "features.restrictedplugins = false; /* Discover Patcher */";
            if (js.Contains(on))
            {
                js = js.Replace(on, off);
                Say("apphost.js: plugin pages allowed in the app (Discover in the menu)");
            }
            else if (js.Contains(off)) Say("apphost.js: already patched");
            else Say("apphost.js: restriction not found (newer Emby version?) - menu entry may be missing");
            Replace(entries, apphost, Encoding.UTF8.GetBytes(js));

            // 3. the loader itself
            var loader = ReadResource("discover-web-loader.js");
            var existing = entries.FirstOrDefault(e => e.Name == LoaderPath);
            if (existing != null) Replace(entries, existing, loader);
            else entries.Insert(entries.IndexOf(index) + 1, ZipEntry.Create(LoaderPath, loader, true, index));
            Say("discover-web-loader.js: added");

            // 4. sign
            Say("Signing (key: " + keyPath + ") ...");
            var cert = ApkSigner.LoadOrCreateKey(keyPath);
            ApkSigner.AddV1(entries, cert);
            var unsigned = ApkZip.Write(entries, out long cd, out long eocd);
            var signed = ApkSigner.AddV2(unsigned, cd, eocd, cert);
            File.WriteAllBytes(outputApk, signed);
            Say("Done: " + outputApk);
            return r;
        }

        static void Replace(List<ZipEntry> entries, ZipEntry old, byte[] content)
        {
            int i = entries.IndexOf(old);
            entries[i] = ZipEntry.Create(old.Name, content, old.Method == 8, old);
        }

        static byte[] ReadResource(string name)
        {
            using (var s = typeof(EmbyApkPatcher).Assembly.GetManifestResourceStream(name))
            using (var ms = new MemoryStream())
            {
                if (s == null) throw new InvalidOperationException("Missing resource " + name);
                s.CopyTo(ms);
                return ms.ToArray();
            }
        }

        public static string DefaultOutputPath(string input)
        {
            var dir = Path.GetDirectoryName(Path.GetFullPath(input));
            return Path.Combine(dir, Path.GetFileNameWithoutExtension(input) + "-discover.apk");
        }
    }
}
