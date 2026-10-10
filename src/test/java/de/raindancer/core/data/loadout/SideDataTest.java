package de.raindancer.core.data.loadout;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SideDataTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final Player player = mock(Player.class);

    @AfterEach
    void reset() {
        SideData.clear();
    }

    /** A part kept in a map, as a plugin's own store would be. */
    private static final class Remembered implements SideData.Part {
        final Map<Player, String> worn = new HashMap<>();

        public String id() {
            return "test:style";
        }

        public String capture(Player player) {
            return worn.get(player);
        }

        public void apply(Player player, String value) {
            if (value == null) {
                worn.remove(player);
            } else {
                worn.put(player, value);
            }
        }
    }

    @Test
    @DisplayName("a side is captured, another put on, and the first comes back — the listeners told each time")
    void swaps() {
        Remembered style = new Remembered();
        SideData.keep(owner, style);
        List<Player> told = new ArrayList<>();
        SideData.onSwitched(owner, told::add);

        style.worn.put(player, "gold");
        Map<String, String> survival = SideData.capture(player);
        assertThat(survival).containsEntry("test:style", "gold");

        SideData.apply(player, Map.of());
        assertThat(style.worn).as("a side that had nothing puts nothing on").isEmpty();
        style.worn.put(player, "red");
        Map<String, String> admin = SideData.capture(player);

        SideData.apply(player, survival);
        assertThat(style.worn.get(player)).isEqualTo("gold");
        SideData.apply(player, admin);
        assertThat(style.worn.get(player)).isEqualTo("red");
        assertThat(told).hasSize(3);
    }

    @Test
    @DisplayName("a part that throws is left out, and the others still go")
    void broken() {
        SideData.keep(owner, new SideData.Part() {
            public String id() {
                return "test:broken";
            }

            public String capture(Player player) {
                throw new IllegalStateException("no");
            }

            public void apply(Player player, String value) {
                throw new IllegalStateException("no");
            }
        });
        Remembered style = new Remembered();
        SideData.keep(owner, style);
        style.worn.put(player, "blue");
        assertThat(SideData.capture(player)).containsOnlyKeys("test:style");
        SideData.apply(player, Map.of("test:style", "green"));
        assertThat(style.worn.get(player)).isEqualTo("green");
    }

    @Test
    @DisplayName("what a disabled plugin's code registered is forgotten")
    void forgetFrom() {
        Remembered style = new Remembered();
        SideData.keep(owner, style);
        assertThat(SideData.forgetFrom(style.getClass().getClassLoader())).isPositive();
        style.worn.put(player, "gold");
        assertThat(SideData.capture(player)).isEmpty();
    }

    @Test
    @DisplayName("persistent-data values survive the trip through a line of text, every plain type")
    void lines() {
        for (PdcLine.Typed value : List.of(new PdcLine.Typed("s", "flame with spaces\\nand=signs"),
                new PdcLine.Typed("i", "-42"), new PdcLine.Typed("ba", "AAEC"), new PdcLine.Typed("ia", "1,2,3"),
                new PdcLine.Typed("s", ""))) {
            String line = PdcLine.write("particle-colour", value);
            assertThat(line).doesNotContain("\n");
            assertThat(PdcLine.read(line)).hasValueSatisfying(entry -> {
                assertThat(entry.key()).isEqualTo("particle-colour");
                assertThat(entry.value()).isEqualTo(value);
            });
        }
        assertThat(PdcLine.read("broken")).isEmpty();
        assertThat(PdcLine.ints("1,2,3")).containsExactly(1, 2, 3);
    }
}
