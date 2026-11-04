using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Minecraft;

internal static class PublicPreparationProof
{
    internal static void Write(AppPaths paths)
    {
        if (!File.Exists(Path.Combine(paths.Root, ".legacy-runtime-qa.json"))) throw new IOException("Not a QA fixture.");
        var pack = paths.CombineUnderPacks("Better Than Wolves");
        var game = paths.CombineUnderInstances("Better Than Wolves");
        var descriptor = PackManifestService.Load(pack);
        var packFiles = Directory.EnumerateFiles(pack, "*", SearchOption.AllDirectories)
            .Select(file => new FilePin(Path.GetRelativePath(pack, file).Replace('\\', '/'), new FileInfo(file).Length, Hash(file)))
            .OrderBy(file => file.Path, StringComparer.Ordinal).ToArray();
        var tree = string.Join('\n', packFiles.Select(file => $"{file.Path}|{file.SizeBytes}|{file.Sha256}"));
        var mods = Directory.EnumerateFiles(Path.Combine(game, "mods"), "*.jar").Select(file =>
            new FilePin(Path.GetFileName(file), new FileInfo(file).Length, Hash(file))).OrderBy(file => file.Path, StringComparer.Ordinal).ToArray();
        var features = new[] { "legacy-fabric-runtime-v1", "legacy-player-profiles-v1", "legacy-steam-v1" };
        var supported = (HashSet<string>)typeof(PortablePackSyncService)
            .GetField("SupportedLauncherFeatures", BindingFlags.Static | BindingFlags.NonPublic)!.GetValue(null)!;
        using var preparation = JsonDocument.Parse(File.ReadAllText(Path.Combine(paths.Root, "production-preparation.json")));
        var launchPath = Path.Combine(paths.Root, "private", "launch-context.json");
        using var launch = File.Exists(launchPath) ? JsonDocument.Parse(File.ReadAllText(launchPath)) : null;
        var agents = launch is null ? [] : launch.RootElement.GetProperty("arguments").EnumerateArray()
            .Select(value => value.GetString()!).Where(value => value.StartsWith("-javaagent:", StringComparison.Ordinal))
            .Select(value => value["-javaagent:".Length..].Split('=', 2)[0])
            .Select(file => new FilePin(Path.GetFileName(file), new FileInfo(file).Length, Hash(file))).ToArray();
        File.WriteAllText(Path.Combine(paths.Root, "preparation-proof.json"), JsonSerializer.Serialize(new
        {
            schemaVersion = 1, preparedAtUtc = preparation.RootElement.GetProperty("preparedAtUtc").GetDateTimeOffset(),
            minecraftVersion = descriptor.MinecraftVersion, loader = descriptor.Loader.Type.ToString(), loaderVersion = descriptor.Loader.Version,
            javaMajorVersion = descriptor.JavaMajorVersion, descriptorHash = descriptor.DescriptorHash,
            packTreeSha256 = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(tree))),
            packFileCount = packFiles.Length, packMods = packFiles.Where(file => file.Path.StartsWith("mods/", StringComparison.Ordinal)),
            actualInstanceMods = mods, javaAgents = agents, requiredLauncherFeatures = features,
            launcherFeatureGatesEnabled = features.All(supported.Contains),
            managedComponentPrepared = preparation.RootElement.GetProperty("managedComponentPrepared").GetBoolean(),
            actualCmlLibContextPrepared = launch is not null,
            launcherAssemblySha256 = launch?.RootElement.GetProperty("launcherAssemblySha256").GetString()
                ?? preparation.RootElement.GetProperty("launcherAssemblySha256").GetString(),
            sourceInstallationWritten = false, personalWorldsCopied = false,
            clientOrSteamAcceptanceClaimed = false
        }, new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true }));
    }
    private static string Hash(string path) { using var stream = File.OpenRead(path); return Convert.ToHexStringLower(SHA256.HashData(stream)); }
    private sealed record FilePin(string Path, long SizeBytes, string Sha256);
}
