package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core asking a question it does not know the answer to.
 *
 * <p>The behaviour worth pinning is <b>what happens with nobody answering</b>. Core ships the protection and
 * the vocabulary; the ground itself comes from a plugin that may not be installed, may have failed to start,
 * or may have been removed between restarts. Every one of those is the same state as far as Core is concerned,
 * and it must not look like "there is nothing protected here".
 *
 * <p>That distinction is not theoretical. The farm-world regeneration deletes three worlds, and it asks this
 * class first. Answering "allowed" when the truth is "I have no idea" is how somebody's house goes away.
 */
class LandTest {

    private final LandPolicies policies = LandPolicies.builtIn();
    private final Messages messages = new Messages(Path.of("target", "land-test-messages.yml"));
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private Land land;

    /**
     * The real wording, read out of the jar's own {@code messages.yml}.
     *
     * <p>Loaded rather than stubbed because the neutral wording <em>is</em> the thing being checked: a flag or
     * an action whose key is missing from that file renders as the key, and a test with its own strings would
     * never notice.
     */
    private void loadTheShippedWording() {
        messages.load(getClass().getResourceAsStream("/messages.yml"));
    }

    /** A world with nothing behind it — only its identity is ever used. */
    private final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "toString" -> "a fake world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });

    /** A provider that answers from a map, and counts how often it was asked. */
    private static final class Fake implements LandProvider {
        final Map<String, ProtectedArea> byKey = new HashMap<>();
        boolean anythingHere;
        int lookups;

        @Override
        public String name() {
            return "the fake provider";
        }

        @Override
        public Optional<ProtectedArea> at(Location location) {
            lookups++;
            return Optional.ofNullable(byKey.get("here"));
        }

        @Override
        public boolean hasAnyIn(World world) {
            return anythingHere;
        }
    }

    private final Fake provider = new Fake();

    /** Somewhere in that world. Never resolved against anything — only handed back to the fake provider. */
    private Location somewhere;

    @BeforeEach
    void setUp() {
        loadTheShippedWording();
        land = new Land(policies, messages, clock::get);
        somewhere = new Location(world, 8, 64, 8);
    }

    @Nested
    @DisplayName("with nobody answering")
    class NoProvider {

        @Test
        void thereIsNoAreaAnywhere() {
            assertThat(land.hasProvider()).isFalse();
            assertThat(land.areaAt(somewhere)).isEmpty();
        }

        @Test
        void everyQuestionAnswersUnknownRatherThanYes() {
            assertThat(land.verdict(new FakePlayer().player(), somewhere, LandAction.BUILD))
                    .isEqualTo(LandVerdict.UNKNOWN);
        }

        @Test
        void andWhatSweepsAWholeWorldIsRefusedByThat() {
            // The one that matters. UNKNOWN read with orRefuse() stops a regeneration; read as "allowed" it
            // deletes three worlds on the strength of a plugin nobody installed.
            LandVerdict verdict = land.safeToReshape(world);

            assertThat(verdict).isEqualTo(LandVerdict.UNKNOWN);
            assertThat(verdict.orRefuse()).isFalse();
            assertThat(verdict.orAllow()).isTrue();
        }

        @Test
        void anOrdinaryActionStillGoesAheadBecauseNothingSaidNo() {
            // A server with no land plugin should behave as though land did not exist.
            assertThat(land.can(new FakePlayer().player(), somewhere, LandAction.BUILD)).isTrue();
        }
    }

    @Nested
    @DisplayName("registering the one who answers")
    class Registering {

        @Test
        void theFirstOneGetsTheJob() {
            assertThat(land.provider(provider)).isTrue();
            assertThat(land.hasProvider()).isTrue();
            assertThat(land.provider()).containsSame(provider);
        }

        @Test
        void aSecondOneIsRefusedRatherThanQuietlyReplacingTheFirst() {
            // Two answers for the same block cannot both be enforced, and silently taking the newer one
            // would mean load order decided whose rules a server ran.
            land.provider(provider);
            Fake other = new Fake();

            assertThat(land.provider(other)).isFalse();
            assertThat(land.provider()).containsSame(provider);
        }

        @Test
        void nothingIsNotAProvider() {
            assertThat(land.provider(null)).isFalse();
            assertThat(land.hasProvider()).isFalse();
        }

        @Test
        void standingDownLeavesNobodyAnswering() {
            land.provider(provider);
            land.withdraw(provider);

            assertThat(land.hasProvider()).isFalse();
            assertThat(land.safeToReshape(world)).isEqualTo(LandVerdict.UNKNOWN);
        }

        @Test
        void standingSomebodyElseDownChangesNothing() {
            // A module shutting down must not be able to unregister a different module's provider.
            land.provider(provider);
            land.withdraw(new Fake());

            assertThat(land.provider()).containsSame(provider);
        }

        @Test
        void afterStandingDownTheJobIsOpenAgain() {
            land.provider(provider);
            land.withdraw(provider);
            Fake replacement = new Fake();

            assertThat(land.provider(replacement)).isTrue();
        }
    }

    @Nested
    @DisplayName("with somebody answering")
    class WithProvider {

        @BeforeEach
        void register() {
            land.provider(provider);
        }

        @Test
        void unprotectedGroundIsAllowedRatherThanUnknown() {
            assertThat(land.verdict(new FakePlayer().player(), somewhere, LandAction.BUILD))
                    .isEqualTo(LandVerdict.ALLOWED);
        }

        @Test
        void anOwnerMayDoAnything() {
            FakePlayer owner = new FakePlayer();
            provider.byKey.put("here", FakeArea.named("home").ownedBy(owner.id()));

            assertThat(land.verdict(owner.player(), somewhere, LandAction.BREAK))
                    .isEqualTo(LandVerdict.ALLOWED);
        }

        @Test
        void aStrangerIsRefused() {
            provider.byKey.put("here", FakeArea.named("home").ownedBy(java.util.UUID.randomUUID()));

            assertThat(land.verdict(new FakePlayer().player(), somewhere, LandAction.BREAK))
                    .isEqualTo(LandVerdict.REFUSED);
        }

        @Test
        void somebodyTrustedGetsExactlyWhatTheyWereGiven() {
            FakePlayer friend = new FakePlayer();
            provider.byKey.put("here", FakeArea.named("home")
                    .ownedBy(java.util.UUID.randomUUID())
                    .trusting(friend.id(), LandAction.BUILD));

            assertThat(land.can(friend.player(), somewhere, LandAction.BUILD)).isTrue();
            assertThat(land.can(friend.player(), somewhere, LandAction.CONTAINERS)).isFalse();
        }

        @Test
        void aWorldWithSomethingInItIsNotSafeToReshape() {
            provider.anythingHere = true;
            assertThat(land.safeToReshape(world)).isEqualTo(LandVerdict.REFUSED);
            assertThat(land.safeToReshape(world).orRefuse()).isFalse();
        }

        @Test
        void aWorldWithNothingInItIs() {
            provider.anythingHere = false;
            assertThat(land.safeToReshape(world)).isEqualTo(LandVerdict.ALLOWED);
            assertThat(land.safeToReshape(world).orRefuse()).isTrue();
        }
    }

    @Nested
    @DisplayName("the admin bypass")
    class Bypass {

        @BeforeEach
        void register() {
            land.provider(provider);
            provider.byKey.put("here", FakeArea.named("home").ownedBy(java.util.UUID.randomUUID()));
        }

        @Test
        void doesNothingUntilItIsSwitchedOn() {
            FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
            assertThat(land.can(admin.player(), somewhere, LandAction.BREAK)).isFalse();
        }

        @Test
        void opensEverythingOnceItIs() {
            FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
            assertThat(land.toggleBypass(admin.player())).isTrue();
            assertThat(land.can(admin.player(), somewhere, LandAction.BREAK)).isTrue();
        }

        @Test
        void acceptsEitherSpellingOfThePermission() {
            // The old node stays valid so an upgrade does not take the bypass away from the admins who have
            // it; the new one exists so a fresh setup can spell it the way the rest of Core does.
            FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION_CORE);
            land.toggleBypass(admin.player());
            assertThat(land.can(admin.player(), somewhere, LandAction.BREAK)).isTrue();
        }

        @Test
        void isWorthlessWithoutThePermission() {
            FakePlayer notAnAdmin = new FakePlayer();
            land.toggleBypass(notAnAdmin.player());
            assertThat(land.can(notAnAdmin.player(), somewhere, LandAction.BREAK)).isFalse();
        }

        @Test
        void togglesBackOff() {
            FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
            land.toggleBypass(admin.player());
            assertThat(land.toggleBypass(admin.player())).isFalse();
            assertThat(land.can(admin.player(), somewhere, LandAction.BREAK)).isFalse();
        }

        @Test
        void isForgottenWhenTheyLeave() {
            FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
            land.toggleBypass(admin.player());

            land.forget(admin.id());

            assertThat(land.can(admin.player(), somewhere, LandAction.BREAK)).isFalse();
        }

        @Nested
        @DisplayName("the reminder to switch it back off")
        class Reminder {

            private final java.time.Duration after = java.time.Duration.ofMinutes(10);

            @Test
            void nobodyIsDueTheMomentItGoesOn() {
                FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
                land.toggleBypass(admin.player());

                assertThat(land.dueForBypassReminder(after)).isEmpty();
            }

            @Test
            void isDueOnceTheIntervalHasPassed() {
                FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
                land.toggleBypass(admin.player());

                clock.addAndGet(after.toMillis());

                assertThat(land.dueForBypassReminder(after)).containsExactly(admin.id());
            }

            @Test
            void postponingPutsTheClockBackToNow() {
                FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
                land.toggleBypass(admin.player());
                clock.addAndGet(after.toMillis());

                land.postponeBypassReminder(admin.id());

                assertThat(land.dueForBypassReminder(after))
                        .as("asked or not, being checked at all buys another full interval")
                        .isEmpty();
            }

            @Test
            void silencingStopsItBeingAskedAgainThisSession() {
                FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
                land.toggleBypass(admin.player());
                clock.addAndGet(after.toMillis());

                land.silenceBypassReminder(admin.id());

                assertThat(land.dueForBypassReminder(after)).isEmpty();
            }

            @Test
            void togglingOffAndOnAgainForgetsTheSilence() {
                // A silenced reminder is a decision about this bypass, not about this player forever —
                // otherwise switching it on next week for something else never asks again either.
                FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
                land.toggleBypass(admin.player());
                land.silenceBypassReminder(admin.id());

                land.toggleBypass(admin.player());
                land.toggleBypass(admin.player());
                clock.addAndGet(after.toMillis());

                assertThat(land.dueForBypassReminder(after)).containsExactly(admin.id());
            }

            @Test
            void leavingForgetsBothTheClockAndTheSilence() {
                FakePlayer admin = new FakePlayer().holding(Land.BYPASS_PERMISSION);
                land.toggleBypass(admin.player());
                land.silenceBypassReminder(admin.id());

                land.forget(admin.id());
                land.toggleBypass(admin.player());
                clock.addAndGet(after.toMillis());

                assertThat(land.dueForBypassReminder(after))
                        .as("a fresh session after leaving is not still silenced from the last one")
                        .containsExactly(admin.id());
            }
        }
    }

    @Nested
    @DisplayName("the refusal a player sees")
    class Refusals {

        private final FakeArea area = FakeArea.named("Raindancer118's home");

        @Test
        void isSentWhenSomethingIsRefused() {
            FakePlayer stranger = new FakePlayer();
            land.deny(stranger.player(), area, LandAction.BUILD);
            assertThat(stranger.actionBar()).hasSize(1);
        }

        @Test
        void namesTheGroundSoTheyKnowWhoseItIs() {
            FakePlayer stranger = new FakePlayer();
            land.deny(stranger.player(), area, LandAction.BUILD);
            assertThat(stranger.actionBar().getFirst()).contains("Raindancer118's home");
        }

        @Test
        void isNotRepeatedForEveryBlockOfARunOfThem() {
            // A player holding down the left mouse button generates one of these per tick. Twenty identical
            // lines a second is not a message, it is a denial of service on the chat box.
            FakePlayer stranger = new FakePlayer();
            for (int attempt = 0; attempt < 40; attempt++) {
                land.deny(stranger.player(), area, LandAction.BUILD);
                clock.addAndGet(50L);
            }
            assertThat(stranger.actionBar()).hasSizeLessThan(3);
        }

        @Test
        void comesBackOnceTheyHaveHadTimeToReadIt() {
            FakePlayer stranger = new FakePlayer();
            land.deny(stranger.player(), area, LandAction.BUILD);
            clock.addAndGet(10_000L);
            land.deny(stranger.player(), area, LandAction.BUILD);
            assertThat(stranger.actionBar()).hasSize(2);
        }

        @Test
        void isThrottledPerPlayerRatherThanServerWide() {
            FakePlayer one = new FakePlayer();
            FakePlayer other = new FakePlayer();
            land.deny(one.player(), area, LandAction.BUILD);
            land.deny(other.player(), area, LandAction.BUILD);
            assertThat(one.actionBar()).hasSize(1);
            assertThat(other.actionBar()).hasSize(1);
        }

        @Test
        void theThrottleIsForgottenWhenTheyLeave() {
            FakePlayer stranger = new FakePlayer();
            land.deny(stranger.player(), area, LandAction.BUILD);
            land.forget(stranger.id());
            land.deny(stranger.player(), area, LandAction.BUILD);
            assertThat(stranger.actionBar()).hasSize(2);
        }
    }
}
