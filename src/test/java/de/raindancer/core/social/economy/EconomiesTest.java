package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EconomiesTest {

    private final Plugin owner = mock(Plugin.class);
    private final List<String> bridged = new ArrayList<>();

    @AfterEach
    void reset() {
        Economies.clear();
    }

    @Test
    @DisplayName("with nobody providing money there is no economy, and asking is not an error")
    void nobody() {
        assertThat(Economies.current()).isEmpty();
        assertThat(Economies.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("the newest provider answers, and retracting it hands back to the one before")
    void stacking() {
        Economy first = new Fake("first");
        Economy second = new Fake("second");
        Economies.provide(owner, first);
        Economies.provide(owner, second);
        assertThat(Economies.current()).contains(second);

        Economies.retract(second);
        assertThat(Economies.current()).contains(first);
        Economies.retract(first);
        assertThat(Economies.current()).isEmpty();
    }

    @Test
    @DisplayName("providing the same economy twice keeps one entry, so one retract really removes it")
    void idempotent() {
        Economy only = new Fake("only");
        Economies.provide(owner, only);
        Economies.provide(owner, only);
        Economies.retract(only);
        assertThat(Economies.current()).isEmpty();
    }

    @Test
    @DisplayName("the bridge hears every export and retraction, and answers when no Rain economy is here")
    void bridge() {
        Economy foreign = new Fake("foreign");
        Economies.bridge(new EconomyBridge() {
            @Override
            public void exported(Plugin plugin, Economy economy) {
                bridged.add("+" + economy.name());
            }

            @Override
            public void retracted(Economy economy) {
                bridged.add("-" + economy.name());
            }

            @Override
            public Optional<Economy> foreign() {
                return Optional.of(foreign);
            }
        });
        assertThat(Economies.current()).contains(foreign);

        Economy ours = new Fake("ours");
        Economies.provide(owner, ours);
        assertThat(Economies.current()).contains(ours);
        Economies.retract(ours);
        assertThat(bridged).containsExactly("+ours", "-ours");
    }

    @Test
    @DisplayName("an economy whose plugin went away without retracting is dropped by its class loader")
    void forgetting() {
        Economies.provide(owner, new Fake("left behind"));
        assertThat(Economies.forgetFrom(Fake.class.getClassLoader())).isEqualTo(1);
        assertThat(Economies.current()).isEmpty();
    }

    @Test
    @DisplayName("has() compares against the balance without changing it")
    void has() {
        Fake fake = new Fake("x");
        UUID who = UUID.randomUUID();
        fake.money = Money.of(500);
        assertThat(fake.has(who, Money.of(500))).isTrue();
        assertThat(fake.has(who, Money.of(501))).isFalse();
        assertThat(fake.balance(who)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("a result says plainly whether it worked")
    void results() {
        assertThat(EconomyResult.done(Money.of(5), Money.of(10)).succeeded()).isTrue();
        assertThat(EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, Money.of(5), Money.of(1))
                .succeeded()).isFalse();
    }

    private static final class Fake implements Economy {
        private final String name;
        private Money money = Money.ZERO;

        Fake(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Currency currency() {
            return Currency.DEFAULT;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return true;
        }

        @Override
        public Money balance(UUID player) {
            return money;
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            money = money.plus(amount);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            money = money.minus(amount);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }
    }
}
