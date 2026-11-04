package org.lanminecraft.btwbridge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Content-specific bridge. Authentication and native player names belong to the transport. */
public final class BridgeClient implements ClientModInitializer {
    private static final String BTW_SHA256 = "ef2b16a0183d810f3c9c526758fc56a2ef3ef540346d2e1cc442f7f9f892739d";

    @Override public void onInitializeClient() {
        try {
            var mod = FabricLoader.getInstance().getModContainer("btw")
                .orElseThrow(() -> new IllegalStateException("BTW is required by the launcher profile bridge."));
            var origins = mod.getOrigin().getPaths();
            if (origins.size() != 1 || !BTW_SHA256.equals(sha256(origins.get(0)))) {
                throw new IllegalStateException("The launcher profile bridge requires the verified BTW CE 3.1.1 JAR.");
            }
            SkinProfiles.refresh(null);
            System.out.println("[BTWProfileBridge] Verified BTW CE 3.1.1 profile API; existing skin and Ears rendering retained.");
        } catch (Exception failure) {
            throw new IllegalStateException("BTW launcher profile bridge could not be initialized.", failure);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
