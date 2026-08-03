package de.raindancer.core.moderation.vanish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Being properly not here.
 *
 * <h2>Why this is a Core concern and not a moderation plugin's</h2>
 * Because vanish is not one feature, it is a promise every other feature has to keep. A staff member
 * who is hidden but still shows in the tablist, or still counts in "3 players online", or whose join
 * message went out anyway, is not hidden — and each of those is owned by a different subsystem. Only
 * the thing that owns the tablist, the chat and the player list can make the promise hold, which is
 * this library.
 *
 * <p>Every plugin then asks the same question — {@code isVanished} — instead of nine plugins each
 * keeping a set and five of them forgetting to check it.
 *
 * <h2>Why the effects are separate from the hiding</h2>
 * Because they come apart in practice. Somebody may want to be invisible without flying, or to look
 * at a build in creative without being hidden. Bundling them means the one you did not want comes
 * along, and turning it off afterwards is what leaves a moderator stuck in survival at bedrock.
 */
@DisplayName("vanish")
class VanishTest {

    /** What would have been done to the server, instead of a server. */
    private record Did(String what, UUID who) {
    }

    private final List<Did> did = new ArrayList<>();
    private final List<String> announced = new ArrayList<>();

    private Vanish vanish() {
        return new Vanish(new VanishSink() {
            @Override
            public void hide(UUID who, java.util.Set<UUID> mayStillSee) {
                did.add(new Did("hide", who));
            }

            @Override
            public void show(UUID who) {
                did.add(new Did("show", who));
            }

            @Override
            public void allowFlight(UUID who, boolean allowed) {
                did.add(new Did("flight:" + allowed, who));
            }

            @Override
            public void collidable(UUID who, boolean collides) {
                did.add(new Did("collide:" + collides, who));
            }

            @Override
            public void silentJoinLeave(UUID who, boolean silent) {
                announced.add((silent ? "quiet:" : "loud:") + who);
            }
        });
    }

    private static final UUID MOD = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    // ------------------------------------------------------------------ going and coming back

    @Nested
    @DisplayName("going invisible")
    class GoingAway {

        @Test
        @DisplayName("somebody can vanish")
        void vanishes() {
            Vanish vanish = vanish();
            assertThat(vanish.vanish(MOD)).isTrue();

            assertThat(vanish.isVanished(MOD)).isTrue();
            assertThat(did).extracting(Did::what).contains("hide");
        }

        @Test
        @DisplayName("vanishing twice changes nothing and is not an error")
        void isIdempotent() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            did.clear();

            assertThat(vanish.vanish(MOD)).isFalse();
            assertThat(did)
                    .as("re-hiding somebody already hidden re-sends packets to every player on "
                            + "the server for nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("coming back undoes it")
        void comesBack() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(vanish.reveal(MOD)).isTrue();

            assertThat(vanish.isVanished(MOD)).isFalse();
            assertThat(did).extracting(Did::what).contains("show");
        }

        @Test
        @DisplayName("revealing somebody who was never hidden is not an error")
        void revealingNobody() {
            assertThat(vanish().reveal(MOD)).isFalse();
        }

        @Test
        @DisplayName("it can be toggled")
        void toggles() {
            Vanish vanish = vanish();
            assertThat(vanish.toggle(MOD)).isTrue();
            assertThat(vanish.toggle(MOD)).isFalse();
            assertThat(vanish.isVanished(MOD)).isFalse();
        }

        @Test
        @DisplayName("everybody hidden can be listed, which is the question every plugin asks")
        void listsThem() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(vanish.everybodyVanished()).containsExactly(MOD);
            assertThat(vanish.isVanished(OTHER)).isFalse();
        }
    }

    // ------------------------------------------------------------------ the promise

    /**
     * The half that makes vanish mean something. Somebody hidden who still shows in the tablist, or
     * still counts in "3 players online", is not hidden.
     */
    @Nested
    @DisplayName("being properly gone")
    class BeingGone {

        @Test
        @DisplayName("they do not appear in a list of who is online")
        void notInTheList() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);

            assertThat(vanish.visibleOf(List.of(MOD, OTHER)))
                    .as("this is the call every plugin has to make instead of getOnlinePlayers")
                    .containsExactly(OTHER);
        }

        @Test
        @DisplayName("they do not count towards how many are online")
        void notInTheCount() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(vanish.countOf(List.of(MOD, OTHER))).isEqualTo(1);
        }

        @Test
        @DisplayName("somebody who may see them still sees them")
        void staffSeeEachOther() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);

            assertThat(vanish.canSee(OTHER, MOD)).isFalse();
            assertThat(vanish.canSee(MOD, MOD))
                    .as("a moderator who cannot see themselves has been made to disappear rather "
                            + "than hidden")
                    .isTrue();

            vanish.maySeeVanished(OTHER, true);
            assertThat(vanish.canSee(OTHER, MOD))
                    .as("other staff have to be able to see each other or they walk into one "
                            + "another all night")
                    .isTrue();
        }

        @Test
        @DisplayName("their joining and leaving is quiet")
        void noJoinMessage() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(announced).contains("quiet:" + MOD);

            vanish.reveal(MOD);
            assertThat(announced).contains("loud:" + MOD);
        }

        @Test
        @DisplayName("they do not bump into anybody")
        void noCollisions() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(did).extracting(Did::what)
                    .as("an invisible wall that shoves players around is worse than being seen")
                    .contains("collide:false");
        }
    }

    // ------------------------------------------------------------------ the extras

    @Nested
    @DisplayName("what comes with it")
    class Extras {

        @Test
        @DisplayName("flight is offered but not forced")
        void flightIsAChoice() {
            Vanish vanish = vanish();
            vanish.flightWhileVanished(false);
            vanish.vanish(MOD);

            assertThat(did).extracting(Did::what).doesNotContain("flight:true");
        }

        @Test
        @DisplayName("with it on, flight comes and goes with the vanish")
        void flightFollows() {
            Vanish vanish = vanish();
            vanish.flightWhileVanished(true);
            vanish.vanish(MOD);
            assertThat(did).extracting(Did::what).contains("flight:true");

            did.clear();
            vanish.reveal(MOD);
            assertThat(did).extracting(Did::what)
                    .as("leaving somebody flying after they come back is how a moderator ends up "
                            + "explaining why they can fly")
                    .contains("flight:false");
        }

        @Test
        @DisplayName("somebody who could already fly keeps flying afterwards")
        void doesNotTakeAwayWhatWasAlreadyThere() {
            Vanish vanish = vanish();
            vanish.flightWhileVanished(true);
            vanish.vanish(MOD, true);

            did.clear();
            vanish.reveal(MOD);
            assertThat(did).extracting(Did::what)
                    .as("a creative-mode builder who vanished must not land in the void when "
                            + "they come back")
                    .doesNotContain("flight:false");
        }
    }

    // ------------------------------------------------------------------ not losing track

    @Nested
    @DisplayName("not losing track of anybody")
    class Bookkeeping {

        @Test
        @DisplayName("somebody who leaves while hidden is still hidden when they return")
        void survivesALeave() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            vanish.forgetSession(MOD);

            assertThat(vanish.isVanished(MOD))
                    .as("a moderator who reconnects and is suddenly visible has been given away "
                            + "by the plugin that was hiding them")
                    .isTrue();
        }

        @Test
        @DisplayName("everything can be undone at once, for a shutdown")
        void revealsEverybody() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            vanish.vanish(OTHER);

            assertThat(vanish.revealEverybody()).isEqualTo(2);
            assertThat(vanish.everybodyVanished()).isEmpty();
        }

        @Test
        @DisplayName("who may see hidden players is remembered per person")
        void seeingIsPerPerson() {
            Vanish vanish = vanish();
            vanish.maySeeVanished(MOD, true);
            assertThat(vanish.maySeeVanished(MOD)).isTrue();
            assertThat(vanish.maySeeVanished(OTHER)).isFalse();

            vanish.maySeeVanished(MOD, false);
            assertThat(vanish.maySeeVanished(MOD)).isFalse();
        }
    }
}
