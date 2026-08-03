package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window title saying where you are, not just what you are looking at.
 *
 * <h2>Why bother</h2>
 * A chest menu has no other chrome. There is a Back button, but nothing that says what Back goes back to, so
 * three levels into a plugin the title read "Trusted people" and nothing on screen said which claim that
 * belonged to. With the page it was opened from in front of it, the title is the window's only orientation:
 *
 * <pre>
 * RSC » Server › All claims
 * </pre>
 *
 * <h2>The page wins the budget</h2>
 * A title gets 154 pixels and Minecraft clips it by cutting the <em>end</em> off, which is the worst possible
 * end to lose: joining the names naively produced {@code Claims » claimtrials › Trusted…} — the space went on
 * where you came from and where you are was cut off mid-word.
 *
 * <p>So the parent is included only when both names fit whole, and dropped when they do not. That is the whole
 * contract, and it is why two short names get a trail and two long ones do not. A title that says less beats
 * one that trails off, and the alternative — abbreviating the parent — produces titles nobody can read either.
 *
 * <p>Only ever the immediate parent, never the chain: three names never fit, so a chain would mean the trail
 * silently switched itself off on every deep page.
 */
class MenuTrailTest {

    private static final Brand BRAND = new Brand("Claims");

    /** A menu that does nothing but have a name and a parent, which is all the trail is about. */
    private static final class Page extends Menu {

        private final String name;

        private Page(String name, Menu parent) {
            super(null, BRAND, parent);
            this.name = name;
        }

        @Override
        protected Component title() {
            return Component.text(name);
        }

        @Override
        protected void render() {
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a front page is just itself")
    void aRootPageHasNoTrail() {
        assertThat(plain(new Page("Your claims", null).windowTitle()))
                .contains("Your claims")
                .doesNotContain("›");
    }

    @Test
    @DisplayName("two names that fit both appear, in the order you walked them")
    void achildCarriesItsParent() {
        // The shape from the screenshot this was modelled on: RSC » Server › All claims.
        Page parent = new Page("Server", null);
        String title = plain(new Page("All claims", parent).windowTitle());

        assertThat(title)
                .as("without this, nothing on screen says what Back goes back to")
                .contains("Server")
                .contains("All claims")
                .contains("\u203a");
        assertThat(title.indexOf("Server")).isLessThan(title.indexOf("All claims"));
    }

    @Test
    @DisplayName("two names that do not fit lose the parent, not the page")
    void thePageIsNeverTheOneClipped() {
        Page parent = new Page("claimtrials", null);
        String title = plain(new Page("Trusted people", parent).windowTitle());

        assertThat(title)
                .as("the page is where you ARE; clipping it mid-word is the one outcome worth avoiding")
                .contains("Trusted people")
                .doesNotContain("\u2026");
        assertThat(title)
                .as("dropped whole rather than abbreviated to something nobody can read")
                .doesNotContain("claimtrials");
    }

    @Test
    @DisplayName("the trail is one level, never the whole chain")
    void theTrailStopsAtOne() {
        Page grandparent = new Page("A", null);
        Page parent = new Page("B", grandparent);

        assertThat(plain(new Page("C", parent).windowTitle()))
                .as("three names never fit, so a chain would switch the trail off on every deep page")
                .contains("B")
                .contains("C")
                .doesNotContain("A \u203a");
    }

    @Test
    @DisplayName("the brand is still in front of it")
    void theBrandComesFirst() {
        Page parent = new Page("Server", null);
        String title = plain(new Page("Fence", parent).windowTitle());

        assertThat(title).startsWith("Claims");
        assertThat(title.indexOf("Server")).isLessThan(title.indexOf("Fence"));
    }

    @Test
    @DisplayName("a player-supplied name is never read as markup")
    void anameIsNotParsedAsColour() {
        // A claim called "<red>" must not colour the rest of the title, and a claim called
        // "<click:run_command:/op me>" must not become a click event in a window title. This is the reason
        // the trail is built from Components rather than by concatenating MiniMessage.
        Page parent = new Page("<red>", null);

        assertThat(plain(new Page("Fence", parent).windowTitle()))
                .contains("<red>")
                .contains("Fence");
    }
}
