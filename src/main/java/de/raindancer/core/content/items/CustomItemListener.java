package de.raindancer.core.content.items;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * The one thing that turns clicking a custom item into that item doing something.
 *
 * <h2>Why this had to be written, and what it was like without it</h2>
 * Every piece of the machinery already existed. {@link CustomItems} held sixteen definitions,
 * {@link ItemFactory} could recognise any of them from the key in its persistent data container,
 * {@link ItemAbilities} owned the charges and the cooldowns and would run an effect atomically, and four
 * services in the Hunger Games module registered fourteen abilities between them and said so in the boot
 * log. Nothing anywhere called {@link ItemAbilities#use}.
 *
 * <p>So every custom item on the server was inert. A medikit was a melon slice, a smoke bomb was gunpowder,
 * the Fiendfinder was a spyglass. Nothing threw, nothing was logged, and the boot log counted sixteen items
 * defined — which is exactly the failure mode that is impossible to find by reading a diff, because every
 * line of it is correct on its own.
 *
 * <h2>Why it is Core's and not a module's</h2>
 * {@link CustomItems} and {@link ItemAbilities} are Core's, so the code that joins them is too. Any plugin
 * that defines an item with an ability needs this, and a second copy in a second module would be two
 * listeners on one event both spending the same charge for one click.
 *
 * <h2>The three things this gets right that a hand-rolled one gets wrong</h2>
 * <ul>
 *   <li><b>One click is one use.</b> A right click fires {@link PlayerInteractEvent} once for the main hand
 *       and again for the off hand, so a version that does not check {@link EquipmentSlot#HAND} spends a
 *       single-use item twice for one click. The plugin this was ported from had that bug.</li>
 *   <li><b>Air and block are the same click.</b> Handled in {@link ItemTrigger#forClick} rather than here —
 *       an item that works in the open and does nothing beside a wall is the other half of the same bug.</li>
 *   <li><b>The vanilla behaviour is cancelled only when the ability actually ran.</b> A medikit is a
 *       glistering melon slice and the Fiendfinder is a spyglass; without the cancel the player eats the
 *       one and zooms with the other <em>as well as</em> using it. But a use that declined — a grappling
 *       hook aimed at the sky — must leave the click alone, or an item that missed also silently swallows
 *       whatever the player was actually trying to do.</li>
 * </ul>
 */
public final class CustomItemListener implements Listener {

    private final CustomItems items;
    private final ItemFactory factory;
    private final ItemAbilities abilities;
    private final Messages messages;

    public CustomItemListener(CustomItems items, ItemFactory factory, ItemAbilities abilities,
                              Messages messages) {
        this.items = items;
        this.factory = factory;
        this.abilities = abilities;
        this.messages = messages;
    }

    /**
     * Clicking while holding something.
     *
     * <p>{@link EventPriority#NORMAL} and not {@code ignoreCancelled}: a right click on air is never
     * cancelled by anything, and a right click on a block inside a protected claim is cancelled for the
     * <em>block</em> — refusing to let somebody use their own smoke bomb because they were facing a fence
     * would be a protection rule nobody wrote. What is respected is {@link PlayerInteractEvent#useItemInHand}
     * being denied, which is the specific "this player may not use what they are holding" answer.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // one click, one use — the off hand fires a second event for the same click
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Optional<ItemTrigger> trigger = ItemTrigger.forClick(event.getAction());
        if (trigger.isEmpty()) {
            return;
        }
        ItemStack held = event.getItem();
        Optional<ItemAbility> ability = abilityOf(held);
        if (ability.isEmpty()) {
            return;
        }
        if (ability.get().trigger() != trigger.get()) {
            return;   // a left click on a right-click item is not an event worth answering
        }

        Player player = event.getPlayer();
        UseResult result = abilities.use(player.getUniqueId(), ability.get().key(), trigger.get());

        switch (result.outcome()) {
            case RAN -> {
                event.setCancelled(true);
                if (ability.get().consumesItem() || result.itemIsSpent()) {
                    takeOne(player, held);
                }
            }
            case ON_COOLDOWN -> {
                event.setCancelled(true);
                messages.send(player, "items.cooling-down", "item", nameOf(held),
                        "seconds", String.valueOf(
                                Math.max(1, result.remaining().orElse(java.time.Duration.ZERO)
                                        .toSeconds())));
            }
            case NO_CHARGES -> {
                event.setCancelled(true);
                messages.send(player, "items.used-up", "item", nameOf(held));
            }
            case FAILED -> {
                // Logged with its stack trace by ItemAbilities. The player is told something rather than
                // being left clicking an item that does nothing for a reason only the console knows.
                event.setCancelled(true);
                messages.send(player, "items.went-wrong", "item", nameOf(held));
            }
            default -> {
                // DECLINED, UNKNOWN, WRONG_TRIGGER: the click was not consumed, so vanilla behaviour
                // stands. A hook that hit the sky costs nothing and looks like a miss, which is the point
                // of ItemAbility.attempts.
            }
        }
    }

    /** The ability this stack performs, if it is a custom item and its plugin has registered one. */
    private Optional<ItemAbility> abilityOf(ItemStack stack) {
        return factory.keyOf(stack)
                .flatMap(items::byKey)
                .flatMap(CustomItem::abilityInFull)
                .flatMap(abilities::byKey);
    }

    /**
     * Takes one out of the stack the player is holding.
     *
     * <p>The stack itself rather than a search of the inventory: it is what was clicked, so it is what
     * should be spent. Searching would let a click on the medikit in your hand consume the one in your
     * backpack, which is the kind of thing that only shows up when somebody is counting.
     */
    private void takeOne(Player player, ItemStack held) {
        if (held == null) {
            return;
        }
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    /** What to call the item in a sentence: its own name if it has one, otherwise its id. */
    private String nameOf(ItemStack stack) {
        return factory.keyOf(stack)
                .flatMap(items::byKey)
                .map(CustomItem::nameOrId)
                .orElse("that");
    }
}
