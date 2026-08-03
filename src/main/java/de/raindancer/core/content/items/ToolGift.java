package de.raindancer.core.content.items;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Handing a player a tool the plugin wants them to have, without also handing them an achievement.
 *
 * <h2>Why this exists</h2>
 * The no-claim-zone tool is a blaze rod, so an admin clicking "mark somewhere out" was congratulated with
 * <b>Into Fire</b> — a nether milestone. Vanilla's criteria is "this item appeared in your inventory", and it
 * does not care where from. An advancement is a record of something the player did; one handed out by a menu
 * click devalues every other one on the server, and on a server with an achievement feed it announces itself
 * to everybody.
 *
 * <h2>Only what we caused</h2>
 * What the player has already earned is read <em>before</em> the item is given. Read afterwards, everybody looks
 * as though they already had it, because by then the item is in the inventory and vanilla has granted it. So the
 * order is the whole correctness argument: somebody who found their blaze rod in a fortress last week keeps it,
 * and only an advancement that appeared during this hand-over is taken back.
 *
 * <p>Deliberately narrow. This knows about the handful of materials that are an advancement in themselves, not
 * about advancements in general — a plugin giving somebody a diamond is not the reason they get "Diamonds!",
 * and guessing would take away things people earned.
 */
public final class ToolGift {

    /**
     * Materials whose mere appearance in an inventory completes a vanilla advancement.
     *
     * <p>Only the ones a plugin plausibly hands out as a tool or a marker. Everything else is left alone,
     * because a wrong entry here silently strips something a player earned.
     */
    private static final Map<Material, String> EARNED_BY_HOLDING = Map.of(
            Material.BLAZE_ROD, "minecraft:nether/obtain_blaze_rod",
            Material.ELYTRA, "minecraft:end/elytra",
            Material.NETHERITE_INGOT, "minecraft:nether/obtain_ancient_debris",
            Material.ECHO_SHARD, "minecraft:adventure/avoid_vibration");

    private ToolGift() {
    }

    /**
     * Puts the item in their inventory, drops what will not fit, and undoes any advancement that only happened
     * because of it.
     *
     * @return whether all of it fitted; false means some of it is on the ground at their feet
     */
    public static boolean give(Player player, ItemStack tool) {
        if (player == null || tool == null || tool.getType().isAir()) {
            return false;
        }

        // Read first. Afterwards the item is already in the inventory and vanilla has granted it, so everybody
        // looks as though they had it all along and nothing would ever be undone.
        Advancement advancement = advancementFor(tool.getType());
        boolean earnedAlready = advancement != null
                && player.getAdvancementProgress(advancement).isDone();

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(tool);
        for (ItemStack leftover : leftovers.values()) {
            // Dropped rather than swallowed: a command that silently does nothing for whoever has a full
            // inventory looks broken to exactly the people most likely to have one.
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }

        if (advancement != null && !earnedAlready) {
            revoke(player, advancement);
        }
        return leftovers.isEmpty();
    }

    private static Advancement advancementFor(Material material) {
        String key = EARNED_BY_HOLDING.get(material);
        if (key == null) {
            return null;
        }
        NamespacedKey parsed = NamespacedKey.fromString(key);
        return parsed == null ? null : Bukkit.getAdvancement(parsed);
    }

    /**
     * Takes the advancement back, criterion by criterion.
     *
     * <p>There is no "revoke the whole thing" call — an advancement is done when its criteria are, so undoing it
     * means undoing each. Awarded criteria only, so nothing else the player has part-finished is disturbed.
     */
    private static void revoke(Player player, Advancement advancement) {
        var progress = player.getAdvancementProgress(advancement);
        for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
            progress.revokeCriteria(criterion);
        }
    }
}
