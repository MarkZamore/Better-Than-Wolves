using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using Minecraft;

internal static class LaunchContextExport
{
    internal static async Task Run(string[] args)
    {
        var options = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var index = 2; index < args.Length; index += 2)
        {
            if (!args[index].StartsWith("--", StringComparison.Ordinal) || index + 1 >= args.Length)
                throw new ArgumentException("Invalid launch context option.");
            options.Add(args[index][2..], args[index + 1]);
        }
        var paths = new AppPaths(Path.GetFullPath(args[1]));
        if (!File.Exists(Path.Combine(paths.Root, ".legacy-runtime-qa.json")))
            throw new IOException("An independently prepared production fixture is required.");
        var json = new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true, PropertyNameCaseInsensitive = true };
        var runtime = JsonSerializer.Deserialize<PreparedRuntime>(File.ReadAllText(Path.Combine(paths.Root, "prepared-runtime.json")), json)
            ?? throw new IOException("The prepared runtime is missing.");
        using var preparation = JsonDocument.Parse(File.ReadAllText(Path.Combine(paths.Root, "production-preparation.json")));
        if (!preparation.RootElement.GetProperty("managedComponentPrepared").GetBoolean())
            throw new IOException("Production launch context requires the selected Steam component to have been prepared.");
        if (!options.TryGetValue("identity-context", out var identityPath)) throw new ArgumentException("Missing --identity-context.");
        identityPath = Path.GetFullPath(identityPath);
        using var context = JsonDocument.Parse(File.ReadAllText(identityPath));
        if (!context.RootElement.GetProperty("boundFromLiveSteam").GetBoolean())
            throw new IOException("The input identity must have been bound from live Steam.");
        var privateRoot = Path.Combine(paths.Root, "private");
        Directory.CreateDirectory(privateRoot);
        var logger = new Logger(Path.Combine(privateRoot, "launch-context-host.log"));
        await using var steam = new SteamClientService(new SteamworksApiFacade(), new SteamNativeLibraryService(paths, logger), logger);
        if (!(await steam.StartAsync()).IsReady) throw new IOException("Live Steam is required when exporting production launch context.");
        var identityService = new SteamIdentityService(new SteamClientUserSource(steam), logger);
        if (!identityService.Bind().Bound) throw new IOException("Production identity binding failed.");
        var displayName = Encoding.UTF8.GetString(Convert.FromBase64String(context.RootElement.GetProperty("displayNameBase64").GetString()!));
        var settings = new AppSettings { PlayerName = displayName };
        var identity = identityService.ResolveContext(settings);
        var nativeName = LegacyPlayerIdentity.NativeName(identity.SteamId64);
        if (identity.MinecraftUuid != context.RootElement.GetProperty("canonicalUuid").GetString() ||
            nativeName != context.RootElement.GetProperty("nativeName").GetString() ||
            identity.SteamId64.ToString() != context.RootElement.GetProperty("steamId64").GetString())
            throw new IOException("The live account differs from the private identity fixture.");
        new LegacyIdentityRegistryService(paths, logger).Prepare(identity);
        new PortableIdentityRegistryService(paths, logger).Prepare(identity);
        var skinSource = context.RootElement.GetProperty("jvmProperties").GetProperty("minecraft.portable.skin.registry").GetString()!;
        File.Copy(skinSource, paths.SkinRegistryFile, true);
        var skinLine = File.ReadAllLines(paths.SkinRegistryFile).Single(line => line.StartsWith(identity.MinecraftUuid + "|", StringComparison.OrdinalIgnoreCase));
        var skinFields = skinLine.Split('|', 4);
        if (!Uri.TryCreate(skinFields[3], UriKind.Absolute, out var skinUri) || skinUri.Scheme != "http" || !skinUri.IsLoopback)
            throw new IOException("The selected skin must be served by the private production skin endpoint.");
        using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) })
        {
            var bytes = await http.GetByteArrayAsync(skinUri);
            if (!Convert.ToHexStringLower(SHA256.HashData(bytes)).Equals(skinFields[1], StringComparison.OrdinalIgnoreCase))
                throw new IOException("The private skin endpoint differs from its production registry pin.");
        }
        const string packName = "Better Than Wolves";
        var pack = paths.CombineUnderPacks(packName);
        var game = paths.CombineUnderInstances(packName);
        using var runtimes = new PackRuntimeService(paths, logger);
        using var adapter = new PortableIdentityAdapterService(paths, logger);
        var launcher = runtimes.CreateLocalLauncher(runtime);
        var profile = await launcher.GetVersionAsync(runtime.ProfileId, default);
        var launchPath = new MinecraftPath(game)
        {
            Library = launcher.MinecraftPath.Library, Versions = launcher.MinecraftPath.Versions,
            Assets = launcher.MinecraftPath.Assets, Resource = launcher.MinecraftPath.Resource,
            Runtime = launcher.MinecraftPath.Runtime
        };
        launchPath.CreateDirs();
        var temp = Path.Combine(paths.Personal, "Temp", "Java", packName);
        var home = Path.Combine(paths.Personal, "Home");
        foreach (var directory in new[] { temp, home, Path.Combine(home, "AppData", "Roaming"), Path.Combine(home, "AppData", "Local") })
            Directory.CreateDirectory(directory);
        var javaMajor = runtime.Descriptor.JavaMajorVersion ?? 17;
        var buildBase = typeof(MinecraftProcessService).GetMethod("BuildBaseJvmArguments", BindingFlags.Static | BindingFlags.NonPublic)!;
        var jvm = (List<string>)buildBase.Invoke(null, [game, temp, home, paths.SkinRegistryFile, paths.IdentityRegistryFile, identity.MinecraftUuid, javaMajor])!;
        var heap = int.Parse(options.GetValueOrDefault("heap-mb", "2048"), CultureInfo.InvariantCulture);
        jvm.AddRange(MinecraftProcessService.HeapTuningArgumentsFor(heap));
        jvm.AddRange(MinecraftProcessService.CompatibilityArgumentsFor(javaMajor));
        var adapterArguments = await adapter.PrepareJvmArgumentsAsync(runtime, game, default);
        if (adapterArguments.Any(argument => argument.StartsWith("-javaagent:", StringComparison.Ordinal)))
            throw new IOException("Legacy Minecraft must not receive the modern authlib identity agent.");
        jvm.AddRange(adapterArguments);
        var noticeService = new GameNoticeOverlayService(paths, logger);
        var notice = typeof(GameNoticeOverlayService).GetMethod("PrepareSession", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(noticeService, null)!;
        var noticeType = notice.GetType();
        var noticeArguments = (IReadOnlyList<string>)noticeType.GetProperty("JvmArguments", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(notice)!;
        if (!noticeArguments.Any(argument => argument.StartsWith("-javaagent:", StringComparison.Ordinal)))
            throw new IOException("The production notice renderer was not prepared.");
        jvm.AddRange(noticeArguments);
        if (options.TryGetValue("qa-agent", out var qaAgent))
        {
            qaAgent = Path.GetFullPath(qaAgent);
            if (!File.Exists(qaAgent)) throw new FileNotFoundException("QA instrumentation is missing.");
            jvm.Add("-javaagent:" + qaAgent);
        }
        var session = MSession.CreateOfflineSession(nativeName);
        session.UUID = identity.MinecraftUuid;
        session.AccessToken = identity.SessionAccessToken;
        session.UserType = "mojang";
        session.Xuid = "";
        var launchOptions = new MLaunchOption
        {
            Path = launchPath, JavaPath = runtime.JavaPath, Session = session,
            MaximumRamMb = heap, MinimumRamMb = heap, GameLauncherName = "LANMinecraft", GameLauncherVersion = "1",
            VersionType = "Legacy Fabric " + runtime.Descriptor.Loader.Version, FullScreen = false,
            ScreenWidth = 960, ScreenHeight = 540, ExtraJvmArguments = jvm.Select(value => new MArgument(value)).ToList()
        };
        using var process = launcher.BuildProcess(profile, launchOptions);
        var complete = typeof(MinecraftProcessService).GetMethod("CompleteJvmArguments", BindingFlags.Static | BindingFlags.NonPublic)!;
        var final = (IReadOnlyList<string>)complete.Invoke(null, [process.StartInfo, profile, pack, new PackJvmArgumentsService(logger)])!;
        var configure = typeof(MinecraftProcessService).GetMethod("ConfigureChildEnvironment", BindingFlags.Static | BindingFlags.NonPublic)!;
        configure.Invoke(null, [process.StartInfo.Environment, temp, home]);
        var environment = new Dictionary<string, string?>();
        foreach (var key in new[] { "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA", "SteamAppId", "SteamGameId" })
            if (process.StartInfo.Environment.TryGetValue(key, out var value)) environment[key] = value;
        var nativeDirectory = final.Single(value => value.StartsWith("-Djava.library.path=", StringComparison.Ordinal))["-Djava.library.path=".Length..];
        if (!Directory.Exists(nativeDirectory) || !Directory.EnumerateFiles(nativeDirectory, "*lwjgl*.dll").Any())
            throw new IOException("The actual CmlLib launch points at missing LWJGL natives.");
        var noticeDirectory = (string)noticeType.GetProperty("Directory", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(notice)!;
        var bindPath = Path.Combine(privateRoot, "bind-game-pid.json");
        var output = Path.Combine(privateRoot, "launch-context.json");
        File.WriteAllText(output, JsonSerializer.Serialize(new
        {
            schemaVersion = 1, java = runtime.JavaPath, arguments = final, workingDirectory = game, environment,
            removeEnvironmentKeys = new[] { SteamworksApiFacade.NoOverlayVariable }, runtime.ProfileId, runtime.Descriptor,
            canonicalUuid = identity.MinecraftUuid, nativeName, noticeDirectory, bindProcessRequestPath = bindPath,
            noticeRendererPrepared = true, modernIdentityAgentAbsent = true, liveAccountRevalidated = true,
            selectedSkinEndpointVerified = true, actualCmlLibNativeDirectoryVerified = true,
            launcherAssemblySha256 = Hash(typeof(PackRuntimeService).Assembly.Location), gameStarted = false
        }, json));
        File.WriteAllText(Path.Combine(privateRoot, "launch-context-ready.json"), JsonSerializer.Serialize(new
        { ready = true, hostPid = Environment.ProcessId, actualProductionLaunchContext = true }, json));
        Console.WriteLine("LAUNCH_CONTEXT_READY actualProduction=true noticeRenderer=true liveAccount=true");
        if (options.GetValueOrDefault("hold") == "true")
        {
            var bound = false;
            while (!File.Exists(Path.Combine(privateRoot, "stop-context-host")))
            {
                if (!bound && File.Exists(bindPath))
                {
                    using var bind = JsonDocument.Parse(File.ReadAllText(bindPath));
                    using var child = Process.GetProcessById(bind.RootElement.GetProperty("pid").GetInt32());
                    noticeType.GetMethod("BindProcess", BindingFlags.Instance | BindingFlags.NonPublic, [typeof(Process)])!.Invoke(notice, [child]);
                    bound = true;
                }
                await Task.Delay(200);
            }
        }
        ((IDisposable)notice).Dispose();
    }
    private static string Hash(string path) { using var stream = File.OpenRead(path); return Convert.ToHexStringLower(SHA256.HashData(stream)); }
}
