# Legacy runtime QA host

This tool operates independent fixtures. It never copies personal worlds or calls `SettingsService.Load` against the source installation, because loading settings can migrate them. Source settings and the selected PNG are read and verified unchanged; the live account is bound using production `SteamClientService`, `SteamClientUserSource` and `SteamIdentityService`. Names, IDs, tokens, selected skin and raw logs remain private evidence.

The host belongs to this pack's [runtime acceptance stand](../README.md), rather than the launcher repository. It calls the production services through an explicit external project reference; no implementation of those services is copied here. It requires Windows and .NET 10 SDK, plus a compatible launcher source checkout/build.

Build from this pack's root, passing the absolute path to the external launcher's `Program/Minecraft.csproj`:

```text
dotnet build tools/runtime-qa/host/LegacyRuntimeQa.csproj -p:LauncherProject=ABSOLUTE_LAUNCHER_PROJECT -p:SkipPortableIdentityAdapter=true -p:BuildProjectReferences=false -p:QaOutputPath=ABSOLUTE_ARTIFACTS_HOST_OUTPUT
```

`LauncherProject` has no machine-specific default. Build the compatible launcher first according to its own instructions, then keep `BuildProjectReferences=false` to reuse that build without replacing shared Java-agent outputs. Omitting that option builds the explicitly selected launcher project. `QaOutputPath` should select an independent directory under `artifacts`; this also avoids replacing an executable holding the skin endpoint open. `Run-Identity.ps1 -HostPath <built-executable>` selects that output explicitly. Each private context records the actual launcher assembly SHA256, so a source/project reference alone is not a claim about a tested executable.

## Actual account and selected skin

`identity <qa-root> --source-install-root <source-installation-root> --private-root <private-directory> --hold` binds the account signed in to Steam. It emits the production legacy registry, an independently served production skin registry, and `context.json` with `canonicalUuid`, `nativeName`, `displayNameBase64` and `jvmProperties`. It does not fabricate a second account or a peer identity. Source registries are copied separately as private provenance.

Launch this command through `Run-Identity.ps1`, which hides its window and redirects both streams to private files. Steam's native SDK writes the account ID directly to stdout; a bare invocation can expose it even when managed logging is private. The script prints only readiness, process ID and whether the selected skin was prepared. Keep the host alive while the game consumes its skin URL. Create `<private-directory>/stop-host` to stop it.

## Production preparation

`prepare <qa-root> --source-install-root <source-installation-root> --source-pack <prepared-pack-source> --baseline-cache <marked-native-baseline>` copies pack content, baseline libraries/assets/version metadata and the required pinned Java 17 into its own roots. All cache files are independent copies by default. Each copied file is verified against both source and destination hashes. Directory links are rejected. An explicit `--immutable-asset-links true` permits only immutable Mojang object blobs from the independent baseline to use authorized NTFS hardlinks; named virtual aliases are then left to the production installer. In both modes, each object's SHA1 content address and SHA256 are verified before preparation and source SHA256 is checked again afterward. No personal JDK, pack, world or config is shared through an external link. Copying stops before workspace free space falls below 100 MiB. The input baseline needs `.btw-runtime-qa.json`; a nonempty destination needs this host's `.legacy-runtime-qa.json` marker. A recorded live game blocks further preparation.

The command runs production `PackRuntimeService.PrepareAsync`, `PackInstanceService.PrepareAsync` and `PortableIdentityAdapterService.PrepareJvmArgumentsAsync`. Add `--component-index <complete-component-index> --component-jar <local-built-JAR>` to select the validated frozen local catalog and run production `ManagedComponentService`; only the exact selected download URI is served from the local JAR, whose size/SHA256 must match the catalog. Other HTTP requests use their real hosts. This proves installation behavior before immutable release publication, not remote release availability.

Outputs are `prepared-runtime.json`, `production-preparation.json` and exact cache-copy evidence. Preparation requires the legacy branch and refuses any modern authlib Java agent. It does not start the game or claim full client, UI, two-account or Steam relay success.

## Closed native world cycle

`world-cycle <qa-root> --identity-context <captured-private-identity> --launch-context <private-launch-context> --evidence <finished-native-run>` requires an owned fixture, a stopped process, exit 0 and the native save/quit receipt. It uses the original captured account and pre-launch evidence timestamp with the actual `WorldLaunchSession`, then runs adoption, world stamping, profile preparation and next-launch `SavesFolderService.Prepare`. The emitted `world-cycle.json` contains only sanitized checks and hashes. A second Steam account and cross-machine statistics transfer are not claimed by this local cycle.

## Exact production launch context

`launch-context <qa-root> --identity-context <private-live-context> --hold true` revalidates the real account against live Steam, prepares the production identity registries, verifies the selected skin endpoint and uses CmlLib `BuildProcess` followed by production `CompleteJvmArguments`. It includes the real notice renderer and checks that the resulting native directory actually contains LWJGL DLLs. The optional `--qa-agent <instrumentation-JAR>` is inserted before the inherited main class. The explicit QA heap is 2048 MiB by default (`--heap-mb` overrides it), with a 960×540 window; neither is a change to distributed pack settings.

Run this process hidden with both streams redirected into the fixture's private directory, as with identity preparation. `private/launch-context.json` contains the exact Java argument array, working directory and portable environment overrides, including session material which must not be published. A consumer starts that exact command, removes `removeEnvironmentKeys`, and writes `{ "pid": <game-process-id> }` to `bindProcessRequestPath`; the held host then binds the real notice session to the game. Stop the host with `private/stop-context-host` after the game has ended. The earlier identity host must stay alive while its skin URL is used.

`safe-proof <qa-root>` produces `preparation-proof.json`, a public-safe summary of platform, descriptor, pack/instance/agent hashes and required launcher feature names. It includes no player IDs, session tokens or filesystem paths and makes no client/Steam acceptance claim. Generate it again after the final verified pack snapshot; it does not replace the native client's own evidence.
