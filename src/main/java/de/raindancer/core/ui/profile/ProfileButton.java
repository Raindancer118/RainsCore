package de.raindancer.core.ui.profile;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * One button an outside module wants drawn on {@link ProfileMenu}, for whichever player is being
 * looked at and whoever is looking.
 *
 * @see ProfileExtension
 */
public record ProfileButton(ItemStack icon, Consumer<InventoryClickEvent> onClick) {
}
