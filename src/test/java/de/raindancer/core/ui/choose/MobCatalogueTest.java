package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorting creatures into the drawers people look in.
 *
 * <p>The names are injected for the reason {@link Catalogue}'s materials are: asking the registry what
 * exists needs a running server, and the sorting is the part worth checking. What matters here is not
 * that every mob is in a defensible drawer — it is that <b>nothing is ever dropped</b>, because a
 * chooser that silently loses the creature somebody wants is worse than one that files it oddly.
 */
class MobCatalogueTest {

    private static MobCatalogue of(String... types) {
        return new MobCatalogue(() -> List.of(types));
    }

    @Test
    @DisplayName("creatures land in the drawer somebody would look in")
    void theObviousOnes() {
        assertThat(MobFamily.of("zombie")).isEqualTo(MobFamily.HOSTILE);
        assertThat(MobFamily.of("cow")).isEqualTo(MobFamily.PASSIVE);
        assertThat(MobFamily.of("squid")).isEqualTo(MobFamily.AQUATIC);
        assertThat(MobFamily.of("ender_dragon")).isEqualTo(MobFamily.BOSS);
        assertThat(MobFamily.of("armor_stand")).isEqualTo(MobFamily.OBJECT);
    }

    @Test
    @DisplayName("a name the table has never heard of is filed, not dropped")
    void nothingVanishes() {
        // The property that matters. Minecraft adds mobs; this table will always be behind.
        assertThat(MobFamily.of("some_mob_from_next_year")).isEqualTo(MobFamily.OTHER);
        assertThat(MobFamily.of(null)).isEqualTo(MobFamily.OTHER);
        assertThat(MobFamily.of("  ")).isEqualTo(MobFamily.OTHER);

        MobCatalogue catalogue = of("zombie", "cow", "some_mob_from_next_year");
        assertThat(catalogue.all()).hasSize(3).contains("some_mob_from_next_year");
    }

    @Test
    @DisplayName("a namespaced name is the same creature")
    void namespaces() {
        assertThat(MobFamily.of("minecraft:zombie")).isEqualTo(MobFamily.HOSTILE);
        assertThat(MobFamily.of("  ZOMBIE  ")).isEqualTo(MobFamily.HOSTILE);
    }

    @Test
    @DisplayName("a creature in two drawers lands in the one somebody would look in first")
    void overlaps() {
        // A drowned is hostile before it is aquatic — somebody building a wave looks for it under
        // hostile — and an elder guardian is a boss before it is either.
        assertThat(MobFamily.of("drowned")).isEqualTo(MobFamily.HOSTILE);
        assertThat(MobFamily.of("elder_guardian")).isEqualTo(MobFamily.BOSS);
        assertThat(MobFamily.of("warden")).isEqualTo(MobFamily.BOSS);
    }

    @Test
    @DisplayName("only the drawers with something in them are shown")
    void emptyDrawersAreHidden() {
        MobCatalogue catalogue = of("zombie", "skeleton");

        assertThat(catalogue.families()).containsExactly(MobFamily.HOSTILE);
        assertThat(catalogue.inFamily(MobFamily.PASSIVE)).isEmpty();
    }

    @Test
    @DisplayName("a wave can only be built from something that fights back")
    void whatAWaveIsMadeOf() {
        // A wave of armour stands is a prank on whoever pressed the button, and a wave of cows is a
        // lag spike with no way to end it.
        MobCatalogue catalogue = of("zombie", "cow", "armor_stand", "ender_dragon", "squid");

        assertThat(catalogue.fightable()).containsExactlyInAnyOrder("zombie", "ender_dragon");
    }

    @Test
    @DisplayName("the golems can be put in a wave without being called hostile")
    void theOnesThatFightWithoutBeingHostile() {
        // Two questions, and the families answer the other one. An iron golem is genuinely not
        // hostile — filing it under Hostile would be a lie to anybody looking for one — but "can this
        // fight" is what a wave asks, and answering it with the family alone left the golems out of
        // packs entirely.
        MobCatalogue catalogue = of("iron_golem", "snow_golem", "cow", "villager", "zombie");

        assertThat(catalogue.fightable())
                .containsExactlyInAnyOrder("iron_golem", "snow_golem", "zombie");
        // Still filed where somebody would look for them.
        assertThat(MobFamily.of("iron_golem")).isEqualTo(MobFamily.PASSIVE);
        assertThat(MobFamily.of("snow_golem")).isEqualTo(MobFamily.PASSIVE);

        assertThat(MobFamily.fightsBack("iron_golem")).isTrue();
        assertThat(MobFamily.fightsBack("minecraft:iron_golem")).isTrue();
        assertThat(MobFamily.fightsBack("cow")).isFalse();
        assertThat(MobFamily.fightsBack(null)).isFalse();
    }

    @Test
    @DisplayName("searching finds it, and an exact match comes first")
    void searching() {
        MobCatalogue catalogue = of("zombie", "zombie_villager", "zombified_piglin", "cow");

        assertThat(catalogue.search("zombie")).startsWith("zombie");
        assertThat(catalogue.search("zombie")).hasSize(2);
        // Spaces work where underscores are, because nobody types an underscore into a search box.
        assertThat(catalogue.search("zombie villager")).containsExactly("zombie_villager");
        assertThat(catalogue.search("")).hasSize(4);
    }

    @Test
    @DisplayName("a creature is drawn as its own spawn egg")
    void icons() {
        assertThat(MobCatalogue.iconFor("zombie")).isEqualTo("ZOMBIE_SPAWN_EGG");
        assertThat(MobCatalogue.iconFor("cave_spider")).isEqualTo("CAVE_SPIDER_SPAWN_EGG");
    }

    @Test
    @DisplayName("the ones with no spawn egg still get a picture rather than nothing")
    void iconsForTheOnesWithoutAnEgg() {
        // Otherwise the dragon, the wither and every object in the list are a grid of identical
        // spawners — a list of names in a costume, which is the thing these choosers exist to avoid.
        assertThat(MobCatalogue.iconFor("ender_dragon")).isEqualTo("DRAGON_HEAD");
        assertThat(MobCatalogue.iconFor("wither")).isEqualTo("WITHER_SKELETON_SKULL");
        assertThat(MobCatalogue.iconFor("iron_golem")).isEqualTo("IRON_BLOCK");
        assertThat(MobCatalogue.iconFor("armor_stand")).isEqualTo(MobFamily.OBJECT.icon());
        assertThat(MobCatalogue.iconFor(null)).isEqualTo(MobFamily.OTHER.icon());
    }

    @Test
    @DisplayName("a creature reads as words rather than as a constant")
    void readableNames() {
        assertThat(MobFamily.readable("cave_spider")).isEqualTo("Cave spider");
        assertThat(MobFamily.readable("minecraft:zombie")).isEqualTo("Zombie");
        assertThat(MobFamily.readable(null)).isNotBlank();
    }

    @Test
    @DisplayName("every drawer has a title and a picture, or it cannot be drawn")
    void everyFamilyIsDrawable() {
        for (MobFamily family : MobFamily.values()) {
            assertThat(family.title()).isNotBlank();
            assertThat(family.icon()).isNotBlank();
        }
    }
}
