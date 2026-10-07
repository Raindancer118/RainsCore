package de.raindancer.core.ui.chat;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One prefix for every plugin, or one each — in any colours, gradient, decorations and shape the owner
 * likes, the same way a player's name is painted.
 */
class PrefixDesignTest {

    private static final NameStyle RED_BOLD =
            new NameStyle(List.of(NamedTextColor.RED), Set.of(TextDecoration.BOLD));

    private static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    @AfterEach
    void reset() {
        Prefixes.use(PrefixDesign.DEFAULT);
    }

    @Nested
    @DisplayName("whose tag")
    class Tags {

        @Test
        @DisplayName("per plugin, each plugin signs with its own name")
        void perPlugin() {
            assertThat(PrefixDesign.DEFAULT.tagFor("Claims", "Claims")).isEqualTo("Claims");
        }

        @Test
        @DisplayName("shared, every plugin signs with the one tag")
        void shared() {
            PrefixDesign design = PrefixDesign.DEFAULT.withMode(PrefixDesign.Mode.SHARED).withTag("Lilly SMP");

            assertThat(design.tagFor("Claims", "Claims")).isEqualTo("Lilly SMP");
            assertThat(design.tagFor("Moderation", "Mod")).isEqualTo("Lilly SMP");
        }

        @Test
        @DisplayName("shared with no tag written falls back to each plugin's own rather than signing with nothing")
        void sharedWithoutTag() {
            PrefixDesign design = PrefixDesign.DEFAULT.withMode(PrefixDesign.Mode.SHARED).withTag(" ");

            assertThat(design.tagFor("Claims", "Claims")).isEqualTo("Claims");
        }

        @Test
        @DisplayName("a plugin's own override wins in per-plugin mode, matched however the key is cased")
        void override() {
            PrefixDesign design = PrefixDesign.DEFAULT
                    .withPlugin("Moderation", new PrefixDesign.PluginPrefix("Mod", RED_BOLD, true));

            assertThat(design.tagFor("moderation", "Moderation")).isEqualTo("Mod");
            assertThat(design.styleFor("MODERATION")).isEqualTo(RED_BOLD);
            assertThat(design.tagFor("Claims", "Claims")).isEqualTo("Claims");
        }

        @Test
        @DisplayName("the tag the plugin's own settings give is used when nothing overrides it")
        void pluginSettingsTag() {
            assertThat(PrefixDesign.DEFAULT.tagFor("Moderation", "Staff")).isEqualTo("Staff");
        }
    }

    @Nested
    @DisplayName("painting")
    class Painting {

        @Test
        @DisplayName("with no style, the theme's gradient in bold, as every plugin had before")
        void legacyLook() {
            String painted = Prefixes.paintedTag("Claims", "Claims", 0);

            assertThat(painted).startsWith("<gradient:").contains("<bold>Claims</bold>");
        }

        @Test
        @DisplayName("with a style, the tag is painted in it like a name")
        void styled() {
            Prefixes.use(PrefixDesign.DEFAULT.withStyle(RED_BOLD));

            String painted = Prefixes.paintedTag("Claims", "Claims", 0);

            assertThat(plain(painted)).isEqualTo("Claims");
            assertThat(painted).contains("red").contains("bold");
        }

        @Test
        @DisplayName("the tag is text, never markup — a <red> in it is shown, not obeyed")
        void escaped() {
            Prefixes.use(PrefixDesign.DEFAULT.withMode(PrefixDesign.Mode.SHARED).withTag("<red>Evil"));

            assertThat(plain(Prefixes.chatPrefix("Claims", "Claims"))).startsWith("<red>Evil");
        }

        @Test
        @DisplayName("the shape is the owner's: {tag} goes where they put it")
        void format() {
            Prefixes.use(PrefixDesign.DEFAULT.withFormat("<gray>[</gray>{tag}<gray>]</gray> "));

            assertThat(plain(Prefixes.chatPrefix("Claims", "Claims"))).isEqualTo("[Claims] ");
        }

        @Test
        @DisplayName("{plugin} is the plugin's own name, so a shared tag can still say who is talking")
        void pluginPlaceholder() {
            Prefixes.use(PrefixDesign.DEFAULT.withMode(PrefixDesign.Mode.SHARED).withTag("SMP")
                    .withFormat("{tag} <gray>{plugin}</gray> » "));

            assertThat(plain(Prefixes.chatPrefix("Claims", "Claims"))).isEqualTo("SMP Claims » ");
        }

        @Test
        @DisplayName("a format without {tag} still carries the tag — in front, rather than vanishing")
        void formatWithoutTag() {
            PrefixDesign design = PrefixDesign.DEFAULT.withFormat("» ");

            assertThat(design.format()).isEqualTo("{tag} » ");
        }

        @Test
        @DisplayName("switched off, there is no prefix at all — for one plugin or for all of them")
        void hidden() {
            Prefixes.use(PrefixDesign.DEFAULT.withShown(false));
            assertThat(Prefixes.chatPrefix("Claims", "Claims")).isEmpty();

            Prefixes.use(PrefixDesign.DEFAULT
                    .withPlugin("Claims", new PrefixDesign.PluginPrefix("", NameStyle.NONE, false)));
            assertThat(Prefixes.chatPrefix("Claims", "Claims")).isEmpty();
            assertThat(Prefixes.chatPrefix("Homes", "Homes")).isNotEmpty();
        }

        @Test
        @DisplayName("a flowing gradient is painted at the moment it is asked for")
        void flowing() {
            NameStyle flowing = new NameStyle(List.of(NamedTextColor.RED, NamedTextColor.BLUE), Set.of())
                    .animated(true);
            Prefixes.use(PrefixDesign.DEFAULT.withStyle(flowing));

            assertThat(Prefixes.paintedTag("Claims", "Claims", 0))
                    .isNotEqualTo(Prefixes.paintedTag("Claims", "Claims", 0.5));
        }
    }

    @Nested
    @DisplayName("the file")
    class File {

        @TempDir
        Path directory;

        @Test
        @DisplayName("is written with the defaults on first start, and reads back the same")
        void roundTrip() {
            Path file = directory.resolve("prefix.yml");
            PrefixFile store = new PrefixFile(file);

            PrefixDesign first = store.load();
            assertThat(first).isEqualTo(PrefixDesign.DEFAULT);
            assertThat(file).exists();

            PrefixDesign changed = first.withMode(PrefixDesign.Mode.SHARED).withTag("Lilly SMP")
                    .withStyle(RED_BOLD).withFormat("[{tag}] ")
                    .withPlugin("Moderation", new PrefixDesign.PluginPrefix("Mod", RED_BOLD, true));
            store.save(changed);

            assertThat(new PrefixFile(file).load()).isEqualTo(changed);
        }

        @Test
        @DisplayName("a broken file is reported and the defaults used, and the broken file is not overwritten")
        void broken() throws Exception {
            Path file = directory.resolve("prefix.yml");
            Files.writeString(file, "mode: [unclosed\n  - : :");

            assertThat(new PrefixFile(file).load()).isEqualTo(PrefixDesign.DEFAULT);
            assertThat(Files.readString(file)).contains("[unclosed");
        }

        @Test
        @DisplayName("an unknown mode or a bad style is read as the default for that one value")
        void badValues() throws Exception {
            Path file = directory.resolve("prefix.yml");
            Files.writeString(file, """
                    mode: sideways
                    tag: SMP
                    style: "not a colour|bold"
                    """);

            PrefixDesign read = new PrefixFile(file).load();
            assertThat(read.mode()).isEqualTo(PrefixDesign.Mode.PER_PLUGIN);
            assertThat(read.tag()).isEqualTo("SMP");
            assertThat(read.style().has(TextDecoration.BOLD)).isTrue();
        }
    }

    @Test
    @DisplayName("plugins are remembered as they introduce themselves, so the menu can list them")
    void known() {
        Prefixes.introduce("Claims");
        Prefixes.introduce("claims");
        Prefixes.introduce("Homes");

        assertThat(Prefixes.known()).contains("Claims", "Homes");
        assertThat(Prefixes.known().stream().filter(name -> name.equalsIgnoreCase("claims"))).hasSize(1);
        assertThat(Map.copyOf(PrefixDesign.DEFAULT.plugins())).isEmpty();
    }
}
