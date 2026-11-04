package org.lanminecraft.btwbridge;

import api.client.mojapi.ProfileUtils;
import api.client.mojapi.UserProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SkinProfiles {
    private static volatile Map<String, RegistryData.Skin> skins = Map.of();
    private static volatile Map<String, UserProfile> profiles = Map.of();
    private static volatile Map<String, RegistryData.Identity> identities = Map.of();
    private static long nextRead;
    private static String fingerprint = "";

    public static Optional<UserProfile> find(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    public static boolean hasNoCape(String name) {
        UserProfile profile = profiles.get(name);
        return profile != null && (profile.getCapeUrl() == null || profile.getCapeUrl().isBlank());
    }

    /** Only the identified named-effect callers use this; identity getters stay native. */
    public static String namedFieldEffect(Object player) {
        try {
            String nativeName = (String) field(player.getClass(), "field_3982").get(player);
            return RegistryData.namedEffectName(identities, nativeName, nativeName, true);
        } catch (Exception failure) {
            throw new IllegalStateException("The verified player named-effect field could not be read.", failure);
        }
    }

    public static String namedGetterEffect(Object entity, String getter) {
        try {
            String original = (String) method(entity.getClass(), getter).invoke(entity);
            Class<?> playerType = Class.forName("net.minecraft.class_988", false, entity.getClass().getClassLoader());
            if (!playerType.isInstance(entity)) return original;
            String nativeName = (String) field(entity.getClass(), "field_3982").get(entity);
            return RegistryData.namedEffectName(identities, nativeName, original, true);
        } catch (Exception failure) {
            throw new IllegalStateException("The verified player named-effect getter could not be read.", failure);
        }
    }

    /** Keep the native renderer's loaded test for every ordinary authored cape. */
    public static boolean capeLoaded(Object cape, Object player) {
        try {
            if (cape == null && hasNoCape((String) field(player.getClass(), "field_3982").get(player))) return false;
            return (Boolean) method(java.util.Objects.requireNonNull(cape).getClass(), "method_5819").invoke(cape);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The verified BTW cape-loaded check could not be invoked.", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("The verified BTW cape-loaded check failed.", failure);
        }
    }

    /** Called on the native client thread. No timer thread touches GL or entities. */
    public static void tick(Object minecraft) {
        if (System.nanoTime() < nextRead) return;
        nextRead = System.nanoTime() + 250_000_000L;
        refresh(minecraft);
    }

    public static void refresh(Object minecraft) {
        String identityProperty = System.getProperty("minecraft.portable.legacy.registry", "").trim();
        String skinProperty = System.getProperty("minecraft.portable.skin.registry", "").trim();
        if (identityProperty.isEmpty()) return;
        try {
            Path identity = Path.of(identityProperty), skin = skinProperty.isEmpty() ? null : Path.of(skinProperty);
            String stamp = stamp(identity) + ":" + (skin == null ? "none" : stamp(skin));
            if (stamp.equals(fingerprint)) return;
            List<String> identityLines = lines(identity);
            Map<String, RegistryData.Identity> identityLabels = RegistryData.identities(identityLines);
            Map<String, RegistryData.Skin> loaded = RegistryData.parse(identityLines, skin == null ? List.of() : lines(skin));
            Set<String> changed = new HashSet<>(skins.keySet());
            changed.addAll(loaded.keySet());
            changed.removeIf(name -> java.util.Objects.equals(skins.get(name), loaded.get(name)));
            Map<String, UserProfile> built = new java.util.HashMap<>();
            for (var entry : loaded.entrySet()) {
                RegistryData.Skin value = entry.getValue();
                built.put(entry.getKey(), new UserProfile(value.uuid(), value.nativeName(),
                    "slim".equals(value.model()), value.url(), null));
            }
            // Publish before refreshing entities: their normal setup re-enters the lookup mixin.
            identities = identityLabels;
            profiles = Map.copyOf(built);
            for (String name : changed) {
                ProfileUtils.removeUserProfile(name);
                UserProfile replacement = profiles.get(name);
                if (replacement != null) ProfileUtils.addFakeProfile(replacement);
            }
            if (minecraft != null && !changed.isEmpty()) refreshPlayers(minecraft, changed);
            skins = loaded;
            fingerprint = stamp;
            if (!changed.isEmpty()) System.out.println("[BTWProfileBridge] Updated " + changed.size() + " launcher skin profile(s).");
        } catch (java.io.IOException transientRead) {
            // Registry replacement is atomic, but a transient read failure must
            // not discard the last complete set. Retry on the next native tick.
            System.err.println("[BTWProfileBridge] Could not read the launcher skin registries; retaining the last complete set.");
        } catch (Exception failure) {
            throw new IllegalStateException("The verified BTW profile bridge could not refresh launcher skins.", failure);
        }
    }

    private static List<String> lines(Path file) throws java.io.IOException {
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }
    private static String stamp(Path file) throws java.io.IOException {
        return Files.exists(file) ? Files.getLastModifiedTime(file).toString() + "/" + Files.size(file) : "missing";
    }

    private static void refreshPlayers(Object minecraft, Set<String> names) throws Exception {
        ClassLoader loader = minecraft.getClass().getClassLoader();
        Class<?> playerClass = Class.forName("net.minecraft.class_1631", false, loader);
        Object textures = method(minecraft.getClass(), "method_5570").invoke(minecraft);
        @SuppressWarnings("unchecked") Map<Object, Object> textureObjects =
            (Map<Object, Object>) field(textures.getClass(), "field_6575").get(textures);
        Method skinLocation = playerClass.getDeclaredMethod("method_5719", String.class);
        for (String name : names) {
            Object removed = textureObjects.remove(skinLocation.invoke(null, name));
            if (removed != null) method(removed.getClass(), "unloadGLTexture").invoke(removed);
        }
        List<Object> players = new ArrayList<>();
        Object local = field(minecraft.getClass(), "field_3805").get(minecraft);
        if (local != null) players.add(local);
        Object world = field(minecraft.getClass(), "field_3803").get(minecraft);
        if (world != null) {
            Object values = field(world.getClass(), "field_4544").get(world);
            if (values instanceof List<?>) for (Object player : (List<?>) values)
                if (!players.contains(player)) players.add(player);
        }
        Method setup = method(playerClass, "method_5722");
        for (Object player : players) {
            if (playerClass.isInstance(player) && names.contains((String) field(player.getClass(), "field_3982").get(player)))
                setup.invoke(player);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field found = current.getDeclaredField(name); found.setAccessible(true); return found; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method found = current.getDeclaredMethod(name); found.setAccessible(true); return found; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
    private SkinProfiles() {}
}
