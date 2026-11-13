# BTW launcher profile bridge

This is a client-only Legacy Fabric mod, ID `btw_lan_bridge`. Build with `JAVA_HOME` pointing to **Eclipse Adoptium Temurin 21.0.12.1+1** (the runtime target remains Java 17):

```
python tools/legacy-bridge/build_bridge.py --pack .
```

The builder verifies the exact compiler distribution/build, uses `--release 17`, exact dependency SHA256 values and deterministic ZIP entries, including fixed timestamps, permissions and `create_system=0` on both Windows and Linux. Downloads and the finished JAR replace their destinations only after a complete write, retaining the previous output on failure. It produces `mods/btw-lan-bridge-1.0.0.jar` and an ignored `artifacts/build-report.json`. Compiler dependencies are not embedded or shipped. The independently pinned compile-time Mixin annotations do not replace the runtime Mixin delivered by Loader 0.18.2.

## Exact target and boundary

Runtime initialization verifies the original `btwce-3.1.1.jar` SHA256 `ef2b16a0183d810f3c9c526758fc56a2ef3ef540346d2e1cc442f7f9f892739d` and `Nightmare-Mode-1.1.6.jar` SHA256 `be8528a5e834faac4d999cfac9038a85f144500521ba9f8e4c19c035b941d248`. Other author builds fail explicitly. The runtime JARs, configuration and renderer remain authored files; this helper changes the following runtime sites:

* A HEAD injection into `api.client.mojapi.ProfileUtils.getUserProfile(String,boolean)` supplies only launcher-registered cosmetic profiles. Other names continue through the original Mojang lookup. The profile uses BTW's existing `UserProfile`, image downloader, 64x64 image buffer, slim model and Ears integration. No texture cropping or renderer replacement occurs.
* A priority-900 redirect of `net.minecraft.class_1600`'s `Display.setTitle(String)` calls uses the existing launch-context property `minecraft.portable.identity.windowTitleText`. The redirect has injection order 10000, after Nightmare Mode's argument modifier appends its branding, so the final Display call receives the exact launch-context title. No title string is copied into this helper. Absent a launch-context title, the original title remains.
* The launcher's skin registry has no cape field. For only its registered profiles with an absent cape URL, `AbstractClientPlayer.method_5716(ResourceLocation,String)` returns null before allocating/scheduling a cape download. This avoids BTW's native conversion of `UserProfile.capeUrl=null` into the empty string and `ThreadDownloadImageData` requesting `URL("")`. `RenderPlayer.method_1582(AbstractClientPlayer,float)` dereferences the cape downloader without a null guard, so its second `ThreadDownloadImageData.method_5819()` call (ordinal 1, the cape only) returns false for that same registered/no-cape player. Its first check (deadmau5 skin), all non-null cape checks and profiles outside the launcher registry retain the native path. This does not fabricate a URL, allocate a placeholder texture, suppress logs or alter skin/model/Ears geometry.
* Canonical Steam storage names must not erase the identified authored name effects. Three exact callers read the chosen display name from the strict identity registry: the first username-field read in `EntityPlayer.method_2580(DamageSource)` (the existing `Notch` death apple), `AbstractClientPlayer.method_3334()` called from `RenderPlayer.method_1582` (the existing `deadmau5` ears), and `EntityLivingBase.method_2518()` called from `RendererLivingEntity.method_5777` (the existing `Dinnerbone`/`Grumm` inversion). The last hook substitutes only player identities; named mobs retain their actual getter result. The native comparisons, item drop, model calls, cape/visibility conditions and geometry remain authored code. No username field or global name getter is changed. These labels load independently of the skin registry, including players with no custom skin. This is an audit of these four identified names, not a claim that every possible name-driven rule was exhaustively audited.

* The exact CE `AchievementEventDispatcher.handleEvent` and Nightmare `ItemAchievementGranter.displayAchievement` username reads used to compose the `achievement.get` announcement resolve the launcher display name. Native award rules, saved names and achievement state remain in the author code.
* The native Share Start call in `class_393.method_1027` passes mode and commands to `class_601.method_3000`. For a Survival overworld, the bridge reads its saved command setting through `WorldInfo.method_237()` and passes it as the native session's guest flag. By the user decision of 8 November 2026, a Creative overworld gives guests Creative and commands. Adventure keeps its native behavior. Nightmare still hides the same controls.

`net.minecraft.class_1600.method_2954()` (native client tick) polls the two registry timestamps and file sizes at most four times per second. Only changed profiles clear BTW's own profile/slim caches. Their texture objects are removed and unloaded, then existing player objects run native `AbstractClientPlayer.method_5722()` again. These are client-thread operations. Reflection names are tied to the guarded exact author JARs and the pinned official intermediary/mappings.

The shared legacy Steam transport owns peer authentication, command-result player labels and world publication. This bridge owns cosmetics and the explicitly selected new-world/Creative exceptions. The pause-menu no-escape mechanic, other difficulty parameters and Underworld lifecycle retain their authored logic.

The new-world screen `class_363.method_915` is fixed to authored difficulty index 2 (Nightmare), with its selector disabled and its existing lock enabled. Game mode, seed, world type and saved worlds are untouched. The exact CE fields and Nightmare refmap target were reviewed against the pinned JARs; both injections require exactly one site. Runtime author SHA guards remain enabled.

## Inputs

* `minecraft.portable.legacy.registry`: UTF-8 `SteamID64|canonicalName|displayNameBase64|portableUUID` lines. The canonical name must equal `s` followed by the unsigned Steam ID's base36 representation padded to 13 characters. The third field is strict Base64 of UTF-8, preserving saved Unicode, spaces and pipe characters; its decoded name has at most 16 UTF-16 units and no control characters. It is not used as an identity or skin lookup key.
* `minecraft.portable.skin.registry`: the existing launcher UTF-8 `portableUUID|SHA256|classic-or-slim|HTTP-URL` lines.

Only a UUID present in both registries receives a skin override. Named-effect labels require only a valid identity row. Skin URLs must be `http://127.0.0.1:<port>/skin/<portableUUID>/<SHA256>`. Malformed rows, conflicting identity labels, unknown models, unregistered UUIDs and remote/mismatched URLs are ignored. Read failures retain the last complete set and are reported. These files supply display/cosmetic context, never Steam authentication.

The builder runs positive and negative registry tests. Full native client, first/third-person skin, peer, classic/slim switching and reload checks must additionally be recorded by runtime QA; a successful build is not evidence that those checks passed.

## Updates

This directory is the owned overlay source. A BTW update requires reviewing the exact API/callers, revising the SHA guard and target hooks, rebuilding, runtime QA, then updating distribution locks through the sole pack owner. Replacing only the author JAR cannot silently reuse this bridge.
