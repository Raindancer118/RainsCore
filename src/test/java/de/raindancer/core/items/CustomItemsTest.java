package de.raindancer.core.items;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Custom items, and where they come from.
 *
 * <h2>What this is for</h2>
 * Every one of these plugins invents items: the claims module's selection stick, the record seller's
 * discs, the ghast lines' tickets. Each built its own {@code ItemStack} by hand, stamped its own
 * key into the persistent data container, and had its own idea of how to tell one of its items from
 * a lookalike somebody crafted. This is that, once — and it means an item defined by one plugin can
 * be given, recognised or listed by any other, and by a command, without either knowing about the
 * other.
 *
 * <h2>Why a definition is not an ItemStack</h2>
 * A definition is what the server owner configured: a material, a name, some lore, an icon. An
 * {@code ItemStack} is one made from it. Keeping them apart is what lets a definition be edited
 * while items made from it are already in chests — and what lets all of this be tested, since an
 * {@code ItemStack} needs a server and a definition does not.
 */
class CustomItemsTest {

    @TempDir
    Path directory;
    private CustomItems items;

    @BeforeEach
    void setUp() {
        items = new CustomItems(directory.resolve("items.yml"));
    }

    private static CustomItem stick() {
        return CustomItem.builder("claims", "selection-stick")
                .material(Material.STICK)
                .name("<gold>Claim Selection Stick")
                .lore(List.of("<gray>Right-click two corners"))
                .glowing(true)
                .build();
    }

    // ------------------------------------------------------------------ defining

    @Nested
    @DisplayName("defining an item")
    class Defining {

        @Test
        @DisplayName("it can be found again by its key")
        void registersAndFinds() {
            items.define(stick());
            assertThat(items.byKey("claims:selection-stick")).contains(stick());
        }

        @Test
        @DisplayName("the key is the plugin and the name, so two plugins cannot collide")
        void keysAreNamespaced() {
            assertThat(stick().key()).isEqualTo("claims:selection-stick");

            items.define(stick());
            items.define(CustomItem.builder("ghasts", "selection-stick")
                    .material(Material.BLAZE_ROD).name("<gold>Ticket").build());

            assertThat(items.all()).hasSize(2);
        }

        @Test
        @DisplayName("defining the same key again replaces it")
        void replaces() {
            items.define(stick());
            items.define(stick().withMaterial(Material.BLAZE_ROD));

            assertThat(items.all()).hasSize(1);
            assertThat(items.byKey("claims:selection-stick").orElseThrow().material())
                    .isEqualTo(Material.BLAZE_ROD);
        }

        @Test
        @DisplayName("everything one plugin defined can be listed")
        void listsByPlugin() {
            items.define(stick());
            items.define(CustomItem.builder("claims", "wand").material(Material.STICK)
                    .name("<gold>Wand").build());
            items.define(CustomItem.builder("ghasts", "ticket").material(Material.PAPER)
                    .name("<gold>Ticket").build());

            assertThat(items.ofPlugin("claims")).hasSize(2);
            assertThat(items.ofPlugin("ghasts")).hasSize(1);
            assertThat(items.ofPlugin("nobody")).isEmpty();
        }

        /**
         * The point of {@code defineIfAbsent}: a plugin ships a default for its own item, and the
         * server owner's edits to it survive the next restart.
         */
        @Test
        @DisplayName("a plugin's default does not overwrite what the owner has changed")
        void defaultsDoNotOverwriteEdits() {
            items.define(stick().withMaterial(Material.BLAZE_ROD));
            items.defineIfAbsent(stick());

            assertThat(items.byKey("claims:selection-stick").orElseThrow().material())
                    .as("the owner chose a blaze rod; a restart must not put the stick back")
                    .isEqualTo(Material.BLAZE_ROD);
        }

        @Test
        @DisplayName("a definition can be removed")
        void undefines() {
            items.define(stick());
            assertThat(items.undefine("claims:selection-stick")).isTrue();
            assertThat(items.byKey("claims:selection-stick")).isEmpty();
            assertThat(items.undefine("claims:selection-stick")).isFalse();
        }
    }

    // ------------------------------------------------------------------ editing

    @Nested
    @DisplayName("editing one")
    class Editing {

        @Test
        @DisplayName("every part can be changed without losing the rest")
        void changesOnePartAtATime() {
            CustomItem edited = stick()
                    .withMaterial(Material.BLAZE_ROD)
                    .withName("<red>Renamed")
                    .withLore(List.of("<gray>New line"))
                    .withModelData(1234)
                    .withGlowing(false);

            assertThat(edited.key()).isEqualTo(stick().key());
            assertThat(edited.material()).isEqualTo(Material.BLAZE_ROD);
            assertThat(edited.name()).isEqualTo("<red>Renamed");
            assertThat(edited.lore()).containsExactly("<gray>New line");
            assertThat(edited.modelData()).contains(1234);
            assertThat(edited.isGlowing()).isFalse();
        }

        @Test
        @DisplayName("changing it does not change items already made from it")
        void definitionsAreValues() {
            CustomItem before = stick();
            CustomItem after = before.withName("<red>Renamed");
            assertThat(before.name()).isEqualTo("<gold>Claim Selection Stick");
            assertThat(after.name()).isEqualTo("<red>Renamed");
        }

        @Test
        @DisplayName("a plugin's own notes travel with the definition")
        void carriesTags() {
            CustomItem tagged = stick().withTag("uses", "2");
            assertThat(tagged.tag("uses")).contains("2");
            assertThat(tagged.withTag("uses", null).tag("uses")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ what is allowed

    @Nested
    @DisplayName("what a definition may be")
    class Validity {

        @Test
        @DisplayName("it needs a plugin, a name and a material")
        void refusesIncomplete() {
            assertThatCode(() -> CustomItem.builder(null, "x").material(Material.STICK).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> CustomItem.builder("claims", " ").material(Material.STICK).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> CustomItem.builder("claims", "x").material(null).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a material that cannot be an item is refused")
        void refusesNonItems() {
            assertThatCode(() -> CustomItem.builder("claims", "x").material(Material.AIR).build())
                    .as("air cannot be given to anybody")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a name that will not parse is refused rather than stored")
        void refusesBrokenNames() {
            assertThatCode(() -> CustomItem.builder("claims", "x")
                    .material(Material.STICK).name("<notatag>Oops").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the key is lower case however it was typed, so lookups match")
        void keysAreNormalised() {
            assertThat(CustomItem.builder("Claims", "Selection-Stick")
                    .material(Material.STICK).name("<gold>x").build().key())
                    .isEqualTo("claims:selection-stick");
        }

        @Test
        @DisplayName("a name is optional; the block's own name is used instead")
        void nameIsOptional() {
            assertThatCode(() -> CustomItem.builder("claims", "x").material(Material.STICK).build())
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("every definition is still there, unchanged")
        void roundTrips() {
            items.define(stick().withModelData(1234).withTag("uses", "2"));
            items.define(CustomItem.builder("ghasts", "ticket").material(Material.PAPER)
                    .name("<aqua>Ticket").lore(List.of("<gray>One journey")).build());
            items.flush();

            CustomItems reopened = new CustomItems(directory.resolve("items.yml"));
            reopened.load();

            assertThat(reopened.all()).hasSize(2);
            CustomItem read = reopened.byKey("claims:selection-stick").orElseThrow();
            assertThat(read.material()).isEqualTo(Material.STICK);
            assertThat(read.name()).isEqualTo("<gold>Claim Selection Stick");
            assertThat(read.lore()).containsExactly("<gray>Right-click two corners");
            assertThat(read.modelData()).contains(1234);
            assertThat(read.isGlowing()).isTrue();
            assertThat(read.tag("uses")).contains("2");
        }

        @Test
        @DisplayName("an entry naming a block this server has never heard of is skipped, not fatal")
        void skipsUnknownMaterials() throws Exception {
            java.nio.file.Files.writeString(directory.resolve("items.yml"), """
                    items:
                      claims:good:
                        material: STICK
                        name: "<gold>Fine"
                      claims:bad:
                        material: UNOBTAINIUM_BLOCK
                        name: "<gold>Nope"
                    """);

            CustomItems reopened = new CustomItems(directory.resolve("items.yml"));
            reopened.load();

            assertThat(reopened.all()).hasSize(1);
            assertThat(reopened.problems()).hasSize(1);
        }

        @Test
        @DisplayName("a missing file is simply no items yet")
        void survivesAMissingFile() {
            CustomItems fresh = new CustomItems(directory.resolve("nothing.yml"));
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.all()).isEmpty();
        }

        @Test
        @DisplayName("nothing is written when nothing changed")
        void doesNotWriteWithoutChanges() {
            items.load();
            items.flush();
            assertThat(directory.resolve("items.yml")).doesNotExist();
        }
    }

    // ------------------------------------------------------------------ listing

    @Test
    @DisplayName("the keys are listed in order, so a command can complete them")
    void listsKeys() {
        items.define(stick());
        items.define(CustomItem.builder("ghasts", "ticket").material(Material.PAPER)
                .name("<aqua>Ticket").build());

        assertThat(items.keys()).containsExactlyInAnyOrder("claims:selection-stick", "ghasts:ticket");
    }

    @Test
    @DisplayName("asking for something that is not defined is empty, not an exception")
    void missingIsEmpty() {
        assertThat(items.byKey("nope:nothing")).isEmpty();
        assertThat(items.byKey(null)).isEmpty();
        assertThat(items.ofPlugin(null)).isEmpty();
    }
}
