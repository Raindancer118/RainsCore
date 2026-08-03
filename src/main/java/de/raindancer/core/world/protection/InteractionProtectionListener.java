package de.raindancer.core.world.protection;

import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Optional;

/** Protects interaction: containers, doors, redstone, entities and PvP. */
public final class InteractionProtectionListener implements Listener {

    private final Land land;

    /** Throttles every flag refusal this listener sends, per player — see refuse. */
    private final java.util.Map<java.util.UUID, Long> lastPotionRefusal =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final de.raindancer.core.ui.messages.Messages messages;

    public InteractionProtectionListener(Land land,
                                         de.raindancer.core.ui.messages.Messages messages) {
        this.land = land;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Both hands. Refusing only the main hand let a player carry the thing they wanted to use in the
        // off-hand and open any chest on the server — the event fires once per hand and only one was
        // being checked.
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        LandAction required = InteractionClassifier.forBlock(block);
        if (required == null) {
            return;
        }
        if (!land.allow(event.getPlayer(), block.getLocation(), required)) {
            event.setCancelled(true);
            return;
        }

        // The flag, asked here rather than only on BlockRedstoneEvent. That event carries no player, so there
        // was nobody to bypass on behalf of: an admin with the bypass on flipped a lever in a claim with
        // redstone switched off and the circuit stayed dead, because the current was held at its old value
        // whatever the click had been allowed to do.
        //
        // Refusing the click instead means nothing flips, so no world event follows and there is nothing left
        // to judge without an actor. It also reads better for everybody else: a lever that will not move is
        // clearer than one that moves and does nothing.
        if (required == LandAction.REDSTONE
                && land.landFlags().isEnforced(LandFlag.REDSTONE)
                && !land.landFlags().isAllowedForTracked(
                        land.areaAround(event.getPlayer()).orElse(null),
                        block.getLocation(), LandFlag.REDSTONE, event.getPlayer())) {
            event.setCancelled(true);
            land.areaAt(block.getLocation())
                    .ifPresent(area -> refuse(event.getPlayer(), area, "land.redstone-refused"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!land.allow(event.getPlayer(), event.getBed().getLocation(), LandAction.BEDS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlowerPot(PlayerFlowerPotManipulateEvent event) {
        if (!land.allow(event.getPlayer(), event.getFlowerpot().getLocation(), LandAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (!land.allow(event.getPlayer(), event.getLectern().getLocation(), LandAction.CONTAINERS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        // Both hands, for the same reason as onInteract: carrying the thing in the off-hand was a way
        // through every protection this listener applies.
        // The flag first, and asked about every creature rather than only the ones with a permission of their
        // own: the classifier says nothing about a snow golem or a tamed parrot, and "do not touch the animals"
        // plainly means those too.
        if (!mayInteractWith(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        LandAction required = InteractionClassifier.forEntityInteract(event.getRightClicked());
        if (required == null) {
            return;
        }
        if (!land.allow(event.getPlayer(), event.getRightClicked().getLocation(), required)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (!mayInteractWith(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        LandAction required = InteractionClassifier.forEntityInteract(event.getRightClicked());
        if (required == null) {
            return;
        }
        if (!land.allow(event.getPlayer(), event.getRightClicked().getLocation(), required)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!land.allow(event.getPlayer(), event.getRightClicked().getLocation(),
                LandAction.ITEM_FRAMES)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!mayInteractWith(event.getPlayer(), event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (!land.allow(event.getPlayer(), event.getEntity().getLocation(), LandAction.ANIMALS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player)) {
            return;
        }
        if (!land.allow(player, event.getVehicle().getLocation(), LandAction.VEHICLES)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player attacker = playerBehind(event.getAttacker());
        if (attacker == null) {
            return;
        }
        if (!land.allow(attacker, event.getVehicle().getLocation(), LandAction.VEHICLES)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        // Silent, like the permission below it. A player standing over a pile they may not take produces one of
        // these every tick, and a line per tick is worse than the pile staying put.
        if (land.landFlags().isEnforced(LandFlag.ITEM_PICKUP)
                && !land.landFlags().isAllowedAt(item.getLocation(), LandFlag.ITEM_PICKUP,
                        player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!land.allowSilently(player, item.getLocation(), LandAction.ITEM_PICKUP)) {
            event.setCancelled(true);
        }
    }

    /**
     * Experience orbs, which no permission covered.
     *
     * <p>Orbs are not items, so a claim that stopped people taking the drops still handed them the levels — the
     * half of the same complaint that had no answer. Silent for the same reason as the items above: an orb
     * hovering by a player fires this repeatedly.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExperiencePickup(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.XP_PICKUP)) {
            return;
        }
        Player player = event.getPlayer();
        if (!land.landFlags().isAllowedAt(event.getExperienceOrb().getLocation(), LandFlag.XP_PICKUP,
                player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerBehind(event.getDamager());
        if (attacker == null) {
            return;
        }
        Entity victim = event.getEntity();

        if (victim instanceof Player hurt) {
            if (!pvpAllowed(attacker, hurt, true)) {
                event.setCancelled(true);
            }
            return;
        }

        // The flag before the permission. A claim that has switched hitting off has switched it off for a whole
        // tier, whatever any individual grant says — and a monster is nobody's protected livestock, so without
        // this half there was no way to stop a stranger fighting in a spawn at all.
        if (!mayHarm(attacker, victim)) {
            event.setCancelled(true);
            return;
        }

        LandAction required = InteractionClassifier.forEntityDamage(victim);
        if (required == null) {
            return;
        }
        if (!land.allow(attacker, victim.getLocation(), required)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether this player may harm this creature here, by the ground's own rule.
     *
     * <p>Judged where the creature is and at the <em>attacker's</em> tier, which is the difference between this
     * and every other audience-aware flag: PvP protects the person being hit, so the victim's standing decides;
     * this restrains the person swinging, so theirs does. An owner keeps their own farm and their own arena.
     *
     * <p>Answers true for anything that is not a creature — item frames, boats, a player — so the permission
     * checks and the PvP rule keep those.
     */
    private boolean mayHarm(Player attacker, Entity victim) {
        LandFlag flag = InteractionClassifier.forCreatureDamage(victim);
        if (flag == null || !land.landFlags().isEnforced(flag)) {
            return true;
        }
        // The bypass is applied inside isAllowedAt, on behalf of the attacker.
        if (land.landFlags().isAllowedAt(victim.getLocation(), flag, attacker.getUniqueId())) {
            return true;
        }
        land.areaAt(victim.getLocation()).ifPresent(area -> refuse(attacker, area,
                flag == LandFlag.HIT_MONSTERS ? "land.hit-monsters-refused" : "land.hit-mobs-refused"));
        return false;
    }

    /**
     * Whether this player may lay hands on this creature without hurting it.
     *
     * <p>Shearing, milking, saddling, dyeing, breeding by hand. None of it is damage, so {@link #mayHarm} never
     * sees it — a flock can be sheared bare in a claim with every damage rule off.
     */
    private boolean mayInteractWith(Player player, Entity target) {
        if (target instanceof Player || !(target instanceof LivingEntity)) {
            return true;
        }
        if (!land.landFlags().isEnforced(LandFlag.INTERACT_MOBS)) {
            return true;
        }
        if (land.landFlags().isAllowedAt(target.getLocation(), LandFlag.INTERACT_MOBS,
                player.getUniqueId())) {
            return true;
        }
        land.areaAt(target.getLocation())
                .ifPresent(area -> refuse(player, area, "land.interact-mobs-refused"));
        return false;
    }

    /**
     * Harmful splash and lingering potions.
     * <p>
     * These never produce a damage event with the thrower as the damager, so without their own handler a
     * bottle of Harming walks straight through a claim with PvP switched off — as does Poison, Wither and
     * anything else that hurts. Only the affected players are dropped from the cloud, so a potion thrown
     * across a border still works on whoever is legitimately in range.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        Player thrower = playerBehind(event.getPotion());
        if (thrower == null || !isHarmful(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity affected : new java.util.ArrayList<>(event.getAffectedEntities())) {
            if (!mayBeSplashed(thrower, affected)) {
                event.setIntensity(affected, 0.0D);
            }
        }
    }

    /**
     * Whether this thrower may hit this creature with something harmful.
     *
     * <p>Players go through the PvP rule. <b>Everything else goes through the claim's own protection</b>, which
     * is the half that was missing: a splash of Harming II over somebody's fence killed every cow, sheep,
     * villager and tamed wolf inside, because potion damage produces no damage event naming the thrower and
     * nothing else was checking. Found by review, not by a crash — it simply worked.
     */
    private boolean mayBeSplashed(Player thrower, LivingEntity affected) {
        // The POTIONS flag first: where it is off, nothing lands there at all, harmful or not.
        if (land.landFlags().isEnforced(LandFlag.POTIONS)
                && !land.landFlags().isAllowedAt(affected.getLocation(), LandFlag.POTIONS,
                        affected instanceof Player hit ? hit.getUniqueId() : null)) {
            return false;
        }
        if (affected instanceof Player hurt) {
            return pvpAllowed(thrower, hurt, false);
        }
        // Harm is harm however it arrives: a flag that stops somebody swinging at the cows has to stop them
        // lobbing Harming II over the fence at them, which is the shape of bug this whole method exists for.
        LandFlag flag = InteractionClassifier.forCreatureDamage(affected);
        if (flag != null && land.landFlags().isEnforced(flag)
                && !land.landFlags().isAllowedAt(affected.getLocation(), flag, thrower.getUniqueId())) {
            return false;
        }
        return land.can(thrower, affected.getLocation(), LandAction.DAMAGE_ANIMALS);
    }

    /**
     * Drinking one.
     *
     * <p>The half of the potion flag people forget to ask for and then want: an arena where fighters bring what
     * they brought, a duel that is not decided by whoever stocked more Strength. Refused where the flag says so,
     * for the tier the drinker falls into — so an owner testing their own arena is not stopped by their own
     * rule unless they meant to be.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.POTIONS)) {
            return;
        }
        org.bukkit.Material drinking = event.getItem().getType();
        if (drinking != org.bukkit.Material.POTION) {
            return;   // milk, food and everything else is not this flag's business
        }
        Player player = event.getPlayer();
        if (land.isBypassing(player) || potionsAllowedFor(player, player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        refusePotions(player);
    }

    /**
     * Throwing one, whoever it would have hit.
     *
     * <p>Checked where it <em>lands</em> rather than where it was thrown, because the point of the flag is that
     * nothing arrives — a potion lobbed over a wall from outside is exactly the case an owner is switching it
     * off for.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionThrown(ProjectileLaunchEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.POTIONS)) {
            return;
        }
        if (!(event.getEntity() instanceof ThrownPotion potion)) {
            return;
        }
        Player thrower = playerBehind(potion);
        if (thrower == null || land.isBypassing(thrower)) {
            return;
        }
        // Where it was thrown from. Where it lands is not known yet, and the splash handler below catches
        // the rest — this stops the obvious case early and cheaply.
        if (potionsAllowedFor(thrower, thrower.getLocation())) {
            return;
        }
        event.setCancelled(true);
        refusePotions(thrower);
    }

    /**
     * Tells somebody potions are not allowed here.
     *
     * <p>On the action bar and throttled the same way every other refusal is, because drinking is something a
     * player does repeatedly and holding the key down would otherwise fill their screen.
     */
    private void refusePotions(Player who) {
        land.areaAt(who.getLocation()).ifPresent(area -> refuse(who, area, "land.potions-refused"));
    }

    /**
     * A riptide trident, which is a way over a wall from a standing start.
     *
     * <p>Its own flag rather than the elytra's: an elytra needs height and a run-up, a trident needs rain and a
     * click, so a border that stops one does not stop the other.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRiptide(org.bukkit.event.player.PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        if (!land.landFlags().isEnforced(LandFlag.RIPTIDE) || land.isBypassing(player)) {
            return;
        }
        if (land.landFlags().isAllowedAt(player.getLocation(), LandFlag.RIPTIDE, player.getUniqueId())) {
            return;
        }
        // PlayerRiptideEvent cannot be cancelled — the client has already launched — so the velocity is taken
        // away on the next tick instead. Stopping them dead rather than letting them sail over the wall.
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        land.areaAt(player.getLocation()).ifPresent(area -> refuseRiptide(player, area));
    }

    private void refuseRiptide(Player who, ProtectedArea area) {
        refuse(who, area, "land.riptide-refused");
    }

    /**
     * Leading an animal.
     *
     * <p>Distinct from the ANIMALS permission, which is about touching one. This is about walking one off the
     * property — the trick that empties a pen without breaking a single block, so nothing in the block
     * protection ever sees it happen.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        Player player = event.getPlayer();
        if (!land.landFlags().isEnforced(LandFlag.LEADS) || land.isBypassing(player)) {
            return;
        }
        if (land.landFlags().isAllowedAt(event.getEntity().getLocation(), LandFlag.LEADS,
                player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        land.areaAt(event.getEntity().getLocation())
                .ifPresent(area -> refuse(player, area, "land.leads-refused"));
    }

    /**
     * Putting a boat or a minecart down.
     *
     * <p>Distinct from the VEHICLES permission, which decides who may use one. This decides whether they exist
     * here at all: a spawn where forty abandoned boats have accumulated, or an arena where a boat is a way onto
     * a wall nobody meant to be climbable.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehiclePlaced(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) {
            return;
        }
        if (!isVehicle(event.getItem().getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (!land.landFlags().isEnforced(LandFlag.BOATS) || land.isBypassing(player)) {
            return;
        }
        org.bukkit.Location where = event.getClickedBlock() == null
                ? player.getLocation() : event.getClickedBlock().getLocation();
        if (land.landFlags().isAllowedAt(where, LandFlag.BOATS, player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        land.areaAt(where).ifPresent(area -> refuse(player, area, "land.boats-refused"));
    }

    /** Whether this item puts a vehicle down. Boats, rafts and every kind of minecart. */
    private static boolean isVehicle(org.bukkit.Material material) {
        String name = material.name();
        return name.endsWith("_BOAT") || name.endsWith("_CHEST_BOAT")
                || name.endsWith("_RAFT") || name.endsWith("_CHEST_RAFT")
                || name.equals("MINECART") || name.endsWith("_MINECART");
    }

    /** One throttled line on the action bar. Shared by the flag refusals, which are all the same shape. */
    private void refuse(Player who, ProtectedArea area, String key) {
        long now = System.currentTimeMillis();
        Long last = lastPotionRefusal.get(who.getUniqueId());
        if (last != null && now - last < 1_500L) {
            return;
        }
        lastPotionRefusal.put(who.getUniqueId(), now);
        who.sendActionBar(messages.prefixed(key, "claim", area.name()));
    }

    /** Whether potions are allowed for this person on this ground. */
    private boolean potionsAllowedFor(Player who, org.bukkit.Location where) {
        return land.landFlags().isAllowedAt(where, LandFlag.POTIONS, who.getUniqueId());
    }

    /** The lingering counterpart: the cloud applies over and over, so each application is checked. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLingeringPotion(AreaEffectCloudApplyEvent event) {
        Player thrower = null;
        if (event.getEntity().getSource() instanceof Player player) {
            thrower = player;
        } else if (event.getEntity().getSource() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            thrower = shooter;
        }
        if (thrower == null || !isHarmful(event.getEntity())) {
            return;
        }
        Player source = thrower;
        event.getAffectedEntities().removeIf(affected -> !mayBeSplashed(source, affected));
    }

    /**
     * Whether a cloud is one nobody would want to stand in.
     *
     * <p>Both halves matter. A brewed potion carries its effect as the <em>base type</em> and
     * {@code getCustomEffects()} is empty for it — so checking only the custom list read every stock Lingering
     * Potion of Harming as harmless and let it through.
     */
    private boolean isHarmful(org.bukkit.entity.AreaEffectCloud cloud) {
        if (isHarmful(cloud.getCustomEffects())) {
            return true;
        }
        org.bukkit.potion.PotionType base = cloud.getBasePotionType();
        if (base == null) {
            return false;
        }
        return isHarmful(base.getPotionEffects());
    }

    /** Whether an effect list contains anything a player would not want thrown at them. */
    private boolean isHarmful(java.util.Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (HARMFUL_EFFECTS.contains(effect.getType())) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<PotionEffectType> HARMFUL_EFFECTS = java.util.Set.of(
            PotionEffectType.INSTANT_DAMAGE, PotionEffectType.POISON, PotionEffectType.WITHER,
            PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.MINING_FATIGUE,
            PotionEffectType.BLINDNESS, PotionEffectType.NAUSEA, PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION, PotionEffectType.DARKNESS, PotionEffectType.INFESTED,
            PotionEffectType.OOZING, PotionEffectType.WEAVING, PotionEffectType.WIND_CHARGED);

    /**
     * Whether this attacker may hurt this player, honouring the victim's claim.
     * <p>
     * The victim's claim decides, not the attacker's: the flag protects the people standing in a claim,
     * so where the blow came from does not matter. Which value applies follows the victim's standing in
     * that claim, so an owner can leave PvP on between the people they trust and off for strangers.
     *
     * @param tell whether to explain the refusal — off for potions, where one throw could otherwise
     *             produce a line per person caught in the cloud
     */
    private boolean pvpAllowed(Player attacker, Player victim, boolean tell) {
        if (attacker.equals(victim) || land.isBypassing(attacker)) {
            return true;
        }
        if (!land.landFlags().isEnforced(LandFlag.PVP)) {
            return true;
        }
        Optional<ProtectedArea> claim = land.areaAround(victim);
        if (claim.isEmpty() || land.flags().isAllowedFor(claim.get(), LandFlag.PVP, victim)) {
            return true;
        }
        if (tell) {
            String where = claim.map(ProtectedArea::name).orElse("here");
            attacker.sendMessage(messages.prefixed("land.pvp-refused", "claim", where));
        }
        return false;
    }

    /** Resolves the responsible player behind a direct hit or a projectile. */
    private Player playerBehind(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        // A pet fighting on its owner's behalf is the owner swinging, as far as a claim is concerned.
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player owner) {
            return owner;
        }
        // Lit TNT remembers who lit it, so a charge tossed over a border is still that player's doing.
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player igniter) {
            return igniter;
        }
        return null;
    }
}
