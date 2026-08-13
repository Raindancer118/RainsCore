package de.raindancer.core.world.speedrun;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * The two items a player in a ready lobby is handed: a compass that opens
 * {@link SpeedrunLobbyMenu}, and a block that starts a run.
 *
 * <h2>Why this is hand-rolled rather than going through {@code CustomItems}/{@code ItemFactory}</h2>
 * Same reasoning as the Hunger Games module's {@code AdminHotbarListener}: these are phase-gated UI
 * buttons, not persistent definitions a server owner configures — there is nothing to put in a
 * catalogue, no recipe, no ability with a cooldown. What they need from {@code ItemFactory} is
 * exactly one thing, recognising a stack by a key in its persistent data container rather than by
 * material or name, and that is small enough to own directly.
 */
public final class SpeedrunLobbyItems {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String MENU = "menu";
    private static final String START = "start";

    private final NamespacedKey marker;

    public SpeedrunLobbyItems(Plugin plugin) {
        this.marker = new NamespacedKey(plugin, "speedrun-lobby-item");
    }

    /** The compass that opens the lobby's menu. */
    public ItemStack menuCompass() {
        return tagged(Material.COMPASS, "<white>Speedrun menu",
                "<gray>Pick the advancement and death rule,", "<gray>or see the run in progress.",
                MENU);
    }

    /** The block that starts a run. */
    public ItemStack startBlock() {
        return tagged(Material.LIME_CONCRETE, "<green>Start the run",
                "<gray>Everybody currently in the lobby world", "<gray>races.",
                START);
    }

    /** Clears the player's inventory and gives them exactly these two items. */
    public void give(Player player) {
        player.getInventory().clear();
        player.getInventory().addItem(menuCompass(), startBlock());
    }

    public boolean isMenu(ItemStack stack) {
        return tagOf(stack).map(MENU::equals).orElse(false);
    }

    public boolean isStart(ItemStack stack) {
        return tagOf(stack).map(START::equals).orElse(false);
    }

    private Optional<String> tagOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return Optional.ofNullable(meta.getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }

    private ItemStack tagged(Material material, String name, String loreOne, String loreTwo, String tag) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MINI.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                MINI.deserialize(loreOne).decoration(TextDecoration.ITALIC, false),
                MINI.deserialize(loreTwo).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, tag);
        stack.setItemMeta(meta);
        return stack;
    }
}
