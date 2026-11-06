package org.lanminecraft.btwbridge;

import java.util.List;
import java.util.UUID;

public final class RegistryDataTest {
    private static int assertions;
    public static void main(String[] args) {
        String steam = "76561198000000000";
        String name = "s" + "0".repeat(13 - new java.math.BigInteger(steam).toString(36).length()) + new java.math.BigInteger(steam).toString(36);
        String id = "00000000-0000-4000-8000-000000000001";
        String hash = "1".repeat(64);
        String identity = steam + "|" + name + "|UGxheWVyT25l|" + id;
        String url = "http://127.0.0.1:45913/skin/" + id + "/" + hash;
        var slim = RegistryData.parse(List.of(identity), List.of(id + "|" + hash + "|slim|" + url));
        check(slim.size() == 1 && slim.get(name).uuid().equals(UUID.fromString(id)) && slim.get(name).model().equals("slim"), "account joins to slim profile");
        check(RegistryData.parse(List.of(identity), List.of(id + "|" + hash + "|classic|" + url)).get(name).model().equals("classic"), "classic model preserved");
        check(RegistryData.parse(List.of(identity.replace(name, "PlayerOne")), List.of(id + "|" + hash + "|slim|" + url)).isEmpty(), "display name cannot claim native identity");
        check(RegistryData.parse(List.of(identity), List.of(id + "|" + hash + "|slim|" + url.replace("127.0.0.1", "example.com"))).isEmpty(), "remote URL rejected");
        check(RegistryData.parse(List.of(identity), List.of(id + "|" + hash + "|slim|" + url.replace(hash, "2".repeat(64)))).isEmpty(), "URL bound to skin hash");
        check(RegistryData.parse(List.of(), List.of(id + "|" + hash + "|slim|" + url)).isEmpty(), "unknown UUID has no name association");
        check(RegistryData.parse(List.of(identity), List.of(id + "|" + hash + "|other|" + url)).isEmpty(), "unknown model rejected");
        check(RegistryData.parse(List.of(identity), List.of(id + "|" + hash + "|slim|" + url + "|extra")).isEmpty(), "partial/malformed line rejected");
        String display64 = java.util.Base64.getEncoder().encodeToString("Игрок| Один".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(RegistryData.parse(List.of(identity.replace("UGxheWVyT25l", display64)), List.of(id + "|" + hash + "|slim|" + url)).size() == 1, "saved Unicode and pipe display names preserved");
        check(RegistryData.parse(List.of(identity.replace("UGxheWVyT25l", "!invalid!")), List.of(id + "|" + hash + "|slim|" + url)).isEmpty(), "invalid Base64 display rejected");
        for (String effect : List.of("Notch", "deadmau5", "Dinnerbone", "Grumm")) {
            String effect64 = java.util.Base64.getEncoder().encodeToString(effect.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var labels = RegistryData.identities(List.of(identity.replace("UGxheWVyT25l", effect64)));
            check(RegistryData.namedEffectName(labels, name, name, true).equals(effect), "identified player name effect preserved: " + effect);
            check(RegistryData.namedEffectName(labels, name, name, false).equals(name), "a non-player's canonical-looking name is not translated: " + effect);
        }
        var labels = RegistryData.identities(List.of(identity.replace("UGxheWVyT25l", "Tm90Y2g=")));
        check(RegistryData.parse(List.of(identity), List.of()).isEmpty() && labels.containsKey(name), "effect labels do not require a custom skin");
        check(RegistryData.namedEffectName(labels, "Unknown", "Unknown", true).equals("Unknown"), "unregistered players retain native names");
        check(!RegistryData.namedEffectName(labels, name, name, true).equals("notch"), "authored comparisons remain case-sensitive");
        var ambiguous = RegistryData.identities(List.of(identity, identity.replace("UGxheWVyT25l", "Tm90Y2g=")));
        check(ambiguous.isEmpty(), "conflicting labels for one canonical identity are rejected");
        check(RegistryData.namedEffectName(labels, name, "Dinnerbone", false).equals("Dinnerbone"), "named mob native effect is unchanged");
        check(LanGuestMode.select(Mode.SURVIVAL, false, () -> Mode.CREATIVE) == Mode.CREATIVE,
            "Creative worlds give LAN guests Creative");
        check(LanGuestMode.select(Mode.SURVIVAL, false, () -> Mode.SURVIVAL) == Mode.SURVIVAL,
            "Survival worlds keep the native Survival default");
        check(LanGuestMode.select(Mode.SURVIVAL, false, () -> Mode.ADVENTURE) == Mode.SURVIVAL,
            "Adventure worlds keep the native Survival default");
        check(LanGuestMode.select(Mode.SURVIVAL, false, () -> null) == Mode.SURVIVAL,
            "Unavailable world retains the native default");
        check(LanGuestMode.select(Mode.ADVENTURE, false, () -> { throw new AssertionError("Explicit mode must win"); })
            == Mode.ADVENTURE, "Explicit LAN mode is respected");
        check(LanGuestMode.select(Mode.SURVIVAL, true, () -> { throw new AssertionError("Commands-enabled share stays native"); })
            == Mode.SURVIVAL, "Commands-enabled LAN remains on the authored path");
        System.out.println("RegistryDataTest: " + assertions + " checks passed");
    }
    private enum Mode { SURVIVAL, CREATIVE, ADVENTURE }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
