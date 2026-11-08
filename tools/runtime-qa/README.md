# Native Better Than Wolves runtime QA

## Source publication checks

The pack's Python suite contains 54 declared test methods, including 11 library
checks for Minecraft 1.6.4 / Legacy Fabric 0.18.2. Its synthetic JARs use Fabric
metadata and registered nested JARs. The standalone-library checks retain the
current loader, deterministic Windows/Linux ZIPs, project-page validation,
actual-inventory hash binding and stale-JAR rejection.

Pack-local copies of filtered-addition/group presentation and Forge-only
component tests were removed because this pack does not use those policies.
Their full generic coverage remains in the launcher's
[`tools/test_pack_library.py`](https://github.com/MarkZamore/LANMinecraft/blob/1b728eb7359e05af1939ac022fbb7f3f2d312be0/tools/test_pack_library.py).
The Legacy Fabric metadata case no longer repeats the unrelated Fabric/Quilt
branches. Update/restore, bridge identity, source/path/hash, delivery receipts
and all native acceptance probes remain separate necessary checks.

This reduction was a static source audit only. No tests, native probes, builds
or publication validators were rerun for it; it establishes no new runtime PASS.

These tools operate only on marked, independent fixtures below `artifacts`.
They do not copy or open personal worlds. Java instrumentation is QA-only and
must never enter pack payloads or active distribution locks.

The C# production preparation/account/launch-context host is owned here too:
[host/README.md](host/README.md). It uses an explicit external `LauncherProject`
reference to a compatible launcher checkout; the production runtime, instance,
Steam, profile and save services remain implemented in the launcher. Build with
Windows/.NET 10 SDK and choose host outputs under `artifacts`. Pack-specific
platform guards, fixture markers, inputs and acceptance scenarios live only in
this repository.

Prepare the official Minecraft 1.6.4 and Legacy Fabric 0.18.2 libraries/assets:

```text
python tools/runtime-qa/prepare_runtime.py --source . --fixture ../qa/upstream --java PATH_TO_JAVA17 --cache PATH_TO_EXISTING_SHARED_RUNTIME
python tools/runtime-qa/run_native.py ../qa/upstream --mode fresh --label baseline-create-01 --jdk PATH_TO_JDK17_OR_NEWER
python tools/runtime-qa/run_native.py ../qa/upstream --mode reenter --label baseline-reenter-01 --jdk PATH_TO_JDK17_OR_NEWER
python tools/runtime-qa/run_server.py ../qa/upstream --label baseline-server-01
python tools/runtime-qa/audit_logs.py ../qa/upstream/evidence/baseline-create-01
```

The prepared runtime records source mod SHA256 and every downloaded library.
Mojang assets and artifacts use their official metadata hashes. Existing caches
are read only; reused content is verified before linking into the fixture.

The native client agent dispatches on the actual Minecraft client tick. Fresh
world creation uses the real author-modified create-world menu with its default
settings. Exit uses the actual enabled pause-menu quit button and then normal
game shutdown. A disabled Nightmare quit gate is respected and retried while
the world runs. Reentry uses the native existing-world launch method.

For the offline LAN UI probe, include the exact candidate e4steam and pack
bridge JARs, set fixture `options.txt` to `lang:ru_RU`, and add
`--property btw.qa.lanGuestMode=true` to a fresh native run. The actual
create-world menu selects Creative. Use `--mode reenter` with that saved world
to repeat the checks after shutdown. Supply a fixture-only legacy registry row
and matching `--username` canonical name plus
`minecraft.portable.identity.localPlayer` and `minecraft.portable.legacy.registry`
properties. The transport still checks the normal local-owner and memory admission
proof. The probe verifies Creative and Survival
arguments at the native publication call and checks the Russian chat message
and report notice through their real screens. It substitutes a dummy port,
suppresses Steam bootstrap and changes the publish flag only for the report
notice check. It exercises the transformed client without opening network
listeners, contacting Steam or testing a real guest login.

Extended probes add a server-tick dispatcher, counters in the real player/font
renderers, and a forwarding observer at the native corpse-rotation call. These
are QA instrumentation, not shipped transformations. Original GL calls and
author rules still execute. The compiled agent is deterministic and targets
Java 17; its hash is recorded with each run.

`loaded-mods.txt` is a real FabricLoader snapshot. GPU maximum texture size is
queried on the render thread. These observations do not certify a screenshot,
skin appearance, Steam authentication, or two-account Steam P2P.

The dedicated fixture binds only loopback, has one private test slot and uses a
fresh disposable world. Its local offline mode belongs only to this QA process.
Native `Done`, save/stop and exit are recorded separately from the full client.
The server is auxiliary QA, not a required production role for any player.

Raw local paths and logs remain under `artifacts`; only bounded, reviewed
results should be summarized in `TESTING-STATUS.md`.

## Exact production launch and retained progress

`run_production.py` consumes the private context from this stand's
`host/LegacyRuntimeQa` exporter. It preserves the actual CmlLib arguments,
selected Java, environment changes and production Notice agent. It inserts
only the QA agent/properties before the main class and submits the real game
PID to the exporter's Notice binding request. Arguments and account values are
never printed.

```text
python tools/runtime-qa/run_production.py --context PRIVATE_CONTEXT_JSON --fixture QA_ROOT --label production-fresh --mode fresh --world BtwQaProduction --jdk JDK_ROOT --asm ASM_9_9_JAR --selected-png PRIVATE_SELECTED_PNG --extended --progress seed
```

After native save/quit and exit, use the actual launcher world lifecycle:
capture/stamp the closed session, `SavesFolderService.Adopt`,
`WorldMetadataService.StampPlayedWorlds`, selected-build
`WorldPlayerProfileService.PrepareWorldsForLaunch`, and the next launch's
`SavesFolderService.Prepare`. A fresh Notice context is required for each game
PID. The reentry guard intentionally refuses if adoption happened without
preparing the new build saves link.

The `seed` action runs on the real server thread. It gives the private native
player seven named sticks and 17 XP and spawns one normally tamed wolf with
the canonical owner name. `verify` checks the actual native inventory, XP,
tame state and owner lookup after save/reentry and a private display-name
change. These are disposable fixtures, not modifications to personal worlds.

`--name-mechanics` additionally exercises actual named-player behavior. It
temporarily changes only the private registry, invokes native base death on a
non-admitted shadow of the fixture's own profile, and compares Notch's red
apple drop with a control. The real host is not killed. Native inventory
rendering checks Dinnerbone/Grumm rotation and deadmau5 ear calls while the
canonical username and Session remain unchanged. Added shadow drops are
removed; registry bytes are restored on completion or probe failure.

## Evidence boundaries

### Reproducing missing legacy sounds

`run_audio_probe.py` runs the marked native fixture at its real title screen,
without opening worlds or invoking Steam. It coordinates native playback with
the process-only capture tool; see `python tools/runtime-qa/run_audio_probe.py
--help`. `--omit-assets-dir` is an explicit negative control reproducing the
native default asset path. The normal case passes the prepared virtual legacy
asset folder. This contrast does not validate production CmlLib arguments: the
launcher must separately test its actual inherited profile argument builder.

The native probe registers six effect requests at 25% volume and then invokes
native music playback after setting only its independent QA music cooldown to
zero. It restores the QA music volume before normal shutdown. It does not test
block physics, natural music timing, hostile mobs, or sounds of a remote player.
`audio-all-pools.tsv` records effects/stream/music pools. The analyzer compares
native event timestamps and captured PCM; one-LSB PCM16 quantization dither is
reported as a noise floor, not a successful sound. Music-only analysis excludes
the maximum indexed rain duration plus a conservative tail allowance.

On 8 November, the production Java command lacked inherited game arguments and
read `Instances/Better Than Wolves/assets`, which was absent. The exact indexed
objects and virtual files were intact. A direct native fixture with explicit
`--assetsDir` had masked this production-path defect in earlier acceptance.
Read-only inspection and the negative control now distinguish both paths.

For the final single-difficulty/Creative-command probe, first create an explicit
offline synthetic identity in the owned fixture:

```text
python -B tools/runtime-qa/prepare_synthetic_identity.py <fixture>
```

Use its `nativeName` with `run_native.py --username`, and pass its registry path
as `--property minecraft.portable.legacy.registry=...` and its `localUuid` as
`--property minecraft.portable.identity.localPlayer=...`. Add
`--property btw.qa.lanGuestMode=true --property btw.qa.hiddenWindow=true`.
The hidden-window option uses an unshown non-focusable AWT parent, not desktop
input automation. Only the isolated probe checks the single-difficulty policy;
the two-author-JAR baseline keeps its authored defaults. The synthetic IDs are
explicit fixture data, never existing people or authenticated Steam accounts.

The same isolated probe observes native copy/invite/stop chat hit tests and
command-result labels. Its fake hosting session and intercepted command sink
never invoke Steam, clipboard actions or real invitations. Physical mouse clicks
and real two-account gameplay remain separate acceptance cases.

The probe also checks the live SoundSystem instance's `soundLibrary` backend,
the native OpenAL context, and a readable `random.click` sound-pool URL. This
does not claim audible output or diagnose another machine's OpenAL loader.
`run_native.py` scopes home, temp and profile environment directories to the
fixture for both Java compilation and the child game.

Extended runs compare opaque pixels from the selected PNG with the real BTW
decoded image, exercise classic/slim cache replacement and GPU texture-object
invalidation, and render the registered no-cape path through the native
inventory. They also inspect actual uploaded atlas dimensions and sprite
rectangles, restoring the previous texture binding.

The native multiplayer test opens/closes the real screen and renders a
clearly synthetic row through its actual slot/font renderer. This validates
world/host/Steam labels, blocked native-LAN announcements, stale-join
suppression and cleanup. It does not prove that a real friend's world was
discovered.

The one-account host probe reads its actual Steam lobby on the Steam worker,
checking owner/generation and the `lanminecraft_display`,
`lanminecraft_player`, and `lanminecraft_world` fields. A real raw TCP Packet 2
attempt claiming the host alias must be rejected without adding a player.
Listener closure, publication-task cleanup and the stopped Steam session are
checked after native quit. None of these is a two-account Steam P2P verdict.

Rendered pixels/counters are native technical evidence. A screenshot or
human visual verdict, positive arbitrary-author cape behavior, and actual
two-account friendship/discovery/P2P remain separate checks.
