using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using Minecraft;

internal static class RuntimePreparation
{
    internal static async Task Run(string[] args)
    {
        var options = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var index = 2; index < args.Length; index += 2)
        {
            if (!args[index].StartsWith("--", StringComparison.Ordinal) || index + 1 >= args.Length)
                throw new ArgumentException("Invalid preparation option.");
            options.Add(args[index][2..], args[index + 1]);
        }
        string Required(string name) => options.TryGetValue(name, out var value) ? Path.GetFullPath(value)
            : throw new ArgumentException("Missing --" + name);
        var paths = new AppPaths(Path.GetFullPath(args[1]));
        var sourcePaths = new AppPaths(Required("source-install-root"));
        var baseline = Required("baseline-cache");
        var sourcePack = Required("source-pack");
        var marker = Path.Combine(paths.Root, ".legacy-runtime-qa.json");
        var bindPath = Path.Combine(paths.Root, "private", "bind-game-pid.json");
        if (File.Exists(bindPath))
        {
            using var bind = JsonDocument.Parse(File.ReadAllText(bindPath));
            try
            {
                using var liveGame = System.Diagnostics.Process.GetProcessById(bind.RootElement.GetProperty("pid").GetInt32());
                if (!liveGame.HasExited) throw new IOException("Stop the fixture's recorded game before changing its runtime or pack files.");
            }
            catch (ArgumentException) { }
        }
        if (new[] { sourcePaths.Personal, sourcePaths.Packs, sourcePaths.Launcher, sourcePaths.Worlds, sourcePack, baseline }
            .Any(parent => Within(paths.Root, parent)))
            throw new IOException("The production QA fixture must be separate from its read-only sources.");
        if (Directory.Exists(paths.Root) && Directory.EnumerateFileSystemEntries(paths.Root).Any() && !File.Exists(marker))
            throw new IOException("An existing QA root needs the legacy fixture marker.");
        if (!File.Exists(Path.Combine(baseline, ".btw-runtime-qa.json")))
            throw new IOException("Only the independently prepared BTW baseline may seed these runtime caches.");
        var descriptor = PackManifestService.Load(sourcePack);
        if (descriptor.Loader.Type != PackLoaderKind.LegacyFabric || descriptor.MinecraftVersion != "1.6.4" || descriptor.JavaMajorVersion != 17)
            throw new IOException("This QA host is limited to the reviewed Legacy Fabric 1.6.4 Java 17 platform.");
        paths.Ensure();
        File.WriteAllText(marker, "{\"schemaVersion\":1,\"purpose\":\"Independent production runtime QA\"}");
        const string packName = "Better Than Wolves";
        var pack = paths.CombineUnderPacks(packName);
        Directory.CreateDirectory(pack);
        var copied = new List<object>();
        foreach (var name in new[] { "mods", "config", "defaultconfigs", "resourcepacks", "scripts", "kubejs", "launcher", "library" })
            CopyTree(Path.Combine(sourcePack, name), Path.Combine(pack, name), copied);
        CopyFile(Path.Combine(sourcePack, PackManifestService.ManifestFileName), Path.Combine(pack, PackManifestService.ManifestFileName), copied);
        if (descriptor.ClientJar.Length > 0)
            CopyFile(PackManifestService.ResolveClientJarPath(sourcePack, descriptor), Path.Combine(pack, descriptor.ClientJar), copied);
        CopyTree(Path.Combine(baseline, "libraries"), SharedRuntimeStore.Libraries(paths), copied);
        var linkAssets = options.GetValueOrDefault("immutable-asset-links") == "true";
        var assetPins = SeedAssetObjects(Path.Combine(baseline, "assets"), SharedRuntimeStore.Assets(paths), copied, linkAssets);
        var baseVersion = Path.Combine(SharedRuntimeStore.Versions(paths), "1.6.4");
        CopyFile(Path.Combine(baseline, "metadata", "1.6.4.json"), Path.Combine(baseVersion, "1.6.4.json"), copied);
        CopyFile(Path.Combine(baseline, "minecraft-1.6.4-client.jar"), Path.Combine(baseVersion, "1.6.4.jar"), copied);
        // A byte-for-byte independent copy. Never point the runtime service at
        // the personal JDK: its repair/sweep is allowed to mutate its own root.
        var javaRelative = Path.Combine("runtime", "windows-x64", "java-17");
        CopyTree(Path.Combine(sourcePaths.JavaRuntimes, javaRelative), Path.Combine(paths.JavaRuntimes, javaRelative), copied);
        var json = new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true };
        File.WriteAllText(Path.Combine(paths.Root, "cache-copy.json"), JsonSerializer.Serialize(new
        { copied, personalWorldsCopied = false, sourceInstallationWritten = false, copiedAtUtc = DateTimeOffset.UtcNow }, json));
        string? componentUri = null;
        string? componentJar = null;
        if (options.TryGetValue("component-index", out var indexPath))
        {
            var assembly = typeof(SteamTransportCatalog).Assembly;
            var parser = assembly.GetType("Minecraft.SteamComponentCatalog", true)!
                .GetMethod("Parse", BindingFlags.Static | BindingFlags.NonPublic)!;
            var snapshot = parser.Invoke(null, [File.ReadAllText(Path.GetFullPath(indexPath))]);
            typeof(SteamTransportCatalog).GetMethod("Activate", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [snapshot]);
            var build = SteamTransportCatalog.Find(descriptor) ?? throw new IOException("The supplied component index has no reviewed Legacy Fabric build.");
            componentJar = Required("component-jar");
            if (Hash(componentJar) != build.Sha256 || new FileInfo(componentJar).Length != build.SizeBytes)
                throw new IOException("The local QA component does not match the selected catalog pin.");
            componentUri = build.DownloadUris.Single().AbsoluteUri;
        }
        using var http = new HttpClient(new QaComponentHandler(componentUri, componentJar)) { Timeout = Timeout.InfiniteTimeSpan };
        var logger = new Logger(paths.LogFile);
        using var runtimeService = new PackRuntimeService(paths, logger, http);
        using var instances = new PackInstanceService(paths, logger);
        using var adapters = new PortableIdentityAdapterService(paths, logger);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(30));
        PreparedRuntime runtime;
        try
        {
            var previousStage = -1;
            runtime = await runtimeService.PrepareAsync(packName,
                new Progress<RuntimePreparationProgress>(progress =>
                {
                    if (Interlocked.Exchange(ref previousStage, (int)progress.Stage) != (int)progress.Stage)
                        Console.WriteLine("RUNTIME_STAGE " + progress.Stage);
                }), timeout.Token);
        }
        finally
        {
            foreach (var pin in assetPins)
                if (!File.Exists(pin.Key) || Hash(pin.Key) != pin.Value)
                    throw new IOException("A baseline immutable asset changed during production preparation.");
            File.WriteAllText(Path.Combine(paths.Root, "baseline-assets-verification.json"), JsonSerializer.Serialize(new
            { checkedObjects = assetPins.Count, sourceAssetBytesUnchanged = true }, json));
        }
        var instance = await instances.PrepareAsync(packName, timeout.Token);
        if (componentUri is not null)
            await new ManagedComponentService(paths, logger, http).EnsureSteamTransportModAsync(instance, descriptor, timeout.Token);
        var adapterArguments = await adapters.PrepareJvmArgumentsAsync(runtime, instance.GameDirectory, timeout.Token);
        if (adapterArguments.Any(argument => argument.StartsWith("-javaagent:", StringComparison.Ordinal)) ||
            !adapterArguments.Contains("-Dminecraft.portable.legacy.enabled=true"))
            throw new IOException("The legacy runtime must use its verified native module, without the modern authlib agent.");
        File.WriteAllText(Path.Combine(paths.Root, "prepared-runtime.json"), JsonSerializer.Serialize(runtime, json));
        File.WriteAllText(Path.Combine(paths.Root, "production-preparation.json"), JsonSerializer.Serialize(new
        {
            preparedAtUtc = DateTimeOffset.UtcNow, runtime.ProfileId, runtime.Descriptor,
            instance.GameDirectory, adapterArguments, managedComponentPrepared = componentUri is not null,
            sourceInstallationWritten = false, personalWorldsCopied = false, gameStarted = false,
            launcherAssemblySha256 = Hash(typeof(PackRuntimeService).Assembly.Location)
        }, json));
        Console.WriteLine("PRODUCTION_PREPARED runtime=true instance=true legacyAdapter=true managedComponent=" +
            (componentUri is not null).ToString().ToLowerInvariant());
    }

    private static void CopyTree(string source, string destination, List<object> copied)
    {
        if (!Directory.Exists(source)) return;
        foreach (var directory in Directory.EnumerateDirectories(source))
        {
            if ((File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Directory links are not accepted as QA cache roots.");
            CopyTree(directory, Path.Combine(destination, Path.GetFileName(directory)), copied);
        }
        foreach (var file in Directory.EnumerateFiles(source)) CopyFile(file, Path.Combine(destination, Path.GetFileName(file)), copied);
    }

    private static void CopyFile(string source, string destination, List<object> copied)
    {
        var hash = Hash(source);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        if (!File.Exists(destination) || Hash(destination) != hash)
        {
            if (new DriveInfo(Path.GetPathRoot(destination)!).AvailableFreeSpace < new FileInfo(source).Length + 100L * 1024 * 1024)
                throw new IOException("QA copying paused to keep at least 100 MiB free on the workspace drive.");
            File.Copy(source, destination, true);
        }
        if (Hash(source) != hash || Hash(destination) != hash) throw new IOException("A cache/source file changed while being copied.");
        copied.Add(new { path = destination, sha256 = hash, sizeBytes = new FileInfo(destination).Length });
    }
    private static Dictionary<string, string> SeedAssetObjects(string source, string destination, List<object> copied, bool linkAssets)
    {
        var pins = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        CopyTree(Path.Combine(source, "indexes"), Path.Combine(destination, "indexes"), copied);
        if (!linkAssets) CopyTree(Path.Combine(source, "virtual"), Path.Combine(destination, "virtual"), copied);
        var objects = Path.Combine(source, "objects");
        foreach (var file in Directory.EnumerateFiles(objects, "*", SearchOption.AllDirectories))
        {
            using var stream = File.OpenRead(file);
            var sha1 = Convert.ToHexStringLower(SHA1.HashData(stream));
            if (sha1 != Path.GetFileName(file) || Path.GetFileName(Path.GetDirectoryName(file)) != sha1[..2])
                throw new IOException("A baseline asset object does not match its Mojang content address.");
            var hash = Hash(file);
            pins.Add(file, hash);
            var target = Path.Combine(destination, "objects", Path.GetRelativePath(objects, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            if (!File.Exists(target))
            {
                if (linkAssets && !CreateHardLink(target, file, IntPtr.Zero))
                    throw new IOException("The independent QA asset cache could not create its authorized immutable link.");
                if (!linkAssets) CopyFile(file, target, copied);
            }
            if (Hash(target) != hash) throw new IOException("A QA asset object differs from the verified baseline.");
        }
        copied.Add(new { immutableBaselineAssetLinks = linkAssets ? pins.Count : 0, sourceObjectHashesVerified = true });
        return pins;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateHardLink(string newFileName, string existingFileName, IntPtr securityAttributes);
    private static bool Within(string path, string parent) => Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar)
        .StartsWith(Path.GetFullPath(parent).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
        || Path.GetFullPath(path).Equals(Path.GetFullPath(parent), StringComparison.OrdinalIgnoreCase);
    private static string Hash(string path) { using var stream = File.OpenRead(path); return Convert.ToHexStringLower(SHA256.HashData(stream)); }

    private sealed class QaComponentHandler(string? componentUri, string? localFile) : DelegatingHandler(new HttpClientHandler())
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) =>
            componentUri is not null && request.RequestUri!.AbsoluteUri == componentUri
                ? Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(File.ReadAllBytes(localFile!)) })
                : base.SendAsync(request, token);
    }
}
