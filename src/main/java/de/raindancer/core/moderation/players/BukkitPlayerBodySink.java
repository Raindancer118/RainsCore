package de.raindancer.core.moderation.players;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** {@link PlayerBodySink} on a real server, each change on the player's own thread (Folia). */
public final class BukkitPlayerBodySink implements PlayerBodySink {

    private final Plugin plugin;

    public BukkitPlayerBodySink(Plugin plugin) {
        this.plugin = plugin;
    }

    private void onTarget(UUID who, Consumer<Player> action) {
        Player player = who == null ? null : Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        if (plugin == null) {
            action.accept(player);
            return;
        }
        Scheduling.onOwner(plugin, player, () -> action.accept(player));
    }

    @Override
    public Optional<BodyState> stateOf(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return Optional.empty();
        }
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        return Optional.of(new BodyState(player.getRemainingAir(), player.getMaximumAir(), player.getHealth(),
                scale == null ? 1 : scale.getBaseValue(), player.getWalkSpeed(), player.getFlySpeed(),
                player.getFireTicks()));
    }

    @Override
    public void air(UUID who, int air) {
        onTarget(who, player -> player.setRemainingAir(Math.clamp(air, 0, player.getMaximumAir())));
    }

    @Override
    public void drowningDamage(UUID who, double amount) {
        onTarget(who, player -> player.damage(amount, DamageSource.builder(DamageType.DROWN).build()));
    }

    @Override
    public void launch(UUID who, double x, double y, double z, boolean relativeToLook) {
        onTarget(who, player -> {
            Vector push;
            if (relativeToLook) {
                Vector facing = player.getLocation().getDirection().normalize();
                Vector right = facing.clone().crossProduct(new Vector(0, 1, 0));
                if (right.lengthSquared() < 1e-6) {
                    right = new Vector(1, 0, 0);
                }
                push = facing.multiply(z).add(right.normalize().multiply(x)).add(new Vector(0, y, 0));
            } else {
                push = new Vector(x, y, z);
            }
            // Falling from a launch is what the command is for; the fall damage that follows is not the
            // player's fault, and a launch that kills on landing is a kill the button did not mention.
            player.setFallDistance(0);
            player.setVelocity(push);
        });
    }

    @Override
    public void scale(UUID who, double scale) {
        onTarget(who, player -> {
            AttributeInstance attribute = player.getAttribute(Attribute.SCALE);
            if (attribute != null) {
                attribute.setBaseValue(Math.clamp(scale, PlayerBody.MIN_SCALE, PlayerBody.MAX_SCALE));
            }
        });
    }

    @Override
    public void walkSpeed(UUID who, float speed) {
        onTarget(who, player -> player.setWalkSpeed(Math.clamp(speed, -1f, 1f)));
    }

    @Override
    public void flySpeed(UUID who, float speed) {
        onTarget(who, player -> player.setFlySpeed(Math.clamp(speed, -1f, 1f)));
    }

    @Override
    public void wipe(UUID who, Set<PlayerBody.Wipe> parts) {
        onTarget(who, player -> {
            if (parts.contains(PlayerBody.Wipe.INVENTORY)) {
                player.getInventory().clear();
                player.setItemOnCursor(null);
            }
            if (parts.contains(PlayerBody.Wipe.ENDER_CHEST)) {
                player.getEnderChest().clear();
            }
            if (parts.contains(PlayerBody.Wipe.EXPERIENCE)) {
                player.setLevel(0);
                player.setExp(0);
                player.setTotalExperience(0);
            }
            if (parts.contains(PlayerBody.Wipe.EFFECTS)) {
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            }
            if (parts.contains(PlayerBody.Wipe.ADVANCEMENTS)) {
                // Advancements are server-wide and the iterator walks the registry, which is safe from
                // any region thread; revoking only touches this player's own progress.
                Iterator<Advancement> all = Bukkit.advancementIterator();
                while (all.hasNext()) {
                    AdvancementProgress progress = player.getAdvancementProgress(all.next());
                    for (String criterion : progress.getAwardedCriteria()) {
                        progress.revokeCriteria(criterion);
                    }
                }
            }
        });
    }

    @Override
    public void explode(UUID who, float power, boolean breakBlocks, boolean fire) {
        onTarget(who, player -> {
            Location at = player.getLocation();
            at.getWorld().createExplosion(at, power, fire, breakBlocks);
        });
    }

    @Override
    public void ignite(UUID who, int ticks) {
        onTarget(who, player -> player.setFireTicks(Math.max(player.getFireTicks(), ticks)));
    }
}
