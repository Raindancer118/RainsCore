package de.raindancer.core.world.manage;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which worlds are one world's dimensions, by Minecraft's own naming, and where a portal between them
 * should really lead.
 */
class WorldFamilyTest {

    @Test
    @DisplayName("any member names the whole family")
    void fromAnyMember() {
        assertThat(WorldFamily.of("farm").overworld()).isEqualTo("farm");
        assertThat(WorldFamily.of("farm_nether").overworld()).isEqualTo("farm");
        assertThat(WorldFamily.of("farm_the_end").overworld()).isEqualTo("farm");
        assertThat(WorldFamily.of("farm").nether()).isEqualTo("farm_nether");
        assertThat(WorldFamily.of("farm").theEnd()).isEqualTo("farm_the_end");
    }

    @Test
    @DisplayName("a name that merely contains the suffix is not a dimension of anything")
    void onlyTheSuffix() {
        assertThat(WorldFamily.of("nether_hub").overworld()).isEqualTo("nether_hub");
        assertThat(WorldFamily.of("_nether").overworld()).isEqualTo("_nether");
    }

    @Test
    @DisplayName("membership and the member for a dimension ignore case, the way Bukkit looks worlds up")
    void membership() {
        WorldFamily family = WorldFamily.of("Farm");
        assertThat(family.contains("farm_NETHER")).isTrue();
        assertThat(family.contains("farmland")).isFalse();
        assertThat(family.inDimension(World.Environment.NORMAL)).contains("Farm");
        assertThat(family.inDimension(World.Environment.NETHER)).contains("Farm_nether");
        assertThat(family.inDimension(World.Environment.THE_END)).contains("Farm_the_end");
        assertThat(family.inDimension(World.Environment.CUSTOM)).isEmpty();
        assertThat(family.members()).containsExactly("Farm", "Farm_nether", "Farm_the_end");
    }

    private static World world(String name, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
    }

    @Test
    @DisplayName("a portal out of a family member that the server sent to its own nether is sent to the family's")
    void redirectsIntoTheFamily() {
        World farm = world("farm", World.Environment.NORMAL);
        World serverNether = world("world_nether", World.Environment.NETHER);
        World farmNether = world("farm_nether", World.Environment.NETHER);
        Location to = new Location(serverNether, 12.5, 64, -3);

        Optional<Location> redirected = WorldFamily.of("farm").redirect(farm, to,
                Map.of("farm_nether", farmNether)::get);

        assertThat(redirected).isPresent();
        assertThat(redirected.get().getWorld()).isSameAs(farmNether);
        assertThat(redirected.get().getX()).isEqualTo(12.5);
        assertThat(redirected.get().getZ()).isEqualTo(-3);
    }

    @Test
    @DisplayName("nothing to change when the server already chose right, or the travel is not the family's")
    void leavesOthersAlone() {
        World farm = world("farm", World.Environment.NORMAL);
        World farmNether = world("farm_nether", World.Environment.NETHER);
        World elsewhere = world("hub", World.Environment.NORMAL);

        assertThat(WorldFamily.of("farm").redirect(farm, new Location(farmNether, 0, 64, 0),
                Map.of("farm_nether", farmNether)::get)).isEmpty();
        assertThat(WorldFamily.of("farm").redirect(elsewhere, new Location(farmNether, 0, 64, 0),
                Map.of("farm_nether", farmNether)::get)).isEmpty();
    }

    @Test
    @DisplayName("a family dimension that is not loaded leaves the server's own answer standing")
    void notLoaded() {
        World farm = world("farm", World.Environment.NORMAL);
        World serverNether = world("world_nether", World.Environment.NETHER);

        assertThat(WorldFamily.of("farm").redirect(farm, new Location(serverNether, 0, 64, 0),
                name -> null)).isEmpty();
    }
}
