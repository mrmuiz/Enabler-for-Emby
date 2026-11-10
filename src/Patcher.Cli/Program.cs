using System;
using DiscoverPatcher;

// Usage: discover-patcher <emby.apk> [output.apk] [--key signing-key.pfx]
string input = null, output = null, key = null;
for (int i = 0; i < args.Length; i++)
{
    if (args[i] == "--key" && i + 1 < args.Length) key = args[++i];
    else if (input == null) input = args[i];
    else output = args[i];
}
if (input == null)
{
    Console.Error.WriteLine("Usage: discover-patcher <emby-android.apk> [output.apk] [--key signing-key.pfx]");
    return 2;
}
try
{
    EmbyApkPatcher.Patch(input, output ?? EmbyApkPatcher.DefaultOutputPath(input), key ?? ApkSigner.DefaultKeyPath(), Console.WriteLine);
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine("Error: " + ex.Message);
    return 1;
}
