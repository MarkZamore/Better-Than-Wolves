package org.lanminecraft.btwbridge;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Strict cosmetic join of the launcher's two existing account registries. */
public final class RegistryData {
    public record Identity(UUID uuid, String nativeName, String displayName) {}
    public record Skin(UUID uuid, String nativeName, String model, String hash, String url) {}

    public static Map<String, Identity> identities(List<String> identityLines) {
        Map<String, Identity> identities = new HashMap<>();
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (String line : identityLines) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) continue;
            try {
                BigInteger steam = new BigInteger(fields[0]);
                String digits = steam.toString(36);
                if (steam.signum() <= 0 || steam.bitLength() > 64 || digits.length() > 13) continue;
                String nativeName = "s" + "0".repeat(13 - digits.length()) + digits;
                String display = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(fields[2]))).toString();
                if (!nativeName.equals(fields[1]) || display.isBlank() || display.length() > 16 ||
                    display.chars().anyMatch(Character::isISOControl)) continue;
                UUID uuid = UUID.fromString(fields[3]);
                Identity value = new Identity(uuid, nativeName, display);
                Identity known = identities.putIfAbsent(nativeName, value);
                if (known != null && !known.equals(value)) ambiguous.add(nativeName);
            } catch (IllegalArgumentException | CharacterCodingException ignored) { }
        }
        ambiguous.forEach(identities::remove);
        return Map.copyOf(identities);
    }

    public static String namedEffectName(Map<String, Identity> identities, String nativeName,
        String original, boolean isPlayer) {
        if (!isPlayer) return original;
        Identity identity = identities.get(nativeName);
        return identity == null ? original : identity.displayName();
    }

    public static Map<String, Skin> parse(List<String> identityLines, List<String> skinLines) {
        Map<UUID, Identity> identities = new HashMap<>();
        for (Identity identity : identities(identityLines).values()) identities.put(identity.uuid(), identity);
        Map<String, Skin> result = new HashMap<>();
        for (String line : skinLines) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4 || !fields[1].matches("[0-9a-fA-F]{64}")) continue;
            try {
                UUID uuid = UUID.fromString(fields[0]);
                Identity identity = identities.get(uuid);
                if (identity == null) continue;
                String model = fields[2].toLowerCase(java.util.Locale.ROOT);
                if (!model.equals("classic") && !model.equals("slim")) continue;
                URI url = URI.create(fields[3]);
                if (!"http".equals(url.getScheme()) || !"127.0.0.1".equals(url.getHost()) ||
                    url.getPort() < 1 || url.getPort() > 65535 || url.getUserInfo() != null ||
                    url.getFragment() != null || url.getQuery() != null) continue;
                String hash = fields[1].toLowerCase(java.util.Locale.ROOT);
                if (!("/skin/" + uuid + "/" + hash).equalsIgnoreCase(url.getRawPath())) continue;
                result.put(identity.nativeName(), new Skin(uuid, identity.nativeName(), model, hash, fields[3]));
            } catch (IllegalArgumentException ignored) { }
        }
        return Map.copyOf(result);
    }
    private RegistryData() {}
}
