package de.raindancer.core.data.settings;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding a settings record to a file on disk.
 *
 * <h2>What this has to get right</h2>
 * A server owner edits {@code config.yml} by hand, in a text editor, while the server is off, and
 * they make mistakes: a number where a word goes, a colour that is not a colour, a value outside
 * what the plugin can use, a key they deleted because they did not know what it did. None of those
 * may stop the server starting, and none of them may silently reset the other forty settings that
 * were fine. That is most of what is tested here.
 */
class SettingsStoreTest {

    @Settings(id = "claims", topics = {
            @Topic(path = "config/fences", title = "Fences", icon = Material.OAK_FENCE,
                    description = "The fence a claim draws around itself."),
            @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER),
    })
    record ClaimConfig(
            @In("config/fences") @Title("Show fences")
            @Describe("Draws a fence along the claim border.")
            boolean fencesEnabled,

            @In("config/fences") @Title("Fence height") @Range(min = 1, max = 16)
            int fencesHeight,

            @In("config/fences") @Title("Fence tint")
            NamedTextColor fencesTint,

            @In("config/fences") @Title("Fence material")
            Material fencesMaterial,

            @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 1_000_000)
            long blocksPerPlayer,

            @In("config/limits") @Title("Tax rate") @Range(min = 0, max = 100)
            double taxPercent,

            @In("config/limits") @Title("Greeting")
            String greeting,

            @In("config/limits") @Title("Banned worlds")
            List<String> bannedWorlds,

            @In("config/limits") @Title("Default policy")
            Policy defaultPolicy,

            @In("config/limits") @Title("Remove phantoms") @Key("gameplay.remove-phantoms")
            boolean removePhantoms
    ) {
        static final ClaimConfig DEFAULTS = new ClaimConfig(
                true, 3, NamedTextColor.AQUA, Material.OAK_FENCE,
                40_000L, 7.5, "Welcome", List.of("nether", "the_end"), Policy.ASK, true);
    }

    enum Policy { ALLOW, DENY, ASK }

    private static final SettingsSchema<ClaimConfig> SCHEMA =
            SettingsSchema.of(ClaimConfig.class, ClaimConfig.DEFAULTS);

    @TempDir
    Path directory;
    private Path file;
    private SettingsStore<ClaimConfig> store;

    @BeforeEach
    void setUp() {
        file = directory.resolve("config.yml");
        store = new SettingsStore<>(SCHEMA, file);
    }

    private void writeFile(String yaml) throws IOException {
        Files.writeString(file, yaml);
    }

    private YamlConfiguration read() {
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    // ------------------------------------------------------------------ first run

    @Nested
    @DisplayName("a server that has never run this plugin")
    class FirstRun {

        @Test
        @DisplayName("gets every default without a file having to exist")
        void loadsDefaultsWithNoFile() {
            store.load();
            assertThat(store.current()).isEqualTo(ClaimConfig.DEFAULTS);
        }

        @Test
        @DisplayName("has a config.yml written for it, holding every key")
        void writesTheFile() {
            store.load();
            store.save();

            assertThat(file).exists();
            YamlConfiguration written = read();
            for (String key : SCHEMA.keys()) {
                assertThat(written.contains(key)).as("%s is missing from the file", key).isTrue();
            }
        }

        @Test
        @DisplayName("the file explains each setting, so it can be edited without the source")
        void writesComments() throws IOException {
            store.load();
            store.save();

            String text = Files.readString(file);
            assertThat(text)
                    .contains("Draws a fence along the claim border.")
                    .contains("Show fences")
                    .as("a topic's description belongs above its block")
                    .contains("The fence a claim draws around itself.");
        }

        @Test
        @DisplayName("a bounded number says what its bounds are")
        void documentsBounds() throws IOException {
            store.load();
            store.save();
            assertThat(Files.readString(file)).contains("1").contains("16");
        }

        @Test
        @DisplayName("a value with a fixed set of answers lists them")
        void documentsChoices() throws IOException {
            store.load();
            store.save();
            assertThat(Files.readString(file)).contains("allow").contains("deny").contains("ask");
        }
    }

    // ------------------------------------------------------------------ reading

    @Nested
    @DisplayName("reading a file somebody edited")
    class Reading {

        @Test
        @DisplayName("every type comes back as the record declares it")
        void readsEveryType() throws IOException {
            writeFile("""
                    fences-enabled: false
                    fences-height: 9
                    fences-tint: red
                    fences-material: STONE_BRICK_WALL
                    blocks-per-player: 123456
                    tax-percent: 12.5
                    greeting: "Guten Tag"
                    banned-worlds:
                      - creative
                      - void
                    default-policy: deny
                    gameplay:
                      remove-phantoms: false
                    """);
            store.load();

            ClaimConfig config = store.current();
            assertThat(config.fencesEnabled()).isFalse();
            assertThat(config.fencesHeight()).isEqualTo(9);
            assertThat(config.fencesTint()).isEqualTo(NamedTextColor.RED);
            assertThat(config.fencesMaterial()).isEqualTo(Material.STONE_BRICK_WALL);
            assertThat(config.blocksPerPlayer()).isEqualTo(123_456L);
            assertThat(config.taxPercent()).isEqualTo(12.5);
            assertThat(config.greeting()).isEqualTo("Guten Tag");
            assertThat(config.bannedWorlds()).containsExactly("creative", "void");
            assertThat(config.defaultPolicy()).isEqualTo(Policy.DENY);
            assertThat(config.removePhantoms()).isFalse();
        }

        @Test
        @DisplayName("a key that is not in the file keeps its default")
        void missingKeysFallBack() throws IOException {
            writeFile("fences-height: 9\n");
            store.load();
            assertThat(store.current().fencesHeight()).isEqualTo(9);
            assertThat(store.current().greeting()).isEqualTo("Welcome");
        }

        @Test
        @DisplayName("an enum is read whatever case it was typed in")
        void enumsAreCaseInsensitive() throws IOException {
            writeFile("default-policy: DeNy\n");
            store.load();
            assertThat(store.current().defaultPolicy()).isEqualTo(Policy.DENY);
        }
    }

    // ------------------------------------------------------------------ bad input

    @Nested
    @DisplayName("a file with mistakes in it")
    class BadInput {

        @Test
        @DisplayName("a value of the wrong type falls back rather than stopping the server")
        void wrongTypeFallsBack() throws IOException {
            writeFile("""
                    fences-height: "quite tall"
                    greeting: "still fine"
                    """);
            store.load();

            assertThat(store.current().fencesHeight()).isEqualTo(3);
            assertThat(store.current().greeting())
                    .as("one bad value must not reset the settings around it")
                    .isEqualTo("still fine");
        }

        @Test
        @DisplayName("a number outside its range is pulled back inside it")
        void outOfRangeIsClamped() throws IOException {
            writeFile("""
                    fences-height: 900
                    tax-percent: -5
                    """);
            store.load();

            assertThat(store.current().fencesHeight()).isEqualTo(16);
            assertThat(store.current().taxPercent()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("a colour that is not a colour falls back")
        void badColourFallsBack() throws IOException {
            writeFile("fences-tint: burnt-sienna\n");
            store.load();
            assertThat(store.current().fencesTint()).isEqualTo(NamedTextColor.AQUA);
        }

        @Test
        @DisplayName("a block that does not exist falls back")
        void badMaterialFallsBack() throws IOException {
            writeFile("fences-material: UNOBTAINIUM_BLOCK\n");
            store.load();
            assertThat(store.current().fencesMaterial()).isEqualTo(Material.OAK_FENCE);
        }

        @Test
        @DisplayName("an unknown choice falls back rather than becoming null")
        void badEnumFallsBack() throws IOException {
            writeFile("default-policy: maybe\n");
            store.load();
            assertThat(store.current().defaultPolicy()).isEqualTo(Policy.ASK);
        }

        @Test
        @DisplayName("a list given as a single value is read as a list of one")
        void toleratesAScalarForAList() throws IOException {
            writeFile("banned-worlds: creative\n");
            store.load();
            assertThat(store.current().bannedWorlds()).containsExactly("creative");
        }

        @Test
        @DisplayName("a file that is not YAML at all leaves every default in place")
        void survivesAnUnparseableFile() throws IOException {
            writeFile("this: is: not: valid: yaml:\n\t\tand neither is this\n");
            store.load();
            assertThat(store.current()).isEqualTo(ClaimConfig.DEFAULTS);
        }

        @Test
        @DisplayName("every mistake is written down, so the owner can find out what was ignored")
        void reportsWhatItIgnored() throws IOException {
            writeFile("""
                    fences-height: "quite tall"
                    fences-tint: burnt-sienna
                    """);
            store.load();

            assertThat(store.problems())
                    .hasSize(2)
                    .anySatisfy(problem -> assertThat(problem).contains("fences-height"))
                    .anySatisfy(problem -> assertThat(problem).contains("fences-tint"));
        }
    }

    // ------------------------------------------------------------------ writing

    @Nested
    @DisplayName("changing a setting")
    class Writing {

        @Test
        @DisplayName("a typed value is accepted and shows up at once")
        void setsFromText() {
            store.load();
            assertThat(store.set("fences-height", "7")).isTrue();
            assertThat(store.current().fencesHeight()).isEqualTo(7);
        }

        @Test
        @DisplayName("a value that will not parse is refused and changes nothing")
        void refusesRubbish() {
            store.load();
            assertThat(store.set("fences-height", "tall")).isFalse();
            assertThat(store.current().fencesHeight()).isEqualTo(3);
        }

        @Test
        @DisplayName("a value outside the range is refused rather than quietly clamped")
        void refusesOutOfRange() {
            store.load();
            // Deliberately different from loading a file: somebody typed this just now and can be
            // told they are wrong. A file was typed months ago by somebody who has gone to bed.
            assertThat(store.set("fences-height", "900")).isFalse();
            assertThat(store.current().fencesHeight()).isEqualTo(3);
        }

        @Test
        @DisplayName("a key nobody declared is refused")
        void refusesUnknownKeys() {
            store.load();
            assertThat(store.set("fences-colour", "red")).isFalse();
        }

        @Test
        @DisplayName("a flag flips and a choice advances, both wrapping round")
        void cycles() {
            store.load();
            assertThat(store.cycle("fences-enabled")).isEqualTo(false);
            assertThat(store.cycle("fences-enabled")).isEqualTo(true);

            assertThat(store.cycle("default-policy")).isEqualTo(Policy.ALLOW);
            assertThat(store.cycle("default-policy")).isEqualTo(Policy.DENY);
            assertThat(store.cycle("default-policy")).isEqualTo(Policy.ASK);
        }

        @Test
        @DisplayName("something with no next value is left alone rather than throwing")
        void cyclingTextDoesNothing() {
            store.load();
            assertThat(store.cycle("greeting")).isEqualTo("Welcome");
        }

        @Test
        @DisplayName("one setting can be put back, and so can all of them")
        void resets() {
            store.load();
            store.set("fences-height", "7");
            store.set("greeting", "Hi");

            store.reset("fences-height");
            assertThat(store.current().fencesHeight()).isEqualTo(3);
            assertThat(store.current().greeting()).isEqualTo("Hi");

            store.resetAll();
            assertThat(store.current()).isEqualTo(ClaimConfig.DEFAULTS);
        }
    }

    // ------------------------------------------------------------------ round trip

    @Nested
    @DisplayName("saving and loading again")
    class RoundTrip {

        @Test
        @DisplayName("gives back exactly what was there")
        void roundTrips() {
            store.load();
            store.set("fences-height", "11");
            store.set("fences-tint", "gold");
            store.set("default-policy", "allow");
            store.set("greeting", "Servus");
            store.save();

            SettingsStore<ClaimConfig> reopened = new SettingsStore<>(SCHEMA, file);
            reopened.load();
            assertThat(reopened.current()).isEqualTo(store.current());
            assertThat(reopened.problems()).isEmpty();
        }

        @Test
        @DisplayName("every default survives a trip through the file unchanged")
        void defaultsRoundTrip() {
            store.load();
            store.save();

            SettingsStore<ClaimConfig> reopened = new SettingsStore<>(SCHEMA, file);
            reopened.load();
            assertThat(reopened.current()).isEqualTo(ClaimConfig.DEFAULTS);
            assertThat(reopened.problems())
                    .as("the file this plugin writes must be one it can read without complaint")
                    .isEmpty();
        }

        @Test
        @DisplayName("a key the plugin does not know is left in the file, not deleted")
        void keepsForeignKeys() throws IOException {
            writeFile("""
                    fences-height: 5
                    something-a-future-version-added: 42
                    """);
            store.load();
            store.save();

            assertThat(read().getInt("something-a-future-version-added"))
                    .as("downgrading and upgrading again must not lose a setting")
                    .isEqualTo(42);
        }

        @Test
        @DisplayName("a new version's settings are added to an old file, leaving the rest alone")
        void fillsInNewKeys() throws IOException {
            writeFile("fences-height: 5\n");
            store.load();
            store.save();

            YamlConfiguration written = read();
            assertThat(written.getInt("fences-height")).isEqualTo(5);
            assertThat(written.contains("greeting")).isTrue();
        }
    }

    // ------------------------------------------------------------------ listeners

    @Nested
    @DisplayName("telling a plugin something changed")
    class Listeners {

        @Test
        @DisplayName("a listener is given the new snapshot when anything changes")
        void firesOnChange() {
            List<ClaimConfig> seen = new ArrayList<>();
            store.load();
            store.onChange(seen::add);

            store.set("fences-height", "7");

            assertThat(seen).hasSize(1);
            assertThat(seen.getFirst().fencesHeight()).isEqualTo(7);
        }

        @Test
        @DisplayName("a listener is not woken up when nothing actually changed")
        void staysQuietWhenNothingChanged() {
            List<ClaimConfig> seen = new ArrayList<>();
            store.load();
            store.onChange(seen::add);

            store.set("fences-height", "3");

            assertThat(seen)
                    .as("setting a value to what it already was is not a change")
                    .isEmpty();
        }

        @Test
        @DisplayName("a listener that throws does not stop the others")
        void survivesABrokenListener() {
            List<ClaimConfig> seen = new ArrayList<>();
            store.load();
            store.onChange(config -> {
                throw new IllegalStateException("no");
            });
            store.onChange(seen::add);

            store.set("fences-height", "7");

            assertThat(seen).hasSize(1);
        }

        @Test
        @DisplayName("reloading from disk tells the listeners too")
        void firesOnReload() throws IOException {
            store.load();
            List<ClaimConfig> seen = new ArrayList<>();
            store.onChange(seen::add);

            writeFile("fences-height: 12\n");
            store.load();

            assertThat(seen).hasSize(1);
            assertThat(seen.getFirst().fencesHeight()).isEqualTo(12);
        }
    }

    // ------------------------------------------------------------------ display

    @Test
    @DisplayName("a value can be shown to a person, whatever its type")
    void displaysValues() {
        store.load();
        assertThat(store.display("fences-enabled")).isEqualTo("on");
        assertThat(store.display("fences-height")).isEqualTo("3");
        assertThat(store.display("default-policy")).isEqualTo("ask");
        assertThat(store.display("banned-worlds")).isEqualTo("nether, the_end");
        assertThat(store.display("fences-tint")).isEqualTo("aqua");
        assertThat(store.display("nope")).isEmpty();
    }

    @Test
    @DisplayName("reading before load() gives the defaults rather than null")
    void isUsableBeforeLoading() {
        assertThat(store.current()).isEqualTo(ClaimConfig.DEFAULTS);
    }
}
