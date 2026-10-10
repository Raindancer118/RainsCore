package de.raindancer.core.data.store;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShippedEntriesTest {

    private static final String SHIPPED = """
            # The roles.
            roles:
              cook:
                title: Cook
                abilities:
                  - harvest: 20
                perks:
                  - buy: 15

              biologist:
                title: Biologist
                abilities:
                  - harvest: 10
            """;

    private static final String OWNERS = """
            # The roles — my server's.
            roles:
              cook:
                title: Chef   # renamed by the owner
                perks:
                  - buy: 20

              hunter:
                title: Hunter
            """;

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    @DisplayName("a new shipped entry is added, a missing field filled in; the owner's edits and comments stay")
    void merges() throws Exception {
        ShippedEntries.Merged merged = ShippedEntries.merge(OWNERS, SHIPPED, "roles", Set.of("abilities"));
        assertThat(merged.added()).containsExactly("biologist");
        assertThat(merged.filled()).containsExactly("cook.abilities");
        assertThat(merged.text()).contains("renamed by the owner").contains("my server's");
        YamlConfiguration read = yaml(merged.text());
        assertThat(read.getString("roles.cook.title")).isEqualTo("Chef");
        assertThat(read.getMapList("roles.cook.perks").getFirst().get("buy")).isEqualTo(20);
        assertThat(read.getMapList("roles.cook.abilities")).hasSize(1);
        assertThat(read.getString("roles.biologist.title")).isEqualTo("Biologist");
        assertThat(read.getString("roles.hunter.title")).isEqualTo("Hunter");
    }

    @Test
    @DisplayName("merged again, nothing changes; an entry the owner deletes afterwards is not brought back")
    void onceOnly() {
        String once = ShippedEntries.merge(OWNERS, SHIPPED, "roles", Set.of("abilities")).text();
        ShippedEntries.Merged twice = ShippedEntries.merge(once, SHIPPED, "roles", Set.of("abilities"));
        assertThat(twice.changed()).isFalse();
        String deleted = once.replace("""
                  biologist:
                    title: Biologist
                    abilities:
                      - harvest: 10
                """, "");
        assertThat(deleted).doesNotContain("biologist:");
        assertThat(ShippedEntries.merge(deleted, SHIPPED, "roles", Set.of("abilities")).added()).isEmpty();
    }

    @Test
    @DisplayName("fields not named are never filled in — the owner may have taken perks off on purpose")
    void onlyNamedFields() {
        String noPerks = OWNERS.replace("""
                    perks:
                      - buy: 20
                """, "");
        assertThat(ShippedEntries.merge(noPerks, SHIPPED, "roles", Set.of("abilities")).filled())
                .containsExactly("cook.abilities");
    }

    @Test
    @DisplayName("on disk: written out when missing, merged when there, and a file already current stays as it was")
    void onDisk(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        java.nio.file.Path file = folder.resolve("roles.yml");
        java.util.function.Supplier<java.io.InputStream> shipped =
                () -> new java.io.ByteArrayInputStream(SHIPPED.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ShippedEntries.bringUp(file, shipped, "roles", Set.of("abilities"));
        assertThat(yaml(java.nio.file.Files.readString(file)).getString("roles.biologist.title")).isEqualTo("Biologist");
        String first = java.nio.file.Files.readString(file);
        assertThat(ShippedEntries.bringUp(file, shipped, "roles", Set.of("abilities")).changed()).isFalse();
        assertThat(java.nio.file.Files.readString(file)).isEqualTo(first);

        java.nio.file.Files.writeString(file, OWNERS);
        assertThat(ShippedEntries.bringUp(file, shipped, "roles", Set.of("abilities")).added()).containsExactly("biologist");
        assertThat(java.nio.file.Files.readString(file)).contains("renamed by the owner");
        assertThat(java.nio.file.Files.readString(file).split("biologist:", -1)).as("added once").hasSize(2);
    }
}
