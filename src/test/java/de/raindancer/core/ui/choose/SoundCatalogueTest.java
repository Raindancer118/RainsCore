package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorting a thousand sound keys, and — the part that matters — giving each one a face.
 *
 * <h2>Why the icon is worth this much trouble</h2>
 * Because a grid of forty-five identical note blocks is not a chooser. It is a list of names in a
 * costume, and picking from it means reading {@code block.amethyst_block.chime} and imagining. The
 * sound's own name already says what makes the noise — the amethyst block, the bell, the zombie — so
 * the icon can simply be that thing, and then the page is something you can look at.
 */
@DisplayName("the sound catalogue")
class SoundCatalogueTest {

    private static final List<String> SOME = List.of(
            "ui.button.click", "ui.toast.in",
            "block.amethyst_block.chime", "block.bell.use", "block.anvil.land",
            "block.note_block.bell", "block.stone.break",
            "item.armor.equip_diamond", "item.bucket.fill",
            "entity.zombie.ambient", "entity.villager.no", "entity.ender_dragon.growl",
            "music.creative", "music_disc.cat",
            "ambient.cave", "weather.rain",
            "something.nobody.knows");

    private static SoundCatalogue catalogue() {
        return new SoundCatalogue(() -> SOME);
    }

    // ------------------------------------------------------------------ sorting

    @Nested
    @DisplayName("families")
    class Families {

        @Test
        @DisplayName("a key is filed by its first word, which is the game's own sorting")
        void byFirstWord() {
            assertThat(SoundFamily.of("ui.button.click")).isEqualTo(SoundFamily.UI);
            assertThat(SoundFamily.of("block.bell.use")).isEqualTo(SoundFamily.BLOCK);
            assertThat(SoundFamily.of("entity.zombie.ambient")).isEqualTo(SoundFamily.ENTITY);
            assertThat(SoundFamily.of("item.bucket.fill")).isEqualTo(SoundFamily.ITEM);
            assertThat(SoundFamily.of("music_disc.cat")).isEqualTo(SoundFamily.MUSIC);
            assertThat(SoundFamily.of("weather.rain")).isEqualTo(SoundFamily.AMBIENT);
        }

        @Test
        @DisplayName("anything unrecognised gets a home rather than disappearing")
        void unknownFamilies() {
            assertThat(SoundFamily.of("something.nobody.knows")).isEqualTo(SoundFamily.OTHER);
            assertThat(SoundFamily.of(null)).isEqualTo(SoundFamily.OTHER);
        }

        @Test
        @DisplayName("every sound ends up in exactly one family")
        void nothingIsLost() {
            SoundCatalogue catalogue = catalogue();
            int filed = catalogue.families().stream()
                    .mapToInt(family -> catalogue.inFamily(family).size())
                    .sum();
            assertThat(filed).isEqualTo(SOME.size());
        }
    }

    // ------------------------------------------------------------------ the faces

    @Nested
    @DisplayName("the icon a sound gets")
    class Icons {

        @Test
        @DisplayName("a block sound is drawn with that block")
        void blocksAreThemselves() {
            assertThat(SoundCatalogue.iconFor("block.amethyst_block.chime"))
                    .as("this is the whole point: the sound of an amethyst block should look like "
                            + "an amethyst block")
                    .isEqualTo("AMETHYST_BLOCK");
            assertThat(SoundCatalogue.iconFor("block.bell.use")).isEqualTo("BELL");
            assertThat(SoundCatalogue.iconFor("block.anvil.land")).isEqualTo("ANVIL");
            assertThat(SoundCatalogue.iconFor("block.note_block.bell")).isEqualTo("NOTE_BLOCK");
        }

        @Test
        @DisplayName("an item sound is drawn with that item")
        void itemsAreThemselves() {
            assertThat(SoundCatalogue.iconFor("item.bucket.fill")).isEqualTo("BUCKET");
        }

        @Test
        @DisplayName("a creature is drawn with its spawn egg")
        void creaturesGetTheirEgg() {
            assertThat(SoundCatalogue.iconFor("entity.zombie.ambient"))
                    .isEqualTo("ZOMBIE_SPAWN_EGG");
            assertThat(SoundCatalogue.iconFor("entity.villager.no"))
                    .isEqualTo("VILLAGER_SPAWN_EGG");
        }

        @Test
        @DisplayName("a creature with no spawn egg still gets something of its own")
        void creaturesWithoutEggs() {
            assertThat(SoundCatalogue.iconFor("entity.ender_dragon.growl"))
                    .as("the dragon has no spawn egg, and a note block would be a lie")
                    .isEqualTo("DRAGON_HEAD");
            assertThat(SoundCatalogue.iconFor("entity.player.hurt")).isEqualTo("PLAYER_HEAD");
        }

        @Test
        @DisplayName("music is drawn with a disc")
        void musicGetsADisc() {
            assertThat(SoundCatalogue.iconFor("music_disc.cat")).isEqualTo("MUSIC_DISC_CAT");
            assertThat(SoundCatalogue.iconFor("music.creative")).contains("MUSIC_DISC");
        }

        @Test
        @DisplayName("the interface and the weather get something that suits them")
        void theRestAreSensible() {
            assertThat(SoundCatalogue.iconFor("ui.button.click")).isEqualTo("OAK_BUTTON");
            assertThat(SoundCatalogue.iconFor("weather.rain")).isEqualTo("WATER_BUCKET");
            assertThat(SoundCatalogue.iconFor("ambient.cave")).isEqualTo("DEEPSLATE");
        }

        @Test
        @DisplayName("something with no name to work from still gets a face, never nothing")
        void alwaysSomething() {
            assertThat(SoundCatalogue.iconFor("something.nobody.knows")).isNotBlank();
            assertThat(SoundCatalogue.iconFor(null)).isNotBlank();
            assertThat(SoundCatalogue.iconFor("")).isNotBlank();
        }

        @Test
        @DisplayName("the namespace does not confuse it")
        void ignoresTheNamespace() {
            assertThat(SoundCatalogue.iconFor("minecraft:block.bell.use")).isEqualTo("BELL");
        }
    }

    // ------------------------------------------------------------------ names

    @Nested
    @DisplayName("what a sound is called in the menu")
    class Naming {

        @Test
        @DisplayName("the family is dropped, because the page is already the family")
        void dropsTheFamily() {
            assertThat(SoundCatalogue.readable("block.note_block.bell"))
                    .isEqualTo("Note Block Bell");
            assertThat(SoundCatalogue.readable("entity.villager.no")).isEqualTo("Villager No");
        }
    }
}
