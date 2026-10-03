package de.raindancer.core.ui.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A colour setting that is not a colour falls back to the preset rather than reaching MiniMessage. */
class StyleColourTest {

    @AfterEach
    void reset() {
        Style.configure(key -> null);
    }

    @Test
    @DisplayName("a tag name that is not a colour is refused")
    void tagsAreNotColours() {
        Style.configure(key -> null);
        String preset = Style.itemName();

        Style.configure(key -> "bold");
        assertThat(Style.itemName())
                .as("'bold' passed as a colour went into every title as <bold>")
                .isEqualTo(preset);

        Style.configure(key -> "gold");
        assertThat(Style.itemName()).isEqualTo("gold");
        Style.configure(key -> "#ff2020");
        assertThat(Style.itemName()).isEqualTo("#ff2020");
    }
}
