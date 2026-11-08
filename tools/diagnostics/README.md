# Read-only legacy diagnosis

These tools are separate from acceptance QA. They inspect personal worlds only
at their owner's explicit request, without modifying blocks, players, settings,
commands or mod classes. Output is private evidence, not client or Steam QA PASS.

`inspect_portals.py WORLD` reads saved NBT/regions. Unsaved live changes can differ.
`InspectLegacyClient.java` is an explicitly attached, one-shot Java 17 inspector.
It registers no transformers, reads on the existing e4steam client tick queue,
and writes only the supplied diagnostic file under the assigned agent run.
Compile with `javac --release 17 --add-modules jdk.attach`, package with
`Agent-Class: InspectLegacyClient`, then invoke main with PID, agent JAR, output.
No inspector is included in the pack payload, recipe or distribution lock.

## Audio diagnosis

`InspectLegacyAudio.java` uses the same read-only attach/queue pattern and agent
manifest as the portal inspector, with `Agent-Class: InspectLegacyAudio`. It
reports the actual game asset folder, sound-pool entries, native backend and
OpenAL current/default device. It plays no sounds and changes no settings.

`InspectWindowsAudio.ps1` enumerates existing render sessions and the three
Windows default roles through Core Audio COM getters. Its private JSON includes
other process IDs/device labels; do not publish raw output as public QA.

`Test-LegacyAudioLoad.ps1 -AppRoot <launcher> -OutputDirectory <new-owned-run>
-UseJavaw` inventories actual installed native files, runs a Win32 loader check,
then a bounded debug OpenAL initialization using the installed JDK and exact
native directory. It does not extract an alternative DLL, play audio, or change
device preferences. A guest report lacking this inventory cannot establish the
cause of `Could not locate OpenAL library`.

For a player, extract BOTH `Run-AudioCheck.cmd` and `Test-LegacyAudioLoad.ps1`
beside `LANMinecraft.exe`, then run the CMD file. The checker uses built-in
Windows PowerShell 5.1 (64-bit even from a 32-bit shell); no PowerShell download
is needed. Command-line use under PowerShell 7 remains supported. Every startup
path saves `AudioCheck-start.log` beside the CMD and a double-clicked window
waits for a key even on a missing script/launcher error. Send that one log first;
it includes the detailed diagnostic output or the startup failure. The optional
two arguments are an explicit launcher root and fresh output directory; explicit
root mode skips the interactive pause for automation. No script downloads or
installs libraries, plays sounds, changes devices, or edits worlds.

[AudioCapture](AudioCapture/README.md) captures only a marked QA Java process
tree, with no microphone or desktop-wide fallback. Its positive tone control is
synthetic; the native game comparison lives in `../runtime-qa/run_audio_probe.py`.
PCM measurements establish rendered signals, not physical headphone listening.
