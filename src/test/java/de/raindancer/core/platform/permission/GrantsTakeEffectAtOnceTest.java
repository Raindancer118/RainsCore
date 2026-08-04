package de.raindancer.core.platform.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That changing what somebody has been granted takes effect while they are standing there.
 *
 * <h2>The defect this exists because of</h2>
 * {@link Grants} is the store; the live permissions come from a {@code PermissionAttachment} that
 * {@link GrantListener} builds. The listener only ever built it <em>on join</em>. So a grant written
 * while the player was online sat in the file and did nothing:
 *
 * <ul>
 *   <li>a moderator promoted in the GUI had none of their new commands until they relogged;
 *   <li>which was invisible to anyone testing as an operator, because op already satisfies every
 *       {@code PermissionDefault.OP} node — so it only reproduced for the non-op it was built for;
 *   <li>and the same in reverse: a demotion, or a permission toggled off, stayed in force for the rest
 *       of the session. Somebody stripped of their powers keeps them until they choose to reconnect,
 *       which is the direction that actually matters.
 * </ul>
 *
 * <p>{@code GrantListener.apply} was already public and its javadoc already said a promotion "has to
 * take effect now". Nothing called it. Fixing the one caller that was noticed would have left the other
 * six mutators just as silent — so the store itself now says when it changed, and there is a test below
 * that fails if a new mutator forgets.
 */
class GrantsTakeEffectAtOnceTest {

    private static final String NODE = "rains.moderation.vanish";

    /** Records who the store said had changed. */
    private static final class Watcher {

        private final List<UUID> touched = new ArrayList<>();

        void changed(UUID who) {
            touched.add(who);
        }
    }

    private static Grants grants(Path folder) {
        return new Grants(folder);
    }

    @Test
    @DisplayName("granting tells whoever is listening")
    void granting(@TempDir Path folder) {
        Grants grants = grants(folder);
        Watcher watcher = new Watcher();
        grants.onChange(watcher::changed);
        UUID mod = UUID.randomUUID();

        grants.grant(mod, NODE);

        assertThat(watcher.touched).containsExactly(mod);
    }

    @Test
    @DisplayName("revoking tells too — the direction that matters most")
    void revoking(@TempDir Path folder) {
        // Somebody stripped of a permission who keeps it until they feel like reconnecting is worse
        // than a promotion that is slow to arrive.
        Grants grants = grants(folder);
        UUID mod = UUID.randomUUID();
        grants.grant(mod, NODE);
        Watcher watcher = new Watcher();
        grants.onChange(watcher::changed);

        grants.revoke(mod, NODE);

        assertThat(watcher.touched).containsExactly(mod);
    }

    @Test
    @DisplayName("applying a whole preset tells — this is what a promotion does")
    void settingAPreset(@TempDir Path folder) {
        Grants grants = grants(folder);
        Watcher watcher = new Watcher();
        grants.onChange(watcher::changed);
        UUID mod = UUID.randomUUID();

        grants.set(mod, Set.of(NODE, "rains.moderation.kick"));

        assertThat(watcher.touched).containsExactly(mod);
    }

    @Test
    @DisplayName("clearing everything tells — this is what a demotion does")
    void clearing(@TempDir Path folder) {
        Grants grants = grants(folder);
        UUID mod = UUID.randomUUID();
        grants.grant(mod, NODE);
        Watcher watcher = new Watcher();
        grants.onChange(watcher::changed);

        grants.clear(mod);

        assertThat(watcher.touched).containsExactly(mod);
    }

    @Test
    @DisplayName("a set() that empties somebody still tells")
    void settingNothing(@TempDir Path folder) {
        // set(who, empty) is a demotion by another name, and took the early return out of the method.
        Grants grants = grants(folder);
        UUID mod = UUID.randomUUID();
        grants.grant(mod, NODE);
        Watcher watcher = new Watcher();
        grants.onChange(watcher::changed);

        grants.set(mod, List.of());

        assertThat(watcher.touched).containsExactly(mod);
        assertThat(grants.has(mod, NODE)).isFalse();
    }

    @Test
    @DisplayName("a change that changed nothing stays quiet")
    void noChange(@TempDir Path folder) {
        // Re-applying a preset somebody already matches happens on every menu render. Rebuilding the
        // attachment each time would be a permission recalculation per frame.
        Grants grants = grants(folder);
        UUID mod = UUID.randomUUID();
        grants.grant(mod, NODE);
        Watcher watcher = new Watcher();
        grants.onChange(watcher::changed);

        grants.grant(mod, NODE);
        grants.revoke(mod, "something.they.never.had");
        grants.clear(UUID.randomUUID());

        assertThat(watcher.touched).isEmpty();
    }

    @Test
    @DisplayName("loading from disk does not fire for every player on the file")
    void loading(@TempDir Path folder) {
        // load() runs at startup, before anybody is online. Firing there would be one wasted
        // attachment rebuild per stored player, and would run before the listener exists.
        Grants grants = grants(folder);
        grants.grant(UUID.randomUUID(), NODE);
        grants.flush();

        Grants reopened = grants(folder);
        Watcher watcher = new Watcher();
        reopened.onChange(watcher::changed);
        reopened.load();

        assertThat(watcher.touched).isEmpty();
    }

    @Test
    @DisplayName("a listener that throws does not lose the grant")
    void aThrowingListener(@TempDir Path folder) {
        // The store is the durable half. If refreshing somebody's live session fails, the grant itself
        // must still stand — otherwise a promotion half-happens and the file disagrees with the menu.
        Grants grants = grants(folder);
        grants.onChange(who -> {
            throw new IllegalStateException("no server here");
        });
        UUID mod = UUID.randomUUID();

        grants.grant(mod, NODE);

        assertThat(grants.has(mod, NODE)).isTrue();
    }

    @Test
    @DisplayName("several listeners all hear it")
    void severalListeners(@TempDir Path folder) {
        Grants grants = grants(folder);
        Watcher one = new Watcher();
        Watcher two = new Watcher();
        grants.onChange(one::changed);
        grants.onChange(two::changed);
        UUID mod = UUID.randomUUID();

        grants.grant(mod, NODE);

        assertThat(one.touched).containsExactly(mod);
        assertThat(two.touched).containsExactly(mod);
    }

    @Test
    @DisplayName("every method that changes a grant announces it")
    void nobodyChangesGrantsSilently(@TempDir Path folder) {
        // The guard. The original bug was not that one caller forgot to refresh the session — it was
        // that refreshing was a caller's job at all, so seven mutators each had to remember. A new
        // mutator added without firing would reintroduce exactly this, silently.
        Set<String> mutators = new LinkedHashSet<>();
        for (Method method : Grants.class.getDeclaredMethods()) {
            if (method.getName().matches("grant|revoke|set|clear")) {
                mutators.add(method.getName());
            }
        }
        assertThat(mutators)
                .as("a mutator was renamed or removed; this test's list has to follow")
                .containsExactlyInAnyOrder("grant", "revoke", "set", "clear");

        List<String> silent = new ArrayList<>();
        UUID mod = UUID.randomUUID();
        for (String mutator : mutators) {
            Grants grants = grants(folder);
            grants.grant(mod, NODE);            // so every mutator has something to actually change
            Watcher watcher = new Watcher();
            grants.onChange(watcher::changed);

            switch (mutator) {
                case "grant" -> grants.grant(mod, "rains.moderation.kick");
                case "revoke" -> grants.revoke(mod, NODE);
                case "set" -> grants.set(mod, Set.of("rains.moderation.kick"));
                case "clear" -> grants.clear(mod);
                default -> throw new AssertionError(mutator);
            }
            if (watcher.touched.isEmpty()) {
                silent.add(mutator);
            }
        }

        assertThat(silent)
                .as("a grant changed without the live session being told — the player keeps their old "
                        + "permissions until they relog, which for a revocation means they keep powers "
                        + "somebody has already decided to take away")
                .isEmpty();
    }
}
