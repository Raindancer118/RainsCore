package de.raindancer.core.social.presence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class AwayTest {

    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();

    @AfterEach
    void reset() {
        Away.clear();
    }

    @Test
    @DisplayName("with nobody watching, nobody is away, and a caller can tell nobody is watching")
    void nobody() {
        assertThat(Away.isAway(ada)).isFalse();
        assertThat(Away.isKnown()).isFalse();
    }

    @Test
    @DisplayName("somebody is away when any watcher says so")
    void anyWatcher() {
        Away.watch(Set.of(ada)::contains);
        Away.watch(who -> false);
        assertThat(Away.isKnown()).isTrue();
        assertThat(Away.isAway(ada)).isTrue();
        assertThat(Away.isAway(bo)).isFalse();
    }

    @Test
    @DisplayName("a watcher that throws is passed over, not fatal")
    void broken() {
        Away.watch(who -> {
            throw new IllegalStateException("broken");
        });
        assertThat(Away.isAway(ada)).isFalse();
    }

    @Test
    @DisplayName("a watcher that stops, or whose plugin went away, is forgotten")
    void forgetting() {
        Predicate<UUID> watcher = Set.of(ada)::contains;
        Away.watch(watcher);
        Away.unwatch(watcher);
        assertThat(Away.isKnown()).isFalse();
        Away.watch(new AlwaysAway());
        assertThat(Away.forgetFrom(AlwaysAway.class.getClassLoader())).isEqualTo(1);
        assertThat(Away.isAway(ada)).isFalse();
    }

    private static final class AlwaysAway implements Predicate<UUID> {
        @Override
        public boolean test(UUID who) {
            return true;
        }
    }
}
