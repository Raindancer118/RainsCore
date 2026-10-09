package de.raindancer.core.content.items;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InsuredItemsTest {

    private final Plugin owner = mock(Plugin.class);

    @AfterEach
    void reset() {
        InsuredItems.clear();
    }

    @Test
    @DisplayName("with nobody keeping policies nothing is insured, so a mark alone protects nothing")
    void nobody() {
        assertThat(InsuredItems.inForce("p1")).isFalse();
        assertThat(InsuredItems.isInsured(null)).isFalse();
    }

    @Test
    @DisplayName("a policy is in force when a keeper says so; a lapsed or unknown one is not")
    void keepers() {
        Predicate<String> keeper = Set.of("p1")::contains;
        InsuredItems.provide(owner, keeper);
        assertThat(InsuredItems.inForce("p1")).isTrue();
        assertThat(InsuredItems.inForce("p2")).isFalse();
        assertThat(InsuredItems.inForce("")).isFalse();
        InsuredItems.retract(keeper);
        assertThat(InsuredItems.inForce("p1")).isFalse();
    }

    @Test
    @DisplayName("a keeper that throws counts as in force, so a broken keeper never lets an insured item be sold")
    void broken() {
        InsuredItems.provide(owner, policy -> {
            throw new IllegalStateException();
        });
        assertThat(InsuredItems.inForce("p1")).isTrue();
    }
}
