using System;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;

namespace DiscoverEnabler
{
    /// <summary>Official Emby for Android builds, downloaded straight from Emby's own GitHub (MediaBrowser/Emby.Releases).</summary>
    public sealed class EmbyBuild
    {
        public string Id, Title, Description, File;
        public string Url => EmbyDownloads.ReleaseBase + File;
    }

    public static class EmbyDownloads
    {
        public const string ReleaseBase = "https://github.com/MediaBrowser/Emby.Releases/raw/master/android/";

        public static readonly EmbyBuild[] Builds =
        {
            new EmbyBuild { Id = "arm64", Title = "ARM64", Description = "Most phones and tablets", File = "emby-android-google-arm64-v8a-release.apk" },
            new EmbyBuild { Id = "armv7", Title = "ARMv7", Description = "Android TV, TV boxes and older phones", File = "emby-android-google-armeabi-v7a-release.apk" },
            new EmbyBuild { Id = "firetv", Title = "Fire TV", Description = "Amazon Fire TV and Fire tablets", File = "emby-android-amazon-armeabi-v7a-release.apk" },
            new EmbyBuild { Id = "x64", Title = "x64", Description = "PCs, Chromebooks and emulators", File = "emby-android-google-x86_64-release.apk" },
            new EmbyBuild { Id = "x86", Title = "x86", Description = "Older Intel devices", File = "emby-android-google-x86-release.apk" },
        };

        public static EmbyBuild Find(string id) =>
            Builds.FirstOrDefault(b => string.Equals(b.Id, id, StringComparison.OrdinalIgnoreCase));

        public static string OutputName(EmbyBuild b) => "Emby-" + b.Title.Replace(" ", "") + "-Discover.apk";

        /// <summary>Downloads a build; progress gets (bytes done, total or -1).</summary>
        public static async Task Download(EmbyBuild build, string path, Action<long, long> progress)
        {
            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) })
            {
                http.DefaultRequestHeaders.UserAgent.ParseAdd("DiscoverEnabler/1.0");
                using (var resp = await http.GetAsync(build.Url, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false))
                {
                    resp.EnsureSuccessStatusCode();
                    long total = resp.Content.Headers.ContentLength ?? -1, done = 0;
                    using (var src = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false))
                    using (var dst = System.IO.File.Create(path))
                    {
                        var buf = new byte[81920];
                        int n;
                        while ((n = await src.ReadAsync(buf, 0, buf.Length).ConfigureAwait(false)) > 0)
                        {
                            await dst.WriteAsync(buf, 0, n).ConfigureAwait(false);
                            done += n;
                            progress?.Invoke(done, total);
                        }
                    }
                }
            }
        }
    }
}
