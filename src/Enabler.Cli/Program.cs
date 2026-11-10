using System;
using System.IO;
using System.Linq;
using DiscoverEnabler;

// discover-enabler --device arm64|armv7|firetv|x64|x86 [--out folder] [--key file.pfx]
// discover-enabler <emby.apk> [output.apk] [--key file.pfx]
string input = null, output = null, key = null, device = null, outDir = null;
for (int i = 0; i < args.Length; i++)
{
    var a = args[i];
    if ((a == "--key") && i + 1 < args.Length) key = args[++i];
    else if ((a == "--device" || a == "-d") && i + 1 < args.Length) device = args[++i];
    else if ((a == "--out" || a == "-o") && i + 1 < args.Length) outDir = args[++i];
    else if (a == "--help" || a == "-h") { input = null; device = null; break; }
    else if (input == null) input = a;
    else output = a;
}

if (input == null && device == null)
{
    Console.WriteLine("Discover Enabler - makes the Emby for Android app load the Discover Media plugin.");
    Console.WriteLine();
    Console.WriteLine("Download from Emby and patch:");
    Console.WriteLine("  discover-enabler --device <device> [--out folder]");
    foreach (var b in EmbyDownloads.Builds) Console.WriteLine("      " + b.Id.PadRight(8) + b.Description);
    Console.WriteLine();
    Console.WriteLine("Patch an APK you already have:");
    Console.WriteLine("  discover-enabler <emby.apk> [output.apk]");
    Console.WriteLine();
    Console.WriteLine("Options: --key <file.pfx>   signing key (default: " + ApkSigner.DefaultKeyPath() + ")");
    return 2;
}

string temp = null;
try
{
    if (device != null)
    {
        var build = EmbyDownloads.Find(device);
        if (build == null) { Console.Error.WriteLine("Unknown device '" + device + "'. Use one of: " + string.Join(", ", EmbyDownloads.Builds.Select(b => b.Id))); return 2; }
        temp = Path.Combine(Path.GetTempPath(), build.File);
        Console.WriteLine("Downloading " + build.Url);
        int last = -1;
        EmbyDownloads.Download(build, temp, (d, t) =>
        {
            if (t <= 0) return;
            int pct = (int)(d * 100 / t);
            if (pct / 10 != last) { last = pct / 10; Console.Write("\r  " + pct + "% (" + d / 1048576 + " of " + t / 1048576 + " MB)   "); }
        }).GetAwaiter().GetResult();
        Console.WriteLine();
        input = temp;
        output = Path.Combine(outDir ?? Directory.GetCurrentDirectory(), EmbyDownloads.OutputName(build));
    }
    else if (output == null)
    {
        output = outDir != null ? Path.Combine(outDir, Path.GetFileNameWithoutExtension(input) + "-discover.apk") : EmbyApkPatcher.DefaultOutputPath(input);
    }
    Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output)));
    EmbyApkPatcher.Patch(input, output, key ?? ApkSigner.DefaultKeyPath(), Console.WriteLine);
    Console.WriteLine();
    Console.WriteLine("Uninstall the official Emby app once, then install " + Path.GetFileName(output) + " on your device.");
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine("Error: " + ex.Message);
    return 1;
}
finally
{
    if (temp != null) try { File.Delete(temp); } catch { }
}
