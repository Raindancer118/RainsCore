package de.raindancer.core.content.items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** An item nobody named is shown by its id, not as the word "null". */
class ItemMenusNameTest {

    @Test
    @DisplayName("the item menus never print the raw, nullable display name")
    void namesFallBackToTheId() throws IOException {
        for (String menu : new String[] {"ItemsMenu.java", "RecipeMenu.java"}) {
            String source = Files.readString(
                    Path.of("src/main/java/de/raindancer/core/content/items").resolve(menu));
            assertThat(source).as(menu).doesNotContain("+ item.displayName()");
        }
    }
}
