package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PriceIndexTest {

    private final Plugin owner = mock(Plugin.class);

    @AfterEach
    void reset() {
        PriceIndex.clear();
    }

    @Test
    @DisplayName("without anybody measuring prices, a fee is what the owner wrote")
    void nobody() {
        assertThat(PriceIndex.level()).isEqualTo(1.0);
        assertThat(PriceIndex.follow(Money.of(1000))).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("a fee follows the price level, rounded up, when the measurer says fees should")
    void following() {
        PriceIndex.provide(owner, () -> 1.5);
        assertThat(PriceIndex.level()).isEqualTo(1.5);
        assertThat(PriceIndex.follow(Money.of(1001))).isEqualTo(Money.of(1502));
    }

    @Test
    @DisplayName("nonsense from the measurer — zero, negative, NaN — counts as no change")
    void nonsense() {
        PriceIndex.provide(owner, () -> Double.NaN);
        assertThat(PriceIndex.level()).isEqualTo(1.0);
        PriceIndex.clear();
        PriceIndex.provide(owner, () -> -2);
        assertThat(PriceIndex.follow(Money.of(10))).isEqualTo(Money.of(10));
        PriceIndex.clear();
        PriceIndex.provide(owner, () -> {
            throw new IllegalStateException();
        });
        assertThat(PriceIndex.level()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a level beyond reason is held at a hundred times, so one bad price cannot price everybody out")
    void ceiling() {
        PriceIndex.provide(owner, () -> 1e9);
        assertThat(PriceIndex.level()).isEqualTo(PriceIndex.MOST);
    }
}
