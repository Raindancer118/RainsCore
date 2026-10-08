package de.raindancer.core.social.economy;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ItemValuesTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final ItemStack diamond = mock(ItemStack.class);

    @AfterEach
    void reset() {
        ItemValues.clear();
    }

    @Test
    @DisplayName("with nobody pricing items nothing has a value, and asking is not an error")
    void nobody() {
        assertThat(ItemValues.current()).isEmpty();
        assertThat(ItemValues.valueOf(diamond)).isEmpty();
        assertThat(ItemValues.valueOf(null)).isEmpty();
    }

    @Test
    @DisplayName("the newest provider answers, and retracting it hands back to the one before")
    void stacking() {
        ItemValuer cheap = item -> Optional.of(Money.of(100));
        ItemValuer dear = item -> Optional.of(Money.of(900));
        ItemValues.provide(owner, cheap);
        ItemValues.provide(owner, dear);
        assertThat(ItemValues.valueOf(diamond)).contains(Money.of(900));
        ItemValues.retract(dear);
        assertThat(ItemValues.valueOf(diamond)).contains(Money.of(100));
    }

    @Test
    @DisplayName("a provider that throws, or calls something worthless, gives no value rather than a wrong one")
    void brokenOrWorthless() {
        ItemValues.provide(owner, item -> {
            throw new IllegalStateException("prices are being recomputed");
        });
        assertThat(ItemValues.valueOf(diamond)).isEmpty();
        ItemValues.clear();
        ItemValues.provide(owner, item -> Optional.of(Money.ZERO));
        assertThat(ItemValues.valueOf(diamond)).as("zero is not a price").isEmpty();
    }

    @Test
    @DisplayName("what a disabled plugin's code provided is forgotten")
    void forgetFrom() {
        ItemValuer mine = item -> Optional.of(Money.of(1));
        ItemValues.provide(owner, mine);
        assertThat(ItemValues.forgetFrom(mine.getClass().getClassLoader())).isEqualTo(1);
        assertThat(ItemValues.current()).isEmpty();
    }
}
