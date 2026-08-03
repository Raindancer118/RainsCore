package de.raindancer.core.data.settings;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every plugin's settings, in one tree.
 *
 * <h2>Why they have to merge rather than sit side by side</h2>
 * A player opening the settings does not know which of nine jars owns what they are looking for, and
 * should not have to. Nine separate menus, one per plugin, is the wall this refactor exists to
 * remove — so the categories merge: claims and the ghast lines both putting something under
 * {@code config/limits} produce one Limits page with both on it, and neither plugin has to know the
 * other exists.
 *
 * <p>The thing that has to be right is that merging never loses a setting and never lets one plugin
 * silently take over another's page.
 */
class SettingsRegistryTest {

    @Settings(id = "claims", topics = {
            @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER,
                    description = "How much one player may have."),
            @Topic(path = "management/fences", title = "Fences", icon = Material.OAK_FENCE),
    })
    record ClaimConfig(
            @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 100_000)
            int blocksPerPlayer,
            @In("management/fences") @Title("Show fences") boolean fencesEnabled) {
        static final ClaimConfig DEFAULTS = new ClaimConfig(40_000, true);
    }

    @Settings(id = "ghasts", topics = {
            @Topic(path = "config/limits", title = "Limits"),
            @Topic(path = "ghast-lines/flight", title = "Flight", icon = Material.WHITE_HARNESS),
    })
    record GhastConfig(
            @In("config/limits") @Title("Stops per player") @Range(min = 1, max = 64)
            int stopsPerPlayer,
            @In("ghast-lines/flight") @Title("Cruise speed") @Range(min = 1, max = 10)
            int cruiseSpeed) {
        static final GhastConfig DEFAULTS = new GhastConfig(8, 4);
    }

    @TempDir
    Path directory;
    private SettingsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SettingsRegistry();
        registry.add(store(ClaimConfig.class, ClaimConfig.DEFAULTS, "claims"));
        registry.add(store(GhastConfig.class, GhastConfig.DEFAULTS, "ghasts"));
    }

    private <T> SettingsStore<T> store(Class<T> type, T defaults, String name) {
        SettingsStore<T> store = new SettingsStore<>(SettingsSchema.of(type, defaults),
                directory.resolve(name + ".yml"));
        store.load();
        return store;
    }

    // ------------------------------------------------------------------ merging

    @Nested
    @DisplayName("the combined tree")
    class Merging {

        @Test
        @DisplayName("holds every plugin's categories")
        void hasEveryRoot() {
            assertThat(registry.topics().roots()).extracting(SettingsTopic::path)
                    .containsExactlyInAnyOrder("config", "management", "ghast-lines");
        }

        @Test
        @DisplayName("two plugins under one category share the page rather than fighting over it")
        void sharesAPage() {
            SettingsTopic limits = registry.topics().at("config/limits").orElseThrow();
            assertThat(limits.settings()).extracting(Setting::key)
                    .containsExactlyInAnyOrder("blocks-per-player", "stops-per-player");
        }

        @Test
        @DisplayName("a page described by one plugin keeps that description for both")
        void keepsTheBestDescription() {
            SettingsTopic limits = registry.topics().at("config/limits").orElseThrow();
            assertThat(limits.title()).isEqualTo("Limits");
            assertThat(limits.description())
                    .as("the ghast lines declared the same topic without describing it, and must "
                            + "not blank out the description claims gave it")
                    .isEqualTo("How much one player may have.");
        }

        @Test
        @DisplayName("nothing is lost: every setting of every plugin is in the tree")
        void losesNothing() {
            int declared = registry.stores().stream()
                    .mapToInt(store -> store.schema().settings().size())
                    .sum();
            int inTree = registry.topics().roots().stream()
                    .mapToInt(root -> root.allSettings().size())
                    .sum();
            assertThat(inTree).isEqualTo(declared).isEqualTo(4);
        }

        @Test
        @DisplayName("a setting knows which plugin owns it, so a change reaches the right store")
        void findsTheOwningStore() {
            assertThat(registry.storeOf("blocks-per-player")).isPresent();
            assertThat(registry.storeOf("cruise-speed")).isPresent();
            assertThat(registry.storeOf("nothing-like-this")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ changing

    @Nested
    @DisplayName("changing a setting through the registry")
    class Changing {

        @Test
        @DisplayName("reaches the plugin that owns it")
        void setsThroughTheRegistry() {
            assertThat(registry.set("cruise-speed", "7")).isTrue();

            SettingsStore<?> ghasts = registry.storeOf("cruise-speed").orElseThrow();
            assertThat(ghasts.display("cruise-speed")).isEqualTo("7");
        }

        @Test
        @DisplayName("a value the owning plugin refuses is refused here too")
        void refusesWhatTheStoreRefuses() {
            assertThat(registry.set("cruise-speed", "99")).isFalse();
            assertThat(registry.set("cruise-speed", "fast")).isFalse();
        }

        @Test
        @DisplayName("an unknown key is refused rather than silently doing nothing somewhere")
        void refusesUnknownKeys() {
            assertThat(registry.set("nothing-like-this", "1")).isFalse();
        }

        @Test
        @DisplayName("a flag can be cycled without knowing which plugin owns it")
        void cyclesThroughTheRegistry() {
            assertThat(registry.cycle("fences-enabled")).isEqualTo(false);
            assertThat(registry.cycle("fences-enabled")).isEqualTo(true);
        }

        @Test
        @DisplayName("what a setting currently says can be read without knowing its owner")
        void displaysThroughTheRegistry() {
            assertThat(registry.display("blocks-per-player")).isEqualTo("40000");
            assertThat(registry.display("nothing-like-this")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ collisions

    /**
     * Two plugins using the same key is the one thing merging cannot paper over: a command saying
     * {@code /settings set cruise-speed 7} has to reach exactly one setting.
     */
    @Nested
    @DisplayName("when two plugins want the same key")
    class Collisions {

        @Settings(id = "other", topics = @Topic(path = "config/limits", title = "Limits"))
        record OtherConfig(
                @In("config/limits") @Title("Blocks per player") int blocksPerPlayer) {
            static final OtherConfig DEFAULTS = new OtherConfig(1);
        }

        @Test
        @DisplayName("the clash is reported rather than one quietly winning")
        void reportsClashes() {
            registry.add(store(OtherConfig.class, OtherConfig.DEFAULTS, "other"));

            assertThat(registry.clashes())
                    .as("a command using this key would reach whichever plugin happened to be "
                            + "registered first, which is not something to discover in a year")
                    .containsKey("blocks-per-player");
        }

        @Test
        @DisplayName("the setting is still reachable by its full name")
        void qualifiedNamesStillWork() {
            registry.add(store(OtherConfig.class, OtherConfig.DEFAULTS, "other"));

            assertThat(registry.set("other:blocks-per-player", "5")).isTrue();
            assertThat(registry.display("other:blocks-per-player")).isEqualTo("5");
            assertThat(registry.display("claims:blocks-per-player")).isEqualTo("40000");
        }

        @Test
        @DisplayName("no clash means no report")
        void quietWhenClean() {
            assertThat(registry.clashes()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    @DisplayName("keys are listed for a command to complete")
    void listsKeys() {
        assertThat(registry.keys()).contains("blocks-per-player", "cruise-speed", "fences-enabled",
                "stops-per-player");
    }

    @Test
    @DisplayName("saving saves every plugin's file")
    void savesEverything() {
        registry.set("cruise-speed", "7");
        registry.saveAll();
        assertThat(directory.resolve("ghasts.yml")).exists();
    }

    @Test
    @DisplayName("an empty registry is a tree with nothing in it, not a failure")
    void emptyRegistry() {
        SettingsRegistry empty = new SettingsRegistry();
        assertThatCode(() -> {
            assertThat(empty.topics().roots()).isEmpty();
            assertThat(empty.keys()).isEmpty();
            assertThat(empty.set("anything", "1")).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adding nothing is harmless")
    void addingNull() {
        assertThatCode(() -> registry.add(null)).doesNotThrowAnyException();
    }
}
