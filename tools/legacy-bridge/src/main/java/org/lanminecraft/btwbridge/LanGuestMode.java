package org.lanminecraft.btwbridge;

import java.util.function.Supplier;

/** Inherit Creative from the overworld when the native share dialog defaults to Survival. */
public final class LanGuestMode {
    public record Share(Object mode, boolean commandsAllowed) {}

    public static Share forShare(Object selectedMode, boolean commandsAllowed) {
        return select(selectedMode, commandsAllowed, () -> readOverworldMode(selectedMode));
    }

    static Share select(Object selectedMode, boolean commandsAllowed, Supplier<?> overworldMode) {
        if (!(selectedMode instanceof Enum<?> selected)
                || !(selected.name().equals("SURVIVAL") || selected.name().equals("CREATIVE")))
            return new Share(selectedMode, commandsAllowed);

        Object worldMode = overworldMode.get();
        if (worldMode instanceof Enum<?> world && world.getDeclaringClass() == selected.getDeclaringClass()
                && world.name().equals("CREATIVE")) return new Share(world, true);
        return new Share(selectedMode, commandsAllowed);
    }

    private static Object readOverworldMode(Object selectedMode) {
        try {
            ClassLoader loader = selectedMode.getClass().getClassLoader();
            Class<?> minecraftClass = Class.forName("net.minecraft.class_1600", false, loader);
            Object minecraft = minecraftClass.getMethod("method_2965").invoke(null);
            Object server = minecraftClass.getMethod("method_2909").invoke(minecraft);
            if (server == null) return null;
            Object overworld = server.getClass().getMethod("method_2991", int.class).invoke(server, 0);
            if (overworld == null) return null;
            Object worldInfo = overworld.getClass().getMethod("method_3588").invoke(overworld);
            return worldInfo.getClass().getMethod("method_233").invoke(worldInfo);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not read the verified Minecraft 1.6.4 overworld mode.", failure);
        }
    }

    private LanGuestMode() {}
}
