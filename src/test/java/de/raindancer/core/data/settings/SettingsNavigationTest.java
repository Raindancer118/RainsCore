package de.raindancer.core.data.settings;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a settings screen shows, and where clicking takes you.
 *
 * <h2>Why this is a class of its own</h2>
 * The same reason {@code MenuLayout} is: a menu cannot be opened without a server, but every
 * decision worth getting right here is arithmetic over a tree — which page shows what, what the
 * trail at the top says, whether a click drills in or changes a value. Putting those in a class that
 * has never heard of an {@code ItemStack} means they are tested rather than clicked through.
 */
class SettingsNavigationTest {

    @Settings(id = "claims", topics = {
            @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER,
                    description = "How much one player may have."),
            @Topic(path = "config/limits/claims", title = "Claims", icon = Material.GRASS_BLOCK),
            @Topic(path = "management/fences", title = "Fences", icon = Material.OAK_FENCE),
    })
    record ClaimConfig(
            @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 100_000)
            int blocksPerPlayer,
            @In("config/limits/claims") @Title("Claims per player") @Range(min = 1, max = 20)
            int claimsPerPlayer,
            @In("management/fences") @Title("Show fences") boolean fencesEnabled,
            @In("management/fences") @Title("Fence style") Style fenceStyle) {

        static final ClaimConfig DEFAULTS = new ClaimConfig(40_000, 5, true, Style.SOLID);
    }

    enum Style { SOLID, DASHED, NONE }

    @TempDir
    Path directory;
    private SettingsRegistry registry;
    private SettingsNavigation navigation;

    @BeforeEach
    void setUp() {
        SettingsStore<ClaimConfig> store = new SettingsStore<>(
                SettingsSchema.of(ClaimConfig.class, ClaimConfig.DEFAULTS),
                directory.resolve("claims.yml"));
        store.load();
        registry = new SettingsRegistry();
        registry.add(store);
        navigation = new SettingsNavigation(registry);
    }

    // ------------------------------------------------------------------ what a page shows

    @Nested
    @DisplayName("a page")
    class Pages {

        @Test
        @DisplayName("at the root shows the top-level categories, and nothing else")
        void rootShowsCategories() {
            SettingsPage root = navigation.page(null);
            assertThat(root.isRoot()).isTrue();
            assertThat(root.subtopics()).extracting(SettingsTopic::path)
                    .containsExactlyInAnyOrder("config", "management");
            assertThat(root.settings()).isEmpty();
        }

        @Test
        @DisplayName("with subtopics and no settings of its own is a menu")
        void aMenuPage() {
            SettingsPage config = navigation.page("config");
            assertThat(config.isMenu()).isTrue();
            assertThat(config.subtopics()).extracting(SettingsTopic::path)
                    .containsExactly("config/limits");
            assertThat(config.settings()).isEmpty();
        }

        @Test
        @DisplayName("can hold both settings and a way further in")
        void aPageWithBoth() {
            SettingsPage limits = navigation.page("config/limits");
            assertThat(limits.settings()).extracting(Setting::key)
                    .containsExactly("blocks-per-player");
            assertThat(limits.subtopics()).extracting(SettingsTopic::path)
                    .containsExactly("config/limits/claims");
        }

        @Test
        @DisplayName("a page nobody declared is the root rather than an empty window")
        void unknownPathFallsBack() {
            assertThat(navigation.page("nothing/like/this").isRoot()).isTrue();
        }

        @Test
        @DisplayName("an empty category is not offered, so no button opens nothing")
        void hidesEmptyCategories() {
            assertThat(navigation.page(null).subtopics())
                    .allSatisfy(topic -> assertThat(topic.isEmpty()).isFalse());
        }
    }

    // ------------------------------------------------------------------ the trail

    @Nested
    @DisplayName("the trail at the top of the window")
    class Trail {

        @Test
        @DisplayName("names where you are, from the root inwards")
        void showsWhereYouAre() {
            assertThat(navigation.page("config/limits/claims").trail())
                    .containsExactly("Server settings", "Limits", "Claims");
        }

        @Test
        @DisplayName("is just the plugin's name at the root")
        void isEmptyAtTheRoot() {
            assertThat(navigation.page(null).trail()).isEmpty();
        }

        @Test
        @DisplayName("is shortened from the left when it would run off the window")
        void shortensADeepTrail() {
            List<String> full = List.of("Server settings", "Limits", "Claims", "Blocks", "Per world");
            assertThat(SettingsPage.shortenTrail(full, 3))
                    .as("the end is where you are; the start is context you can see by going back")
                    .containsExactly("…", "Claims", "Blocks", "Per world");
        }

        @Test
        @DisplayName("a trail that fits is left alone")
        void leavesAShortTrail() {
            List<String> full = List.of("Server settings", "Limits");
            assertThat(SettingsPage.shortenTrail(full, 3)).isEqualTo(full);
        }
    }

    // ------------------------------------------------------------------ going up

    @Nested
    @DisplayName("going back")
    class GoingUp {

        @Test
        @DisplayName("from a page goes to the one above it")
        void goesUpOne() {
            assertThat(navigation.page("config/limits/claims").parentPath()).isEqualTo("config/limits");
            assertThat(navigation.page("config/limits").parentPath()).isEqualTo("config");
        }

        @Test
        @DisplayName("from a top-level category goes to the root")
        void goesToTheRoot() {
            assertThat(navigation.page("config").parentPath()).isNull();
        }

        @Test
        @DisplayName("the root has nowhere above it")
        void theRootHasNoParent() {
            assertThat(navigation.page(null).parentPath()).isNull();
        }
    }

    // ------------------------------------------------------------------ clicking a setting

    @Nested
    @DisplayName("clicking a setting")
    class Clicking {

        @Test
        @DisplayName("a flag flips on the spot")
        void flagsToggle() {
            assertThat(navigation.canCycle(setting("fences-enabled"))).isTrue();
            assertThat(navigation.click("fences-enabled")).isEqualTo(SettingsNavigation.Click.CYCLED);
            assertThat(registry.display("fences-enabled")).isEqualTo("off");
        }

        @Test
        @DisplayName("a choice advances, and wraps")
        void choicesCycle() {
            assertThat(navigation.click("fence-style")).isEqualTo(SettingsNavigation.Click.CYCLED);
            assertThat(registry.display("fence-style")).isEqualTo("dashed");
            navigation.click("fence-style");
            navigation.click("fence-style");
            assertThat(registry.display("fence-style")).isEqualTo("solid");
        }

        @Test
        @DisplayName("a number cannot be cycled — it has to be typed")
        void numbersNeedTyping() {
            assertThat(navigation.canCycle(setting("blocks-per-player"))).isFalse();
            assertThat(navigation.click("blocks-per-player"))
                    .isEqualTo(SettingsNavigation.Click.NEEDS_TYPING);
            assertThat(registry.display("blocks-per-player"))
                    .as("clicking must not have changed it")
                    .isEqualTo("40000");
        }

        @Test
        @DisplayName("a setting nobody knows does nothing")
        void unknownDoesNothing() {
            assertThat(navigation.click("nothing-like-this"))
                    .isEqualTo(SettingsNavigation.Click.UNKNOWN);
        }
    }

    // ------------------------------------------------------------------ what a button says

    @Nested
    @DisplayName("what a setting's button says")
    class Buttons {

        @Test
        @DisplayName("its title, its description, its value and how to change it")
        void describesTheSetting() {
            List<String> lore = navigation.describe(setting("blocks-per-player"));
            assertThat(lore).anySatisfy(line -> assertThat(line).contains("40000"));
            assertThat(lore).anySatisfy(line -> assertThat(line).contains("0"));
            assertThat(lore).anySatisfy(line -> assertThat(line).contains("100000"));
            assertThat(lore).anySatisfy(line -> assertThat(line).containsIgnoringCase("type"));
        }

        @Test
        @DisplayName("a flag says what clicking does rather than what it is")
        void describesAFlag() {
            assertThat(navigation.describe(setting("fences-enabled")))
                    .anySatisfy(line -> assertThat(line).containsIgnoringCase("click"));
        }

        @Test
        @DisplayName("a choice lists what it can be")
        void listsChoices() {
            assertThat(navigation.describe(setting("fence-style")))
                    .anySatisfy(line -> assertThat(line).contains("dashed"));
        }

        @Test
        @DisplayName("which plugin owns it, so a shared page is not confusing")
        void namesTheOwningPlugin() {
            assertThat(navigation.describe(setting("blocks-per-player")))
                    .anySatisfy(line -> assertThat(line).contains("claims"));
        }
    }

    private Setting<?> setting(String key) {
        return registry.setting(key).orElseThrow();
    }
}
