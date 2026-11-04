# Native Better Than Wolves runtime QA

These tools operate only on marked, independent fixtures below `artifacts`.
They do not copy or open personal worlds. Java instrumentation is QA-only and
must never enter pack payloads or active distribution locks.

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

`run_production.py` consumes the private context from the launcher's
`tools/LegacyRuntimeQa` exporter. It preserves the actual CmlLib arguments,
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
