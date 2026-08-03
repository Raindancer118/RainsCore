package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That no flag an owner sets can take an admin's bypass away.
 *
 * <h2>The bug this is about</h2>
 * Reported as "an admin inside a claim could not ender-pearl once the owner switched pearls off, even with the
 * bypass on — toggling the bypass fixed it". Then, correctly: <em>it is not only teleports; the bypass is
 * overridden whenever claim rules are changed.</em>
 *
 * <p>The cause was that the bypass was never in one place. Each listener remembered it for itself: the teleport
 * gate checked it, the potion thrower checked it, the movement listener checked it — and the fourteen flag
 * questions that went straight through {@link LandFlags} did not. So whether the bypass held depended on which
 * listener happened to be enforcing the flag, which is not a rule anybody can hold in their head and not one
 * an admin can rely on.
 *
 * <p>It now lives in {@link LandFlags}, which is the one thing every listener asks. A bypass that has to be
 * remembered in fourteen places is a bypass that works in thirteen.
 *
 * <p>Held partly as a source scan: the answer needs a {@link org.bukkit.entity.Player}, and a Player needs a
 * server. What is checkable without one is that the single choke point consults it, and that nothing has gone
 * back to enforcing a flag around it.
 */
class BypassIsNotBreakableTest {

    private static final Path PROTECTION =
            Path.of("src/main/java/de/raindancer/core/world/protection");

    private static String read(String file) {
        try {
            return Files.readString(PROTECTION.resolve(file));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the facade, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(read("LandFlags.java")).contains("isAllowedForTracked");
    }

    @Test
    @DisplayName("every flag question about a player consults the bypass")
    void theOneChokePointChecksIt() {
        String facade = read("LandFlags.java");

        // The three overloads that know who is asking. The block-level ones take no player and are about the
        // world rather than a person, so there is nobody to bypass on their behalf.
        for (String signature : List.of(
                "public boolean isAllowedAt(Location location, LandFlag flag, UUID who)",
                "public boolean isAllowedFor(Entity entity, LandFlag flag)",
                "public boolean isAllowedForTracked(ProtectedArea tracked, Location location, LandFlag flag,")) {
            int at = facade.indexOf(signature);
            assertThat(at).as(signature + " is gone").isNotNegative();
            // Either it checks, or it hands the question to the overload that does. Delegation counts on
            // purpose: a second check on the way through would be two places to keep in step again, which is
            // the exact shape of the bug being fixed.
            assertThat(facade.substring(at, Math.min(facade.length(), at + 700)))
                    .as(signature + " must let a bypassing admin through, itself or by delegating")
                    .containsPattern("isBypassing|bypassed\\(|isAllowedAt\\(");
        }
    }

    @Test
    @DisplayName("the bypass is decided in one place, not remembered in fourteen")
    void theListenersDoNotEachKeepTheirOwnCopy() {
        // Not a ban on mentioning it — a listener may still check the bypass for something that is not a flag,
        // and the movement listener has to, because refusing a step is not a flag question. What must not
        // happen is a NEW flag check written without it, which is how this drifted in the first place.
        List<String> listeners = new ArrayList<>();
        try (Stream<Path> files = Files.list(PROTECTION)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith("Listener.java")) {
                    listeners.add(name);
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not list the protection package", unreadable);
        }

        assertThat(listeners)
                .as("if this is empty the scan below checks nothing")
                .isNotEmpty();

        // Reaching past the facade to FlagRules is allowed, and sometimes necessary: PvP resolves the flag at
        // the VICTIM's tier, and routing that through the facade would make a bypassing admin PvP-able rather
        // than able to fight — the opposite of what a bypass means. What such a listener must do instead is
        // check the bypass of whoever is acting, itself, before it asks.
        List<String> unguarded = new ArrayList<>();
        for (String listener : listeners) {
            String body = read(listener);
            if (body.contains("land.flags().isAllowedFor") && !body.contains("isBypassing")) {
                unguarded.add(listener);
            }
        }
        assertThat(unguarded)
                .as("these ask FlagRules directly — the one path that does not know about the bypass — without "
                        + "checking it for the player who is acting")
                .isEmpty();
    }

    @Test
    @DisplayName("a bypassing admin is not stopped by a flag, and the flag still applies to everybody else")
    void thebypassIsNotAServerWideOff() {
        String facade = read("LandFlags.java");

        // The failure worth guarding against while fixing this: a bypass that short-circuits the whole
        // resolver rather than the asker's own answer would switch the flag off for the entire server the
        // moment one admin turned their bypass on.
        assertThat(facade)
                .as("the bypass has to be about the player asking, never about the area")
                .contains("isBypassing(");
        assertThat(facade)
                .as("the check belongs next to the player it is about")
                .doesNotContain("bypassing.contains");
    }
}
