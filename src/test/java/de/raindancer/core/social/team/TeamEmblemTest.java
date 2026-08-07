package de.raindancer.core.social.team;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second dimension of a team's identity: that it exists, that it is unique, and that it renders.
 *
 * <p>The whole feature answers one question — how does a server run more than sixteen teams — and the answer
 * is only true if three things hold. Every glyph has to be distinct, or two teams look the same. Every glyph
 * has to be one the client can draw, or a team's identity is a hollow box. And the plain team has to stay
 * plain, or a server with four teams is made to care about a mechanism it does not need.
 */
class TeamEmblemTest {

    @Nested
    @DisplayName("the ceiling this raises")
    class Capacity {

        @Test
        @DisplayName("there are many more identities than there are colours")
        void farMoreThanSixteen() {
            assertThat(TeamColour.values())
                    .as("sixteen, and it cannot be more — Minecraft's chat and scoreboard colours are a "
                            + "set of sixteen")
                    .hasSize(16);

            assertThat(TeamEmblem.distinctIdentities())
                    .as("colours times emblems. The number a screen shows an owner asking how many teams "
                            + "they may have")
                    .isEqualTo(16 * TeamEmblem.values().length)
                    .isGreaterThan(200);
        }
    }

    @Nested
    @DisplayName("every emblem is its own")
    class Distinctness {

        @Test
        @DisplayName("no two share a glyph")
        void glyphsAreUnique() {
            Set<String> glyphs = new HashSet<>();
            for (TeamEmblem emblem : TeamEmblem.values()) {
                assertThat(glyphs.add(emblem.glyph()))
                        .as("%s repeats a glyph — two teams would then look identical, which is the one "
                                + "thing this enum exists to prevent", emblem)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("no two share a name")
        void titlesAreUnique() {
            Set<String> titles = new HashSet<>();
            for (TeamEmblem emblem : TeamEmblem.values()) {
                assertThat(titles.add(emblem.title()))
                        .as("%s repeats a title, so a picker shows two entries with one name", emblem)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every glyph is a single character the default font can draw")
        void oneCharacterEach() {
            for (TeamEmblem emblem : TeamEmblem.visible()) {
                assertThat(emblem.glyph().codePointCount(0, emblem.glyph().length()))
                        .as("%s is more than one character. Every character costs width a player's name "
                                + "needs on a scoreboard", emblem)
                        .isEqualTo(1);

                int code = emblem.glyph().codePointAt(0);
                assertThat(code)
                        .as("%s is outside the range Minecraft's default font covers, so it renders as a "
                                + "hollow box — a team with no identity at all, which nobody would notice "
                                + "until somebody joined it", emblem)
                        .isBetween(0x2000, 0x27BF);
            }
        }
    }

    @Nested
    @DisplayName("the plain team")
    class Plain {

        @Test
        @DisplayName("comes first, shows nothing, and adds no space")
        void nothingImposed() {
            assertThat(TeamEmblem.values()[0])
                    .as("a server with four teams should never meet this mechanism")
                    .isEqualTo(TeamEmblem.NONE);
            assertThat(TeamEmblem.NONE.isVisible()).isFalse();
            assertThat(TeamEmblem.NONE.glyph()).isEmpty();
            assertThat(TeamEmblem.NONE.prefix())
                    .as("a prefix of \" \" would put a leading space in front of every plain team's name — "
                            + "invisible in a diff and obvious on a scoreboard")
                    .isEmpty();
        }

        @Test
        @DisplayName("is not in the visible list")
        void visibleMeansVisible() {
            assertThat(TeamEmblem.visible())
                    .doesNotContain(TeamEmblem.NONE)
                    .hasSize(TeamEmblem.values().length - 1);
        }
    }

    @Nested
    @DisplayName("reading one back")
    class Parsing {

        @Test
        @DisplayName("by key, however it was typed")
        void byKey() {
            assertThat(TeamEmblem.named("diamond")).contains(TeamEmblem.DIAMOND);
            assertThat(TeamEmblem.named("  DIAMOND  ")).contains(TeamEmblem.DIAMOND);
        }

        @Test
        @DisplayName("by the glyph itself, which is what somebody copies out of chat")
        void byGlyph() {
            assertThat(TeamEmblem.named("♦")).contains(TeamEmblem.DIAMOND);
        }

        @Test
        @DisplayName("something unrecognised is empty, never a default")
        void nothingSilentlyBecomesPlain() {
            // A stored emblem that quietly became NONE would merge two teams' identities with nothing
            // saying so — exactly the collision the enum exists to avoid.
            assertThat(TeamEmblem.named("sparkle")).isEmpty();
            assertThat(TeamEmblem.named("")).isEmpty();
            assertThat(TeamEmblem.named(null)).isEmpty();
        }

        @Test
        @DisplayName("keys survive a round trip")
        void roundTrip() {
            for (TeamEmblem emblem : TeamEmblem.values()) {
                if (emblem == TeamEmblem.NONE) {
                    // Its key is "none" and its glyph is empty; named("") is deliberately empty, so the
                    // round trip is by key only.
                    assertThat(TeamEmblem.named(emblem.key())).contains(emblem);
                    continue;
                }
                assertThat(TeamEmblem.named(emblem.key())).contains(emblem);
                assertThat(TeamEmblem.named(emblem.glyph())).contains(emblem);
            }
        }
    }

    @Nested
    @DisplayName("the badge a team picks for itself")
    class Badges {

        @Test
        @DisplayName("every emblem suggests something, so a team is drawable before anybody chooses")
        void thereIsAlwaysASuggestion() {
            for (TeamEmblem emblem : TeamEmblem.values()) {
                assertThat(emblem.suggestedBadge())
                        .as("%s has no suggested item, so a page of teams has a hole in it until "
                                + "somebody visits the chooser", emblem)
                        .isNotNull()
                        .isNotEqualTo(Material.AIR);
            }
        }

        @Test
        @DisplayName("a team with no chosen badge falls back to its emblem's suggestion")
        void theFallback() {
            Team plain = Team.of(TeamId.fromName("red"), "Red", TeamColour.RED, TeamEmblem.DIAMOND);

            assertThat(plain.badge()).isEqualTo(TeamEmblem.DIAMOND.suggestedBadge());
        }

        @Test
        @DisplayName("a plain team with no emblem falls back to a banner in its own colour, not white")
        void thePlainFallbackIsColoured() {
            // The bug this guards: TeamEmblem.NONE suggests a fixed WHITE_BANNER, so a plain orange team that
            // never visited the item chooser used to be drawn white in every screen reading Team.badge(),
            // while its name and nametag were correctly orange — two data paths for one identity, disagreeing.
            Team plain = Team.of(TeamId.fromName("orange"), "Orange", TeamColour.ORANGE);

            assertThat(plain.badge()).isEqualTo(TeamColour.ORANGE.bannerMaterial());
        }

        @Test
        @DisplayName("a chosen badge is kept exactly, however odd")
        void whateverTheyPicked() {
            Team odd = Team.of(TeamId.fromName("cake"), "Cake", TeamColour.PINK)
                    .withBadge(Material.CAKE);

            // Deliberately not validated against a list of sensible materials. A team that wants to be a
            // cake is a team that wants to be a cake, and refusing it would refuse the only part of a team
            // its members actually chose.
            assertThat(odd.badge()).isEqualTo(Material.CAKE);
        }

        @Test
        @DisplayName("the badge is not part of what tells two teams apart")
        void badgesMayRepeat() {
            Team one = Team.of(TeamId.fromName("one"), "One", TeamColour.RED, TeamEmblem.DIAMOND)
                    .withBadge(Material.CAKE);
            Team two = Team.of(TeamId.fromName("two"), "Two", TeamColour.BLUE, TeamEmblem.CLUB)
                    .withBadge(Material.CAKE);

            assertThat(one.identity())
                    .as("identity is the colour and the emblem; two teams that both like cake is not a "
                            + "problem anybody has")
                    .isNotEqualTo(two.identity());
        }
    }

    @Nested
    @DisplayName("how a team reads")
    class Display {

        @Test
        @DisplayName("the emblem comes before the name")
        void withAnEmblem() {
            Team team = Team.of(TeamId.fromName("red"), "Red", TeamColour.RED, TeamEmblem.DIAMOND);

            assertThat(team.display()).isEqualTo("♦ Red");
        }

        @Test
        @DisplayName("a plain team reads as just its name")
        void withoutOne() {
            assertThat(Team.of(TeamId.fromName("red"), "Red", TeamColour.RED).display())
                    .isEqualTo("Red");
        }

        @Test
        @DisplayName("an identity describes itself for a screen")
        void identitiesReadAloud() {
            assertThat(new Team.Identity(TeamColour.RED, TeamEmblem.DIAMOND).describe())
                    .contains("♦");
            assertThat(new Team.Identity(TeamColour.RED, TeamEmblem.NONE).describe())
                    .doesNotContain(" ♦")
                    .isEqualTo(TeamColour.RED.describe());
        }

        @Test
        @DisplayName("a null emblem is the plain one rather than a crash")
        void nullIsPlain() {
            // Every screen draws this. One null is a page that throws while forty people are picking teams.
            Team team = new Team(TeamId.fromName("red"), "Red", TeamColour.RED, null, null,
                    Set.of(), java.util.Optional.empty());

            assertThat(team.emblem()).isEqualTo(TeamEmblem.NONE);
            assertThat(team.badge()).isNotNull();
        }
    }
}
