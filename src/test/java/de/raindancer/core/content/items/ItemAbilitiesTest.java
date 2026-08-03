package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Items that do something.
 *
 * <h2>What this is modelled on</h2>
 * The custom items in the Hunger Games plugins: a wooden axe that calls lightning where you are
 * looking, a fishing rod that reels a player in, a snowball that swaps your position with theirs.
 * What they all have in common is the shape rather than the effect — a <em>trigger</em> (right
 * click, hit somebody, eat it), a <em>cooldown</em> so it is not spammed, and often a number of
 * <em>charges</em> after which the item is used up.
 *
 * <p>So that shape is here and the effects are the plugin's. A plugin registers what its ability
 * does; this decides whether it may run right now, and says why not when it may not.
 */
class ItemAbilitiesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private AtomicLong clock;
    private ItemAbilities abilities;
    private List<UUID> fired;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        abilities = new ItemAbilities(clock::get);
        fired = new ArrayList<>();
    }

    private void advance(Duration by) {
        clock.addAndGet(by.toMillis());
    }

    private ItemAbility lightning() {
        return ItemAbility.builder("hg", "lightning")
                .on(ItemTrigger.RIGHT_CLICK)
                .cooldown(Duration.ofSeconds(30))
                .charges(3)
                .describedAs("Calls lightning where you are looking")
                .does(use -> fired.add(use.player()))
                .build();
    }

    // ------------------------------------------------------------------ triggering

    @Nested
    @DisplayName("using an ability")
    class Using {

        @BeforeEach
        void register() {
            abilities.register(lightning());
        }

        @Test
        @DisplayName("the right trigger fires it")
        void firesOnItsTrigger() {
            assertThat(abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
            assertThat(fired).containsExactly(ALICE);
        }

        @Test
        @DisplayName("the wrong trigger does not")
        void ignoresOtherTriggers() {
            UseResult result = abilities.use(ALICE, "hg:lightning", ItemTrigger.HIT_ENTITY);
            assertThat(result.ran()).isFalse();
            assertThat(result.outcome()).isEqualTo(UseOutcome.WRONG_TRIGGER);
            assertThat(fired).isEmpty();
        }

        @Test
        @DisplayName("an ability nobody registered is unknown, not an error")
        void unknownAbility() {
            assertThat(abilities.use(ALICE, "hg:nothing", ItemTrigger.RIGHT_CLICK).outcome())
                    .isEqualTo(UseOutcome.UNKNOWN);
        }

        @Test
        @DisplayName("an ability that throws is reported as failed, not as having worked")
        void survivesABrokenAbility() {
            abilities.register(ItemAbility.builder("hg", "broken")
                    .on(ItemTrigger.RIGHT_CLICK)
                    .does(use -> {
                        throw new IllegalStateException("no");
                    })
                    .build());

            UseResult result = abilities.use(ALICE, "hg:broken", ItemTrigger.RIGHT_CLICK);
            assertThat(result.outcome()).isEqualTo(UseOutcome.FAILED);
            assertThat(result.ran()).isFalse();
        }

        @Test
        @DisplayName("an ability can refuse itself — there was nothing to aim at")
        void anAbilityMayDecline() {
            abilities.register(ItemAbility.builder("hg", "reel")
                    .on(ItemTrigger.RIGHT_CLICK)
                    .cooldown(Duration.ofSeconds(10))
                    .attempts(use -> false)
                    .build());

            UseResult result = abilities.use(ALICE, "hg:reel", ItemTrigger.RIGHT_CLICK);
            assertThat(result.outcome()).isEqualTo(UseOutcome.DECLINED);
            // ...and a shot that missed must not start the cooldown.
            assertThat(abilities.use(ALICE, "hg:reel", ItemTrigger.RIGHT_CLICK).outcome())
                    .isEqualTo(UseOutcome.DECLINED);
        }
    }

    // ------------------------------------------------------------------ cooldowns

    @Nested
    @DisplayName("the cooldown")
    class Cooldowns {

        @BeforeEach
        void register() {
            abilities.register(lightning());
        }

        @Test
        @DisplayName("stops it being used again straight away")
        void blocksASecondUse() {
            abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            UseResult second = abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);

            assertThat(second.outcome()).isEqualTo(UseOutcome.ON_COOLDOWN);
            assertThat(fired).hasSize(1);
        }

        @Test
        @DisplayName("says how long is left, so the player can be told")
        void reportsTheWait() {
            abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            advance(Duration.ofSeconds(10));

            assertThat(abilities.remaining(ALICE, "hg:lightning"))
                    .contains(Duration.ofSeconds(20));
            assertThat(abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK).remaining())
                    .contains(Duration.ofSeconds(20));
        }

        @Test
        @DisplayName("wears off")
        void expires() {
            abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            advance(Duration.ofSeconds(31));

            assertThat(abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
            assertThat(fired).hasSize(2);
        }

        @Test
        @DisplayName("is per player, so one player's use does not block another's")
        void isPerPlayer() {
            abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            assertThat(abilities.use(BOB, "hg:lightning", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        }

        @Test
        @DisplayName("is per ability, so one item's cooldown does not block another")
        void isPerAbility() {
            abilities.register(ItemAbility.builder("hg", "swap")
                    .on(ItemTrigger.RIGHT_CLICK).cooldown(Duration.ofSeconds(30))
                    .does(use -> fired.add(use.player())).build());

            abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            assertThat(abilities.use(ALICE, "hg:swap", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        }

        @Test
        @DisplayName("an ability with no cooldown can be used as fast as somebody can click")
        void noCooldown() {
            abilities.register(ItemAbility.builder("hg", "free")
                    .on(ItemTrigger.RIGHT_CLICK).does(use -> fired.add(use.player())).build());

            abilities.use(ALICE, "hg:free", ItemTrigger.RIGHT_CLICK);
            assertThat(abilities.use(ALICE, "hg:free", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
            assertThat(abilities.remaining(ALICE, "hg:free")).isEmpty();
        }

        @Test
        @DisplayName("a player who leaves takes their cooldowns with them")
        void forgetsPlayers() {
            abilities.use(ALICE, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            abilities.forget(ALICE);
            assertThat(abilities.remaining(ALICE, "hg:lightning")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ charges

    @Nested
    @DisplayName("charges")
    class Charges {

        @Test
        @DisplayName("an ability with charges runs out")
        void runsOut() {
            abilities.register(ItemAbility.builder("hg", "bomb")
                    .on(ItemTrigger.RIGHT_CLICK).charges(2)
                    .does(use -> fired.add(use.player())).build());

            assertThat(abilities.use(ALICE, "hg:bomb", ItemTrigger.RIGHT_CLICK).chargesLeft())
                    .contains(1);
            assertThat(abilities.use(ALICE, "hg:bomb", ItemTrigger.RIGHT_CLICK).chargesLeft())
                    .contains(0);

            UseResult spent = abilities.use(ALICE, "hg:bomb", ItemTrigger.RIGHT_CLICK);
            assertThat(spent.outcome()).isEqualTo(UseOutcome.NO_CHARGES);
            assertThat(fired).hasSize(2);
        }

        @Test
        @DisplayName("the last charge says the item should be taken away")
        void reportsWhenSpent() {
            abilities.register(ItemAbility.builder("hg", "single")
                    .on(ItemTrigger.RIGHT_CLICK).charges(1)
                    .does(use -> fired.add(use.player())).build());

            UseResult only = abilities.use(ALICE, "hg:single", ItemTrigger.RIGHT_CLICK);
            assertThat(only.ran()).isTrue();
            assertThat(only.itemIsSpent())
                    .as("a one-use item should vanish from the hand that used it")
                    .isTrue();
        }

        @Test
        @DisplayName("charges are per player, like cooldowns")
        void chargesArePerPlayer() {
            abilities.register(ItemAbility.builder("hg", "bomb")
                    .on(ItemTrigger.RIGHT_CLICK).charges(1)
                    .does(use -> fired.add(use.player())).build());

            abilities.use(ALICE, "hg:bomb", ItemTrigger.RIGHT_CLICK);
            assertThat(abilities.use(BOB, "hg:bomb", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        }

        @Test
        @DisplayName("an ability with no charges never runs out")
        void unlimited() {
            abilities.register(ItemAbility.builder("hg", "free")
                    .on(ItemTrigger.RIGHT_CLICK).does(use -> fired.add(use.player())).build());

            for (int use = 0; use < 50; use++) {
                assertThat(abilities.use(ALICE, "hg:free", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
            }
            assertThat(abilities.use(ALICE, "hg:free", ItemTrigger.RIGHT_CLICK).chargesLeft())
                    .isEmpty();
        }

        @Test
        @DisplayName("charges can be given back, for a round starting again")
        void resets() {
            abilities.register(ItemAbility.builder("hg", "bomb")
                    .on(ItemTrigger.RIGHT_CLICK).charges(1)
                    .does(use -> fired.add(use.player())).build());

            abilities.use(ALICE, "hg:bomb", ItemTrigger.RIGHT_CLICK);
            abilities.reset(ALICE);
            assertThat(abilities.use(ALICE, "hg:bomb", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        }
    }

    // ------------------------------------------------------------------ binding to an item

    @Test
    @DisplayName("an item carries which ability it has, so the listener needs only the stack")
    void itemsCarryTheirAbility() {
        CustomItem axe = CustomItem.builder("hg", "thor")
                .material(Material.WOODEN_AXE)
                .name("<gold>Thor's Axe")
                .ability("hg:lightning")
                .build();

        assertThat(axe.ability()).contains("hg:lightning");
        assertThat(CustomItem.builder("hg", "plain").material(Material.STICK).build().ability())
                .isEmpty();
    }

    // ------------------------------------------------------------------ misuse

    @Test
    @DisplayName("nulls do not throw")
    void survivesNulls() {
        assertThatCode(() -> {
            abilities.use(null, "hg:lightning", ItemTrigger.RIGHT_CLICK);
            abilities.use(ALICE, null, ItemTrigger.RIGHT_CLICK);
            abilities.use(ALICE, "hg:lightning", null);
            abilities.register(null);
            abilities.forget(null);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an ability needs something to do")
    void refusesAnEmptyAbility() {
        assertThatCode(() -> ItemAbility.builder("hg", "nothing").on(ItemTrigger.RIGHT_CLICK).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("a one-charge ability clicked by many threads fires exactly once")
    void isSafeFromEveryThread() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        abilities.register(ItemAbility.builder("hg", "single")
                .on(ItemTrigger.RIGHT_CLICK).charges(1)
                .does(use -> ran.incrementAndGet()).build());

        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                pool.submit(() -> {
                    go.await();
                    abilities.use(ALICE, "hg:single", ItemTrigger.RIGHT_CLICK);
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(ran.get())
                .as("a client sending eight click packets must not fire a one-use item eight times")
                .isEqualTo(1);
    }
}
