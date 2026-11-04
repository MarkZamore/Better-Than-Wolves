using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Minecraft;

internal static class WorldCycle
{
    internal static void Run(string[] args)
    {
        var options = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var index = 2; index < args.Length; index += 2)
        {
            if (index + 1 >= args.Length || !args[index].StartsWith("--", StringComparison.Ordinal))
                throw new ArgumentException("Invalid world-cycle option.");
            options.Add(args[index][2..], args[index + 1]);
        }
        var paths = new AppPaths(Path.GetFullPath(args[1]));
        if (!File.Exists(Path.Combine(paths.Root, ".legacy-runtime-qa.json")))
            throw new IOException("An independently prepared QA root is required.");
        var evidence = Path.GetFullPath(options["evidence"]);
        paths.EnsureUnderRoot(evidence);
        using var exit = JsonDocument.Parse(File.ReadAllText(Path.Combine(evidence, "exit.json")));
        if (exit.RootElement.GetProperty("exitCode").GetInt32() != 0 ||
            !exit.RootElement.GetProperty("productionCmlLibArgumentsUsed").GetBoolean())
            throw new IOException("A normally closed production-context game is required.");
        using var processProof = JsonDocument.Parse(File.ReadAllText(Path.Combine(evidence, "process.json")));
        try
        {
            using var gameProcess = Process.GetProcessById(processProof.RootElement.GetProperty("pid").GetInt32());
            if (!gameProcess.HasExited) throw new IOException("The recorded game process is still running.");
        }
        catch (ArgumentException) { }
        if (!File.ReadAllText(Path.Combine(evidence, "events.tsv")).Contains("native_save_quit_requested", StringComparison.Ordinal))
            throw new IOException("The native authored save-and-quit path was not observed.");
        using var launch = JsonDocument.Parse(File.ReadAllText(options["launch-context"]));
        var game = Path.GetFullPath(launch.RootElement.GetProperty("workingDirectory").GetString()!);
        paths.EnsureUnderRoot(game);
        var build = Path.GetRelativePath(paths.Instances, game);
        if (build.Contains(Path.DirectorySeparatorChar) || build is "." or "..")
            throw new IOException("The launch context is not a single prepared instance.");
        using var saved = JsonDocument.Parse(File.ReadAllText(options["identity-context"]));
        if (!saved.RootElement.GetProperty("boundFromLiveSteam").GetBoolean() ||
            !SteamId64.TryParse(saved.RootElement.GetProperty("steamId64").GetString(), out var steamId))
            throw new IOException("The captured identity must originate from a real Steam binding.");
        var uuid = Guid.Parse(saved.RootElement.GetProperty("canonicalUuid").GetString()!);
        var identity = new LocalIdentityContext
        {
            IdentityName = Encoding.UTF8.GetString(Convert.FromBase64String(saved.RootElement.GetProperty("displayNameBase64").GetString()!)),
            MinecraftUuid = uuid.ToString("D"), PlayerUuid = uuid, SteamId64 = steamId
        };
        if (LegacyPlayerIdentity.PortableUuid(steamId) != uuid ||
            LegacyPlayerIdentity.NativeName(steamId) != launch.RootElement.GetProperty("nativeName").GetString())
            throw new IOException("The captured account and launch context do not match.");
        var pack = paths.CombineUnderPacks(build);
        var descriptor = PackManifestService.Load(pack);
        var logger = new Logger(Path.Combine(paths.Root, "private", "world-cycle.log"));
        var sessionType = typeof(MinecraftProcessService).Assembly.GetType("Minecraft.WorldLaunchSession", true)!;
        var flags = BindingFlags.Static | BindingFlags.NonPublic;
        var beforeProcessStart = new DateTimeOffset(File.GetLastWriteTimeUtc(Path.Combine(evidence, "launch-private.json")));
        var session = sessionType.GetMethod("Capture", flags)!.Invoke(null,
            [pack, build, game, "", descriptor, identity, beforeProcessStart]);
        sessionType.GetMethod("StampClosedWorlds", flags)!.Invoke(null, [paths, session, logger]);
        new WorldPlayerProfileService(paths, logger).PrepareWorldsForLaunch(paths.Worlds, identity, build, descriptor);
        // This is the next-launch stage used by MinecraftProcessService after
        // adoption; recreate its build-scoped saves link using the real service.
        new SavesFolderService(logger).Prepare(paths.Worlds, game, build);
        var worlds = Directory.GetDirectories(WorldLocations.ForBuild(paths.Worlds, build));
        var results = worlds.Select(world =>
        {
            var metadata = new WorldMetadataService().Read(world) ?? throw new IOException("Native world was not stamped.");
            var profile = Path.Combine(world, "players", LegacyPlayerIdentity.NativeName(steamId) + ".dat");
            if (metadata.PlayerStorage != "legacy-name-v1" || metadata.LegacyHostSteamId64 != steamId.ToString() || !File.Exists(profile))
                throw new IOException("The closed native world did not retain its captured owner/profile.");
            using var input = File.OpenRead(profile);
            return new { legacyStorage = true, capturedOwnerPreserved = true,
                profileSha256 = Convert.ToHexStringLower(SHA256.HashData(input)) };
        }).ToArray();
        if (results.Length == 0) throw new IOException("No native QA world was adopted.");
        File.WriteAllText(Path.Combine(evidence, "world-cycle.json"), JsonSerializer.Serialize(new
        { status = "PASS", actualClosedWorldServicesUsed = true, nextLaunchSavesPrepared = true, worldCount = results.Length, worlds = results,
            twoAccountHostHandover = "NOT_RUN", globalClientStatisticsTransferred = false }, new JsonSerializerOptions { WriteIndented = true }));
        Console.WriteLine("WORLD_CYCLE_PASS capturedLaunchOwner=true canonicalProfiles=true");
    }
}
