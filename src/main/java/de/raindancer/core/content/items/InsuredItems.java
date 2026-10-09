package de.raindancer.core.content.items;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Items somebody pays to insure: whoever keeps the policies marks the stack with its owner and policy, and
 * every other plugin asks here before taking such an item off somebody — a shop buying it, an auction, a
 * raffle — because an insured item is meant to come back to its owner, not to be sold out from under the policy.
 *
 * <p>The mark travels on the stack; whether it still counts is the policy keeper's answer, so a lapsed policy
 * needs no hunt for every copy of its item. With no keeper, nothing is insured.
 */
public final class InsuredItems {

    public static final NamespacedKey OWNER = new NamespacedKey("rainscore", "insured-owner");
    public static final NamespacedKey POLICY = new NamespacedKey("rainscore", "insured-policy");

    private static final LogChannel log = Log.of("items");

    private record Provided(Plugin owner, Predicate<String> active) {
    }

    private static final List<Provided> keepers = new CopyOnWriteArrayList<>();

    private InsuredItems() {
    }

    /** Starts answering whether a policy is in force. */
    public static synchronized void provide(Plugin owner, Predicate<String> active) {
        if (active != null && keepers.stream().noneMatch(each -> each.active() == active)) {
            keepers.add(new Provided(owner, active));
        }
    }

    public static synchronized void retract(Predicate<String> active) {
        keepers.removeIf(each -> each.active() == active);
    }

    /** Whether a policy is in force with any keeper. False with none, and for a keeper that throws. */
    public static boolean inForce(String policy) {
        if (policy == null || policy.isBlank()) {
            return false;
        }
        for (Provided each : keepers) {
            try {
                if (each.active().test(policy)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                log.error(broken, "An insurance keeper could not say whether policy {} is in force.", policy);
            }
        }
        return false;
    }

    /** Marks the stack as insured for {@code owner} under {@code policy}; returns the same stack. */
    public static ItemStack mark(ItemStack stack, UUID owner, String policy) {
        if (stack == null || owner == null || policy == null) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner.toString());
        meta.getPersistentDataContainer().set(POLICY, PersistentDataType.STRING, policy);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Takes the mark off; returns the same stack. */
    public static ItemStack strip(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().remove(OWNER);
        meta.getPersistentDataContainer().remove(POLICY);
        stack.setItemMeta(meta);
        return stack;
    }

    public static Optional<String> policy(ItemStack stack) {
        return read(stack, POLICY);
    }

    public static Optional<UUID> owner(ItemStack stack) {
        return read(stack, OWNER).flatMap(text -> {
            try {
                return Optional.of(UUID.fromString(text));
            } catch (IllegalArgumentException unreadable) {
                return Optional.empty();
            }
        });
    }

    /** Marked, and its policy in force — what a shop or an auction refuses. */
    public static boolean isInsured(ItemStack stack) {
        return policy(stack).filter(InsuredItems::inForce).isPresent();
    }

    private static Optional<String> read(ItemStack stack, NamespacedKey key) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? Optional.empty()
                : Optional.ofNullable(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = keepers.size();
        keepers.removeIf(each -> PluginCode.isFrom(each.active(), loader));
        return before - keepers.size();
    }

    public static synchronized void clear() {
        keepers.clear();
    }
}
