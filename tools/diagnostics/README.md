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
