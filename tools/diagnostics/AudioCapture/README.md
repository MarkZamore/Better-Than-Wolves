# QA process audio capture

Standalone Windows x64/.NET 10 diagnostic. It uses Microsoft's
[application loopback API](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/)
to include only the specified process tree. There is **no desktop-wide or
microphone fallback**. A Java/javaw target must have `-Dbtw.qa.*` or
`LegacyAudioProbe` in its inspected command line. Live unmarked player audio is
refused. The tool does not change default devices, routing, volume or mute.

Build with an explicit generated-output directory in the owner's run:

```text
dotnet build tools/diagnostics/AudioCapture/AudioCapture.csproj -p:CaptureBuildRoot=ABSOLUTE_OWNED_RUN/capture-build
```

Start the marked QA Java process with its sound emission gated on a file. Then:

```text
AudioCapture.exe --pid QA_JAVA_PID --seconds 16 --out ABSOLUTE_OWNED_RUN/capture-click.wav
```

Wait for `capture-click.wav.ready` before releasing the Java emission gate.
The tool creates stereo 44.1kHz signed PCM16 WAV and a same-basename JSON with
RMS, peak, sample counts and SHA256. Duration is limited to 1–20 seconds (at most
3.37MiB PCM); activation waits at most six seconds. An unsupported/failed
application-loopback operation returns exit 5 and `UNAVAILABLE`, without opening
a broader capture. Invalid arguments or an unmarked target return exit 2.

Output must be a new filename beneath a marked `artifacts/agent-runs/<task>`
directory, without links in its ancestry. Keep WAV and JSON private unless they
are explicitly reviewed for release. A captured nonzero signal demonstrates
rendered samples from this QA process; it does not prove audible device output,
the selected Windows endpoint, or two-client gameplay.

`JavaToneProbe.java` is an optional positive control: compile it under the same
owned build directory, start with `-Dbtw.qa.captureProbe=true`, and give it the
planned WAV `.ready` filename. It waits at most 20 seconds, then emits one second
of a generated 440Hz tone at 5% amplitude through JavaSound. This control reads no
microphone data and is not a Minecraft/OpenAL acceptance test.

The pack's [native orchestrator](../../runtime-qa/run_audio_probe.py) starts a
marked independent fixture, waits for native audio readiness and this capture
readiness file, then releases six effect emissions and one native music trigger.
Its default 16-second capture covers the controlled music tail. The explicit
`--omit-assets-dir` flag is a negative control only. Cleanup requests native
`stop.flag` first and never terminates an unconfirmed Java PID.
