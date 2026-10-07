package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a teleport somewhere would be let in — asked before offering a claim's warp, so a list does not
 * show somebody a door they will only be turned away at.
 */
class MayTeleportIntoTest {

    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });
    private final Location inside = new Location(world, 0, 64, 0);

    private Land land;
    private ProtectedArea here;

    @BeforeEach
    void setUp() {
        land = new Land(LandPolicies.builtIn(), new Messages(Path.of("target", "tp-in-messages.yml")), () -> 0L);
        land.provider(new LandProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Optional<ProtectedArea> at(Location location) {
                return Optional.ofNullable(here);
            }

            @Override
            public boolean hasAnyIn(World world) {
                return here != null;
            }
        });
    }

    @Test
    @DisplayName("outside any claim, anybody may arrive")
    void wilderness() {
        assertThat(land.mayTeleportInto(new FakePlayer().player(), inside)).isTrue();
    }

    @Test
    @DisplayName("a claim that lets visitors teleport in lets this one in")
    void open() {
        FakePlayer visitor = new FakePlayer();
        here = FakeArea.named("base").with(LandFlag.TELEPORT_IN, true).admitting(visitor.id(), LandAction.ENTER);

        assertThat(land.mayTeleportInto(visitor.player(), inside)).isTrue();
    }

    @Test
    @DisplayName("a claim that keeps visitors from teleporting in keeps them out, and still lets its owner in")
    void closedToVisitors() {
        FakePlayer visitor = new FakePlayer();
        FakePlayer owner = new FakePlayer();
        here = FakeArea.named("base").ownedBy(owner.id())
                .with(LandFlag.TELEPORT_IN, LandAudience.VISITOR, false)
                .with(LandFlag.TELEPORT_IN, LandAudience.OWNER, true)
                .admitting(visitor.id(), LandAction.ENTER);

        assertThat(land.mayTeleportInto(visitor.player(), inside)).isFalse();
        assertThat(land.mayTeleportInto(owner.player(), inside)).isTrue();
    }

    @Test
    @DisplayName("somebody who may not be there at all — banned — is not let in by any flag")
    void banned() {
        FakePlayer banned = new FakePlayer();
        here = FakeArea.named("base").with(LandFlag.TELEPORT_IN, true);

        assertThat(land.mayTeleportInto(banned.player(), inside)).isFalse();
    }

    @Test
    @DisplayName("staff bypassing protection go anywhere")
    void bypass() {
        FakePlayer staff = new FakePlayer().holding(Land.BYPASS_PERMISSION_CORE, Land.ADMIN_PERMISSION_CORE);
        here = FakeArea.named("base").with(LandFlag.TELEPORT_IN, false);
        land.toggleBypass(staff.player());

        assertThat(land.mayTeleportInto(staff.player(), inside)).isTrue();
    }
}
