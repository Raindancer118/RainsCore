package de.raindancer.core.social.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PriceModifiersTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final UUID cook = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @AfterEach
    void reset() {
        PriceModifiers.clear();
    }

    /** 25% off food for the cook, 10% more for crops they sell. */
    private final PriceModifier cookRole = (player, material, side) -> {
        if (!player.equals(cook)) {
            return Optional.empty();
        }
        if (side == TradeSide.BUY && material.equals("BREAD")) {
            return Optional.of(new PriceChange(-25, "Cook"));
        }
        if (side == TradeSide.SELL && material.equals("WHEAT")) {
            return Optional.of(new PriceChange(10, "Cook"));
        }
        return Optional.empty();
    };

    @Test
    @DisplayName("with nobody changing prices everybody pays the base price, and nothing is explained")
    void nobody() {
        PersonalPrice price = PriceModifiers.buy(cook, "BREAD", Money.of(1000));
        assertThat(price.price()).isEqualTo(Money.of(1000));
        assertThat(price.changed()).isFalse();
        assertThat(price.reasons()).isEmpty();
    }

    @Test
    @DisplayName("a discount applies to whom and what it names, and says why")
    void discount() {
        PriceModifiers.provide(owner, cookRole);
        PersonalPrice bread = PriceModifiers.buy(cook, "BREAD", Money.of(1000));
        assertThat(bread.price()).isEqualTo(Money.of(750));
        assertThat(bread.percent()).isEqualTo(-25);
        assertThat(bread.reasons()).containsExactly("Cook");
        assertThat(PriceModifiers.buy(stranger, "BREAD", Money.of(1000)).price()).isEqualTo(Money.of(1000));
        assertThat(PriceModifiers.buy(cook, "STONE", Money.of(1000)).changed()).isFalse();
    }

    @Test
    @DisplayName("buying rounds up and selling rounds down — a cent is never given away")
    void rounding() {
        PriceModifiers.provide(owner, cookRole);
        // 333 × 0.75 = 249.75
        assertThat(PriceModifiers.buy(cook, "BREAD", Money.of(333)).price()).isEqualTo(Money.of(250));
        // 333 × 1.10 = 366.3
        assertThat(PriceModifiers.sell(cook, "WHEAT", Money.of(333)).price()).isEqualTo(Money.of(366));
    }

    @Test
    @DisplayName("a positive price never becomes free, however many discounts stack")
    void neverFree() {
        PriceModifiers.provide(owner, (player, material, side) -> Optional.of(new PriceChange(-80, "A")));
        PriceModifiers.provide(owner, (player, material, side) -> Optional.of(new PriceChange(-80, "B")));
        PersonalPrice price = PriceModifiers.buy(cook, "BREAD", Money.of(1000));
        assertThat(price.percent()).isEqualTo(PriceModifiers.LARGEST_DISCOUNT);
        assertThat(price.price()).isEqualTo(Money.of(100));
        assertThat(price.reasons()).containsExactly("A", "B");
        assertThat(PriceModifiers.buy(cook, "BREAD", Money.of(1)).price()).isEqualTo(Money.of(1));
    }

    @Test
    @DisplayName("two modifiers add up, and a zero change is no change")
    void stacking() {
        PriceModifiers.provide(owner, cookRole);
        PriceModifiers.provide(owner, (player, material, side) -> Optional.of(new PriceChange(-5, "Event")));
        PriceModifiers.provide(owner, (player, material, side) -> Optional.of(new PriceChange(0, "Nothing")));
        PersonalPrice bread = PriceModifiers.buy(cook, "BREAD", Money.of(1000));
        assertThat(bread.percent()).isEqualTo(-30);
        assertThat(bread.reasons()).containsExactly("Cook", "Event");
    }

    @Test
    @DisplayName("a modifier that throws or answers null is skipped, the rest still count")
    void broken() {
        PriceModifiers.provide(owner, (player, material, side) -> {
            throw new IllegalStateException("roles are loading");
        });
        PriceModifiers.provide(owner, (player, material, side) -> null);
        PriceModifiers.provide(owner, cookRole);
        assertThat(PriceModifiers.buy(cook, "BREAD", Money.of(1000)).price()).isEqualTo(Money.of(750));
    }

    @Test
    @DisplayName("nothing to price, or no player, is the base price")
    void degenerate() {
        PriceModifiers.provide(owner, cookRole);
        assertThat(PriceModifiers.buy(null, "BREAD", Money.of(1000)).price()).isEqualTo(Money.of(1000));
        assertThat(PriceModifiers.buy(cook, null, Money.of(1000)).price()).isEqualTo(Money.of(1000));
        assertThat(PriceModifiers.buy(cook, "BREAD", Money.ZERO).price()).isEqualTo(Money.ZERO);
        assertThat(PriceModifiers.buy(cook, "bread", Money.of(1000)).price())
                .as("material names are matched as upper case").isEqualTo(Money.of(750));
    }

    @Test
    @DisplayName("a huge price does not overflow")
    void huge() {
        PriceModifiers.provide(owner, (player, material, side) -> Optional.of(new PriceChange(500, "Greed")));
        assertThat(PriceModifiers.sell(cook, "BREAD", Money.of(Long.MAX_VALUE / 2)).price().minor())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("providing twice is once; retracting and forgetting a plugin's code work")
    void lifecycle() {
        PriceModifiers.provide(owner, cookRole);
        PriceModifiers.provide(owner, cookRole);
        assertThat(PriceModifiers.buy(cook, "BREAD", Money.of(1000)).percent()).isEqualTo(-25);
        PriceModifiers.retract(cookRole);
        assertThat(PriceModifiers.buy(cook, "BREAD", Money.of(1000)).changed()).isFalse();
        PriceModifiers.provide(owner, cookRole);
        assertThat(PriceModifiers.forgetFrom(cookRole.getClass().getClassLoader())).isEqualTo(1);
        assertThat(PriceModifiers.isAnyProvided()).isFalse();
    }

    @Test
    @DisplayName("a change is clamped to something sensible when it is made")
    void clamped() {
        assertThat(new PriceChange(-500, "x").percent()).isEqualTo(-100);
        assertThat(new PriceChange(5000, "x").percent()).isEqualTo(1000);
        assertThat(new PriceChange(5, null).reason()).isEmpty();
    }

    @Test
    @DisplayName("a change applied to a whole line rounds once, so cheap items still get their discount")
    void scaleALine() {
        // One seed at 2 is 1.5 → 2 when rounded up on its own; 64 of them are 96, not 128.
        assertThat(PriceModifiers.scale(Money.of(2), -25, TradeSide.BUY)).isEqualTo(Money.of(2));
        assertThat(PriceModifiers.scale(Money.of(128), -25, TradeSide.BUY)).isEqualTo(Money.of(96));
        assertThat(PriceModifiers.scale(Money.of(15), 10, TradeSide.SELL)).isEqualTo(Money.of(16));
        assertThat(PriceModifiers.scale(Money.of(100), 0, TradeSide.BUY)).isEqualTo(Money.of(100));
        assertThat(PriceModifiers.scale(Money.of(100), -500, TradeSide.BUY)).isEqualTo(Money.of(10));
        assertThat(PriceModifiers.scale(Money.ZERO, -25, TradeSide.BUY)).isEqualTo(Money.ZERO);
    }
}
