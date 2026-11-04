using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Minecraft;

if (args.Length >= 2 && args[0] == "world-cycle")
{
    WorldCycle.Run(args);
    return;
}

if (args.Length == 2 && args[0] == "safe-proof")
{
    PublicPreparationProof.Write(new AppPaths(Path.GetFullPath(args[1])));
    Console.WriteLine("SAFE_PREPARATION_PROOF_WRITTEN");
    return;
}
if (args.Length >= 2 && args[0] == "launch-context")
{
    await LaunchContextExport.Run(args);
    return;
}
if (args.Length >= 2 && args[0] == "prepare")
{
    await RuntimePreparation.Run(args);
    return;
}
if (args.Length < 2 || args[0] != "identity")
    throw new ArgumentException("Usage: identity <qa-root> --source-install-root <installation> --private-root <private-evidence> [--hold]");
var options = new Dictionary<string, string>(StringComparer.Ordinal);
for (var index = 2; index < args.Length; index++)
{
    if (args[index] == "--hold") { options["hold"] = "true"; continue; }
    if (!args[index].StartsWith("--", StringComparison.Ordinal) || index + 1 >= args.Length)
        throw new ArgumentException("Invalid QA option.");
    options.Add(args[index][2..], args[++index]);
}
var root = Path.GetFullPath(args[1]);
var source = new AppPaths(Path.GetFullPath(Required("source-install-root")));
var privateRoot = Path.GetFullPath(Required("private-root"));
foreach (var forbidden in new[] { source.Personal, source.Packs, source.Launcher, source.Worlds })
    if (Within(root, forbidden) || Within(privateRoot, forbidden))
        throw new IOException("QA outputs must not be placed in the source installation's managed directories.");
var paths = new AppPaths(root);
paths.Ensure();
Directory.CreateDirectory(privateRoot);
var json = new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true, PropertyNameCaseInsensitive = true };
File.WriteAllText(Path.Combine(root, ".legacy-runtime-qa.json"), JsonSerializer.Serialize(new
{ schemaVersion = 1, purpose = "Independent legacy runtime QA; no personal worlds copied", createdAtUtc = DateTimeOffset.UtcNow }, json));
var sourceSettingsHash = Hash(source.SettingsFile);
var settings = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(source.SettingsFile), json)
    ?? throw new InvalidDataException("Source settings are empty.");
if (LocalIdentityService.NormalizedOrNothing(settings.PlayerName).Length == 0)
    throw new InvalidDataException("The source installation has no selected player name.");
var logger = new Logger(Path.Combine(privateRoot, "identity-host.log"));
await using var steam = new SteamClientService(new SteamworksApiFacade(), new SteamNativeLibraryService(paths, logger), logger);
var status = await steam.StartAsync();
if (!status.IsReady) throw new InvalidOperationException("A real signed-in Steam session is required: " + status.Availability);
var identityService = new SteamIdentityService(new SteamClientUserSource(steam), logger);
if (!identityService.Bind().Bound) throw new InvalidOperationException("The production Steam identity could not bind.");
var identity = identityService.ResolveContext(settings);
var legacy = new LegacyIdentityRegistryService(paths, logger);
var legacyRegistry = legacy.Prepare(identity);
if (!File.Exists(legacyRegistry)) throw new IOException("Production legacy registry was not written.");
var privateLegacyRegistry = Path.Combine(privateRoot, "legacy-identity-profiles.properties");
File.Copy(legacyRegistry, privateLegacyRegistry, true);
if (File.Exists(source.IdentityRegistryFile))
    File.Copy(source.IdentityRegistryFile, Path.Combine(privateRoot, "source-identity-profiles.properties"), true);
if (File.Exists(source.SkinRegistryFile))
    File.Copy(source.SkinRegistryFile, Path.Combine(privateRoot, "source-skin-profiles.properties"), true);
await using var skin = new SkinService(paths, logger, new NullPeerTransport());
string? selectedSkinHash = null;
string? selectedSkinModel = null;
if (!string.IsNullOrWhiteSpace(settings.SkinPath))
{
    var selectedPath = Path.IsPathRooted(settings.SkinPath) ? settings.SkinPath : Path.Combine(source.Root, settings.SkinPath);
    if (!File.Exists(selectedPath)) throw new FileNotFoundException("The selected source skin is unavailable.");
    selectedSkinHash = Hash(selectedPath);
    var copiedSkin = Path.Combine(privateRoot, "selected-skin.png");
    File.Copy(selectedPath, copiedSkin, true);
    if (Hash(selectedPath) != selectedSkinHash || Hash(copiedSkin) != selectedSkinHash)
        throw new IOException("The selected skin changed while being copied.");
    settings.SkinPath = copiedSkin;
    await skin.StartAsync();
    skin.PrepareRegistry(settings, identity);
    var announcement = skin.GetAnnouncement(settings, identity.MinecraftUuid);
    if (!announcement.IsAvailable || !string.Equals(announcement.Sha256, selectedSkinHash, StringComparison.OrdinalIgnoreCase))
        throw new IOException("The production skin service did not accept the selected skin.");
    selectedSkinModel = announcement.Model;
    File.Copy(paths.SkinRegistryFile, Path.Combine(privateRoot, "skin-profiles.properties"), true);
}
else
    File.WriteAllText(Path.Combine(privateRoot, "skin-profiles.properties"), "");
if (Hash(source.SettingsFile) != sourceSettingsHash) throw new IOException("Source settings changed during the read-only snapshot.");
var nativeName = LegacyPlayerIdentity.NativeName(identity.SteamId64);
var jvmProperties = new Dictionary<string, string>(StringComparer.Ordinal)
{
    ["minecraft.portable.legacy.enabled"] = "true",
    ["minecraft.portable.legacy.registry"] = privateLegacyRegistry,
    ["minecraft.portable.skin.registry"] = Path.Combine(privateRoot, "skin-profiles.properties"),
    ["minecraft.portable.identity.localPlayer"] = identity.MinecraftUuid,
    ["minecraft.portable.identity.windowTitleText"] = "Better Than Wolves"
};
File.WriteAllText(Path.Combine(privateRoot, "context.json"), JsonSerializer.Serialize(new
{
    schemaVersion = 1, boundFromLiveSteam = true, canonicalUuid = identity.MinecraftUuid, nativeName,
    steamId64 = identity.SteamId64.ToString(), displayNameBase64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(identity.IdentityName)),
    sessionAccessToken = identity.SessionAccessToken, jvmProperties,
    selectedSkinHash, selectedSkinModel, sourceSettingsWritten = false, personalWorldsCopied = false
}, json));
File.WriteAllText(Path.Combine(privateRoot, "ready.json"), JsonSerializer.Serialize(new
{ ready = true, pid = Environment.ProcessId, liveSteamBound = true, selectedSkinPrepared = selectedSkinHash is not null }, json));
Console.WriteLine("IDENTITY_READY liveSteamBound=true selectedSkinPrepared=" + (selectedSkinHash is not null).ToString().ToLowerInvariant());
if (options.ContainsKey("hold"))
    while (!File.Exists(Path.Combine(privateRoot, "stop-host"))) await Task.Delay(250);

string Required(string key) => options.TryGetValue(key, out var value) ? value : throw new ArgumentException("Missing --" + key);
static bool Within(string candidate, string parent) => Path.GetFullPath(candidate).TrimEnd(Path.DirectorySeparatorChar)
    .StartsWith(Path.GetFullPath(parent).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
    || Path.GetFullPath(candidate).Equals(Path.GetFullPath(parent), StringComparison.OrdinalIgnoreCase);
static string Hash(string file) { using var stream = File.OpenRead(file); return Convert.ToHexStringLower(SHA256.HashData(stream)); }
