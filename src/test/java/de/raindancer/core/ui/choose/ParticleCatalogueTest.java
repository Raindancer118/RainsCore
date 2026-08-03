package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorting the particles, and giving each one a face.
 *
 * <h2>Why a particle chooser is harder than a sound one</h2>
 * Because a sound key says what makes it — {@code block.bell.use} is a bell — and a particle name
 * does not. {@code CRIT}, {@code DUST_PLUME}, {@code SCULK_CHARGE_POP}, {@code TRIAL_SPAWNER_DETECTION}:
 * a list of those is a vocabulary test. So the icon has to be chosen by what the particle is
 * <em>for</em>, and the grouping by the same, or the chooser is a wall of grey squares with words on.
 */
@DisplayName("the particle catalogue")
class ParticleCatalogueTest {

    private static final List<String> SOME = List.of(
            "FLAME", "SOUL_FIRE_FLAME", "LAVA", "SMOKE", "LARGE_SMOKE", "CAMPFIRE_COSY_SMOKE",
            "BUBBLE", "SPLASH", "DRIPPING_WATER", "FALLING_WATER", "FISHING",
            "ENCHANT", "ENCHANTED_HIT", "PORTAL", "END_ROD", "WITCH", "DRAGON_BREATH",
            "HEART", "ANGRY_VILLAGER", "HAPPY_VILLAGER", "COMPOSTER", "NOTE",
            "CRIT", "DAMAGE_INDICATOR", "SWEEP_ATTACK", "EXPLOSION", "EXPLOSION_EMITTER",
            "DUST", "DUST_COLOR_TRANSITION", "TRAIL",
            "BLOCK", "ITEM", "FALLING_DUST", "BLOCK_MARKER",
            "CLOUD", "RAIN", "SNOWFLAKE", "WHITE_ASH",
            "SCULK_SOUL", "SCULK_CHARGE_POP", "SHRIEK",
            "SOMETHING_NEW_IN_2031");

    private static ParticleCatalogue catalogue() {
        return new ParticleCatalogue(() -> SOME);
    }

    // ------------------------------------------------------------------ the drawers

    @Nested
    @DisplayName("grouping")
    class Grouping {

        @Test
        @DisplayName("fire and smoke go together")
        void fireAndSmoke() {
            assertThat(ParticleCatalogue.groupOf("FLAME")).isEqualTo(ParticleGroup.FIRE);
            assertThat(ParticleCatalogue.groupOf("LAVA")).isEqualTo(ParticleGroup.FIRE);
            assertThat(ParticleCatalogue.groupOf("LARGE_SMOKE")).isEqualTo(ParticleGroup.FIRE);
        }

        @Test
        @DisplayName("water things go together")
        void water() {
            assertThat(ParticleCatalogue.groupOf("BUBBLE")).isEqualTo(ParticleGroup.WATER);
            assertThat(ParticleCatalogue.groupOf("DRIPPING_WATER")).isEqualTo(ParticleGroup.WATER);
            assertThat(ParticleCatalogue.groupOf("FISHING")).isEqualTo(ParticleGroup.WATER);
        }

        @Test
        @DisplayName("the magical ones go together")
        void magic() {
            assertThat(ParticleCatalogue.groupOf("ENCHANT")).isEqualTo(ParticleGroup.MAGIC);
            assertThat(ParticleCatalogue.groupOf("PORTAL")).isEqualTo(ParticleGroup.MAGIC);
            assertThat(ParticleCatalogue.groupOf("WITCH")).isEqualTo(ParticleGroup.MAGIC);
            assertThat(ParticleCatalogue.groupOf("DRAGON_BREATH")).isEqualTo(ParticleGroup.MAGIC);
        }

        @Test
        @DisplayName("the ones that mean something about a player or mob go together")
        void emotes() {
            assertThat(ParticleCatalogue.groupOf("HEART")).isEqualTo(ParticleGroup.EMOTES);
            assertThat(ParticleCatalogue.groupOf("ANGRY_VILLAGER")).isEqualTo(ParticleGroup.EMOTES);
            assertThat(ParticleCatalogue.groupOf("HAPPY_VILLAGER")).isEqualTo(ParticleGroup.EMOTES);
        }

        @Test
        @DisplayName("fighting")
        void combat() {
            assertThat(ParticleCatalogue.groupOf("CRIT")).isEqualTo(ParticleGroup.COMBAT);
            assertThat(ParticleCatalogue.groupOf("EXPLOSION")).isEqualTo(ParticleGroup.COMBAT);
            assertThat(ParticleCatalogue.groupOf("SWEEP_ATTACK")).isEqualTo(ParticleGroup.COMBAT);
        }

        @Test
        @DisplayName("weather and the air")
        void weather() {
            assertThat(ParticleCatalogue.groupOf("CLOUD")).isEqualTo(ParticleGroup.WEATHER);
            assertThat(ParticleCatalogue.groupOf("SNOWFLAKE")).isEqualTo(ParticleGroup.WEATHER);
            assertThat(ParticleCatalogue.groupOf("RAIN")).isEqualTo(ParticleGroup.WEATHER);
        }

        @Test
        @DisplayName("the ones you colour yourself are their own group, because they behave differently")
        void colourable() {
            assertThat(ParticleCatalogue.groupOf("DUST")).isEqualTo(ParticleGroup.COLOURED);
            assertThat(ParticleCatalogue.groupOf("DUST_COLOR_TRANSITION"))
                    .as("these need extra data to spawn at all; a chooser that hides that fact "
                            + "produces settings that silently do nothing")
                    .isEqualTo(ParticleGroup.COLOURED);
        }

        @Test
        @DisplayName("something added in a future version still lands somewhere")
        void unknownParticles() {
            assertThat(ParticleCatalogue.groupOf("SOMETHING_NEW_IN_2031"))
                    .isEqualTo(ParticleGroup.OTHER);
            assertThat(ParticleCatalogue.groupOf(null)).isEqualTo(ParticleGroup.OTHER);
        }

        @Test
        @DisplayName("nothing is lost between the list and the groups")
        void nothingIsLost() {
            ParticleCatalogue catalogue = catalogue();
            int grouped = catalogue.groups().stream()
                    .mapToInt(group -> catalogue.inGroup(group).size())
                    .sum();
            assertThat(grouped).isEqualTo(SOME.size());
        }

        @Test
        @DisplayName("every group has a title and an icon of its own")
        void groupsHaveChrome() {
            for (ParticleGroup group : ParticleGroup.values()) {
                assertThat(group.title()).isNotBlank();
                assertThat(group.icon()).isNotBlank();
            }
        }
    }

    // ------------------------------------------------------------------ the faces

    @Nested
    @DisplayName("the icon a particle gets")
    class Icons {

        @Test
        @DisplayName("one that names a thing is drawn as that thing")
        void obviousOnes() {
            assertThat(ParticleCatalogue.iconFor("FLAME")).isEqualTo("FIRE_CHARGE");
            assertThat(ParticleCatalogue.iconFor("LAVA")).isEqualTo("LAVA_BUCKET");
            assertThat(ParticleCatalogue.iconFor("HEART")).isEqualTo("POPPY");
            assertThat(ParticleCatalogue.iconFor("NOTE")).isEqualTo("NOTE_BLOCK");
            assertThat(ParticleCatalogue.iconFor("PORTAL")).isEqualTo("OBSIDIAN");
        }

        @Test
        @DisplayName("one that does not gets its group's icon rather than a grey square")
        void fallsBackToTheGroup() {
            assertThat(ParticleCatalogue.iconFor("SCULK_CHARGE_POP"))
                    .isEqualTo(ParticleGroup.of("SCULK_CHARGE_POP").icon());
            assertThat(ParticleCatalogue.iconFor("SOMETHING_NEW_IN_2031")).isNotBlank();
        }

        @Test
        @DisplayName("there is always a face")
        void alwaysSomething() {
            for (String particle : SOME) {
                assertThat(ParticleCatalogue.iconFor(particle))
                        .as(particle + " would be drawn as nothing")
                        .isNotBlank();
            }
            assertThat(ParticleCatalogue.iconFor(null)).isNotBlank();
        }
    }

    // ------------------------------------------------------------------ warning about the awkward ones

    @Nested
    @DisplayName("the ones that need more than a name")
    class NeedingData {

        @Test
        @DisplayName("it says which particles will not show up on their own")
        void saysWhichNeedData() {
            assertThat(ParticleCatalogue.needsExtraData("DUST"))
                    .as("spawning DUST without a colour spawns nothing, and a setting that "
                            + "silently does nothing is the worst kind")
                    .isTrue();
            assertThat(ParticleCatalogue.needsExtraData("BLOCK")).isTrue();
            assertThat(ParticleCatalogue.needsExtraData("ITEM")).isTrue();
            assertThat(ParticleCatalogue.needsExtraData("FLAME")).isFalse();
        }
    }

    // ------------------------------------------------------------------ searching

    @Test
    @DisplayName("searching works the way it does everywhere else")
    void searching() {
        assertThat(catalogue().search("smoke"))
                .containsExactlyInAnyOrder("SMOKE", "LARGE_SMOKE", "CAMPFIRE_COSY_SMOKE");
        assertThat(catalogue().search("large smoke")).containsExactly("LARGE_SMOKE");
        assertThat(catalogue().search("")).hasSize(SOME.size());
    }
}
