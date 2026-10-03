package de.raindancer.core.content.loot;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** loot.yml as a server owner writes it, read on a server that does not know everything in it. */
class LootTablesFileTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("an entry naming a block this server lacks is kept in the file when the table is saved")
    void anUnknownEntrySurvivesTheNextSave() throws IOException {
        Path file = folder.resolve("loot.yml");
        Files.writeString(file, """
                tables:
                  hg:tier-one:
                    tier: 1
                    fill-percent: 30
                    entries:
                    - material: STONE
                      weight: 5
                    - material: BLOCK_FROM_A_NEWER_VERSION
                      weight: 2
                """);
        LootTables tables = new LootTables(file);
        tables.load();
        assertThat(tables.byKey("hg:tier-one")).hasValueSatisfying(table ->
                assertThat(table.entries()).hasSize(1));

        // Anything that marks the file for saving.
        tables.define(LootTable.builder("hg", "tier-two").build());
        tables.flush();

        List<Map<?, ?>> written = YamlConfiguration.loadConfiguration(file.toFile())
                .getMapList("tables.hg:tier-one.entries");
        assertThat(written.stream().map(entry -> String.valueOf(entry.get("material"))).toList())
                .as("skipped on this server is not deleted — it reads again on the one it was written for")
                .containsExactlyInAnyOrder("STONE", "BLOCK_FROM_A_NEWER_VERSION");
    }
}
