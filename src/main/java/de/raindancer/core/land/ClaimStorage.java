package de.raindancer.core.land;

import de.raindancer.core.data.nbt.ItemText;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

/**
 * One YAML file per claim under {@code plugins/RainsExtendedClaims/claims/}.
 * <p>
 * A file per claim keeps saves cheap (only dirty claims are written), makes manual admin surgery
 * possible and avoids one corrupt entry taking down every claim on the server. Writes go through a
 * temporary file and an atomic move.
 */
public final class ClaimStorage {

    /** Makes every temporary write file unique, so concurrent saves cannot clobber each other. */
    /**
     * The shape of a claim file this version writes.
     * <p>
     * <b>1</b> — up to 1.5.3: one boolean per flag, {@code pantry.feed-visitors} and
     * {@code equipment.equip-visitors} for who a perk served.<br>
     * <b>2</b> — 1.6.0: a value per flag <em>and</em> audience, and a {@code feature-audiences} list per
     * perk.
     * <p>
     * A file without the stamp is version 1 by definition — that is every file written before 1.6.0. The
     * loader reads both shapes either way (it recognises the older keys), so the stamp is not what makes
     * an upgrade work; it is what lets the plugin <em>say</em> that it upgraded something, and what a
     * future format change will branch on instead of guessing from which keys happen to be present.
     */
    public static final int DATA_VERSION = 2;

    private static final java.util.concurrent.atomic.AtomicLong WRITE_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    private static final LogChannel log = Log.of("land");

    private final Path directory;

    public ClaimStorage(Path dataFolder) {
        this.directory = dataFolder.resolve("claims");
    }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    public List<Claim> loadAll() {
        List<Claim> claims = new ArrayList<>();
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return claims;
        }
        java.util.List<String> unreadable = new java.util.ArrayList<>();
        int upgraded = 0;
        for (File file : files) {
            try {
                Claim claim = load(file);
                if (claim != null) {
                    claims.add(claim);
                    if (claim.dirty()) {
                        upgraded++;
                    }
                }
            } catch (RuntimeException exception) {
                unreadable.add(file.getName());
                log.error(exception, "Could not load claim file " + file.getName()
                        + " — it is being skipped, the file was left untouched.");
            }
        }
        if (!unreadable.isEmpty()) {
            // Loud, and repeated at the end of the load: an unreadable claim file means land that was
            // protected this morning is open to anybody this afternoon. One SEVERE line halfway up a
            // startup log is not enough warning for that.
            log.error("=".repeat(78));
            log.error(unreadable.size() + " claim file(s) could not be read: "
                    + String.join(", ", unreadable));
            log.error("THAT LAND IS CURRENTLY UNPROTECTED. The files were left untouched — restore "
                    + "them from a backup, or delete them deliberately once you have looked at them.");
            log.error("=".repeat(78));
        }
        if (upgraded > 0) {
            log.info(upgraded + " claim file(s) were written by an older version; they have been read"
                    + " in full and will be rewritten in the current format on the next save.");
        }
        return claims;
    }

    private Claim load(File file) {
        return fromYaml(YamlConfiguration.loadConfiguration(file), file.getName());
    }

    /**
     * Builds a claim from an already-parsed file, whichever version of the plugin wrote it.
     * <p>
     * Separate from the file handling so the format — including every older shape of it — can be tested
     * without a server or a disk. See {@code LegacyClaimFormatTest}.
     *
     * @param label how to name the file in a warning; the file name in production
     * @return the claim, or {@code null} when the file is too broken to be one
     */
    public static Claim fromYaml(YamlConfiguration yaml, String label) {
        String idRaw = yaml.getString("id");
        String worldIdRaw = yaml.getString("world-id");
        if (idRaw == null || worldIdRaw == null) {
            log.warn("Claim file " + label + " has no id or world — skipping.");
            return null;
        }
        int fileVersion = yaml.getInt("data-version", 1);
        UUID id = UUID.fromString(idRaw);
        UUID worldId = UUID.fromString(worldIdRaw);
        String worldName = yaml.getString("world-name", "unknown");
        String name = yaml.getString("name", "claim-" + idRaw.substring(0, 8));

        List<ClaimPoint> vertices = new ArrayList<>();
        for (String raw : yaml.getStringList("shape.vertices")) {
            vertices.add(ClaimPoint.deserialize(raw));
        }
        if (vertices.size() < 3) {
            log.warn("Claim " + name + " has a degenerate shape (" + vertices.size()
                    + " vertices) — skipping.");
            return null;
        }
        ClaimShape shape = new ClaimShape(vertices, yaml.getInt("shape.min-y"), yaml.getInt("shape.max-y"));

        List<String> ownerList = yaml.getStringList("owners");
        UUID firstOwner = ownerList.isEmpty() ? null : UUID.fromString(ownerList.get(0));
        Claim claim = new Claim(id, name, worldId, worldName, shape, firstOwner);
        for (int index = 1; index < ownerList.size(); index++) {
            claim.addOwner(UUID.fromString(ownerList.get(index)));
        }
        claim.createdAt(yaml.getLong("created-at", System.currentTimeMillis()));
        // Newer files store the whole item so a potion keeps its brew; older ones held a material name.
        ItemStack icon = ItemText.decode(yaml.getString("icon-item"));
        if (icon == null) {
            String iconRaw = yaml.getString("icon");
            if (iconRaw != null) {
                Material material = Material.matchMaterial(iconRaw);
                icon = material == null ? null : new ItemStack(material);
            }
        }
        claim.icon(icon);

        claim.publicPermissions().clear();
        for (String raw : yaml.getStringList("public-permissions")) {
            ClaimPermission.byKey(raw).ifPresent(claim.publicPermissions()::add);
        }

        ConfigurationSection membersSection = yaml.getConfigurationSection("members");
        if (membersSection != null) {
            for (String key : membersSection.getKeys(false)) {
                ConfigurationSection memberSection = membersSection.getConfigurationSection(key);
                if (memberSection == null) {
                    continue;
                }
                EnumSet<ClaimPermission> permissions = EnumSet.noneOf(ClaimPermission.class);
                for (String raw : memberSection.getStringList("permissions")) {
                    ClaimPermission.byKey(raw).ifPresent(permissions::add);
                }
                EnumSet<ClaimAdminPermission> adminPermissions = EnumSet.noneOf(ClaimAdminPermission.class);
                for (String raw : memberSection.getStringList("admin-permissions")) {
                    ClaimAdminPermission.byKey(raw).ifPresent(adminPermissions::add);
                }
                EnumSet<ClaimPermission> grantable = EnumSet.noneOf(ClaimPermission.class);
                for (String raw : memberSection.getStringList("grantable")) {
                    ClaimPermission.byKey(raw).ifPresent(grantable::add);
                }
                claim.putMember(new ClaimMember(UUID.fromString(key), permissions, adminPermissions, grantable,
                        memberSection.getLong("added-at", System.currentTimeMillis())));
            }
        }

        ConfigurationSection bansSection = yaml.getConfigurationSection("bans");
        if (bansSection != null) {
            Map<UUID, ClaimBan> bans = new HashMap<>();
            for (String key : bansSection.getKeys(false)) {
                ConfigurationSection banSection = bansSection.getConfigurationSection(key);
                if (banSection == null) {
                    continue;
                }
                UUID banned = UUID.fromString(key);
                String issuedByRaw = banSection.getString("issued-by");
                bans.put(banned, new ClaimBan(banned,
                        issuedByRaw == null ? null : UUID.fromString(issuedByRaw),
                        banSection.getLong("issued-at"),
                        banSection.getLong("expires-at"),
                        banSection.getString("reason", "")));
            }
            claim.restoreBans(bans);
        }

        loadFlags(claim, yaml.getConfigurationSection("flags"), fileVersion);
        loadFeatureAudiences(claim, yaml);

        loadTitles(claim.titles(), yaml.getConfigurationSection("titles"));
        loadEntryFee(claim.entryFee(), yaml.getConfigurationSection("entry-fee"));

        claim.bank().restore(ItemText.decodeAll(yaml.getStringList("bank.items")), yaml.getInt("bank.experience"));

        ConfigurationSection fenceSection = yaml.getConfigurationSection("fence");
        if (fenceSection != null) {
            claim.fence().enabled(fenceSection.getBoolean("enabled", false));
            Material fenceMaterial = Material.matchMaterial(
                    fenceSection.getString("material", Material.OAK_FENCE.name()));
            if (fenceMaterial != null) {
                claim.fence().material(fenceMaterial);
            }
            Map<ClaimPoint, FenceSegment> segments = new HashMap<>();
            ConfigurationSection segmentSection = fenceSection.getConfigurationSection("segments");
            if (segmentSection != null) {
                for (String key : segmentSection.getKeys(false)) {
                    // Keys are "x,z" with the comma replaced, because YAML paths split on dots only.
                    FenceSegment segment = FenceSegment.deserialize(segmentSection.getString(key, ""));
                    if (segment != null) {
                        segments.put(decodePoint(key), segment);
                    }
                }
            }
            Set<ClaimPoint> suppressed = new HashSet<>();
            for (String raw : fenceSection.getStringList("suppressed")) {
                suppressed.add(ClaimPoint.deserialize(raw));
            }
            claim.fence().restore(segments, suppressed);
        }

        Map<org.bukkit.potion.PotionEffectType, ClaimEffect> effects = new HashMap<>();
        for (String raw : yaml.getStringList("effects")) {
            ClaimEffect.deserialize(raw).ifPresent(effect -> effects.put(effect.type(), effect));
        }
        claim.restoreEffects(effects);
        claim.effectsEnabled(yaml.getBoolean("effects-enabled", true));

        ConfigurationSection pantrySection = yaml.getConfigurationSection("pantry");
        if (pantrySection != null) {
            claim.pantry().enabled(pantrySection.getBoolean("enabled", false));
            claim.pantry().threshold(pantrySection.getInt("threshold", 16));
            claim.pantry().allowDeposits(pantrySection.getBoolean("allow-deposits", true));
            claim.pantry().restore(ItemText.decodeAll(pantrySection.getStringList("items")));
        }

        claim.potionStore().restore(ItemText.decodeAll(yaml.getStringList("potion-store")));
        // Resume the potion that was burning, so a restart does not throw away a partly used one.
        claim.potionStore().restoreActive(ItemText.decode(yaml.getString("potion-active.item")),
                yaml.getLong("potion-active.until"));

        ConfigurationSection equipSection = yaml.getConfigurationSection("equipment");
        if (equipSection != null) {
            claim.equipment().enabled(equipSection.getBoolean("enabled", false));
            claim.equipment().restoreStock(ItemText.decodeAll(equipSection.getStringList("stock")));

            List<EquipRule> rules = new ArrayList<>();
            ConfigurationSection ruleSection = equipSection.getConfigurationSection("rules");
            if (ruleSection != null) {
                for (String key : ruleSection.getKeys(false)) {
                    ItemStack template = ItemText.decode(ruleSection.getString(key + ".item"));
                    if (template == null) {
                        continue;
                    }
                    EquipRule.Target target = EquipRule.Target
                            .byKey(ruleSection.getString(key + ".target", "auto"))
                            .orElse(EquipRule.Target.AUTO);
                    rules.add(new EquipRule(template, target,
                            ruleSection.getInt(key + ".hotbar", 0),
                            ruleSection.getInt(key + ".keep", 1)));
                }
            }
            claim.equipment().restoreRules(rules);
        }

        ConfigurationSection atmosphereSection = yaml.getConfigurationSection("atmosphere");
        if (atmosphereSection != null) {
            ClaimAtmosphere.WeatherMode.byKey(atmosphereSection.getString("weather", "inherit"))
                    .ifPresent(claim.atmosphere()::weather);
            ClaimAtmosphere.TimePreset.byKey(atmosphereSection.getString("time-preset", "inherit"))
                    .ifPresent(claim.atmosphere()::timePreset);
            int ticks = atmosphereSection.getInt("time-ticks", -1);
            if (ticks >= 0) {
                claim.atmosphere().customTicks(ticks);
            }
        }

        CostType paidType = CostType.byKey(yaml.getString("paid.type", "none")).orElse(CostType.NONE);
        if (paidType != CostType.NONE) {
            int baseAmount = yaml.getInt("paid.amount");
            claim.restorePayment(paidType,
                    baseAmount,
                    yaml.getLong("paid.area", shape.areaBlocks()),
                    // Files written before the baseline/invested split had only one figure.
                    yaml.getInt("paid.settled", baseAmount),
                    ItemText.decode(yaml.getString("paid.item")));
        }

        // A file older than the current format has just been read through the legacy branches above; the
        // in-memory claim is already the new shape. Marking it dirty is what actually rewrites it on disk,
        // at the next autosave, so the upgrade happens once rather than being re-derived on every start.
        if (fileVersion < DATA_VERSION) {
            claim.markDirty();
        } else {
            claim.clearDirty();
        }
        return claim;
    }

    private static void loadTitles(ClaimTitles titles, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        titles.enterTitle(loadStyled(section.getConfigurationSection("enter.title")));
        titles.enterSubtitle(loadStyled(section.getConfigurationSection("enter.subtitle")));
        titles.leaveTitle(loadStyled(section.getConfigurationSection("leave.title")));
        titles.leaveSubtitle(loadStyled(section.getConfigurationSection("leave.subtitle")));
        titles.fadeInTicks(section.getInt("fade-in", 10));
        titles.stayTicks(section.getInt("stay", 40));
        titles.fadeOutTicks(section.getInt("fade-out", 10));
    }

    private static StyledText loadStyled(ConfigurationSection section) {
        if (section == null) {
            return StyledText.empty();
        }
        NamedTextColor color = StyledText.colorByName(section.getString("color", "white"))
                .orElse(NamedTextColor.WHITE);
        return new StyledText(section.getString("text", ""), color,
                section.getBoolean("bold", false),
                section.getBoolean("italic", false),
                section.getBoolean("underlined", false),
                section.getBoolean("strikethrough", false),
                section.getBoolean("obfuscated", false));
    }

    private static void loadEntryFee(EntryFee fee, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        fee.enabled(section.getBoolean("enabled", false));
        fee.type(CostType.byKey(section.getString("type", "none")).orElse(CostType.NONE));
        fee.amount(section.getInt("amount", 1));
        fee.item(ItemText.decode(section.getString("item")));
        fee.passDurationSeconds(section.getInt("pass-seconds", 300));
    }

    public void save(Claim claim) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("data-version", DATA_VERSION);
        yaml.set("id", claim.id().toString());
        yaml.set("name", claim.name());
        yaml.set("world-id", claim.worldId().toString());
        yaml.set("world-name", claim.worldName());
        yaml.set("created-at", claim.createdAt());
        yaml.set("icon-item", ItemText.encode(claim.icon()));

        List<String> vertices = new ArrayList<>();
        for (ClaimPoint point : claim.shape().vertices()) {
            vertices.add(point.serialize());
        }
        yaml.set("shape.vertices", vertices);
        yaml.set("shape.min-y", claim.shape().minY());
        yaml.set("shape.max-y", claim.shape().maxY());

        List<String> owners = new ArrayList<>();
        claim.owners().forEach(owner -> owners.add(owner.toString()));
        yaml.set("owners", owners);

        List<String> publicPermissions = new ArrayList<>();
        claim.publicPermissions().forEach(permission -> publicPermissions.add(permission.key()));
        yaml.set("public-permissions", publicPermissions);

        for (ClaimMember member : claim.members().values()) {
            String base = "members." + member.uuid();
            yaml.set(base + ".permissions", keys(member.permissions()));
            List<String> adminPermissions = new ArrayList<>();
            member.adminPermissions().forEach(permission -> adminPermissions.add(permission.key()));
            yaml.set(base + ".admin-permissions", adminPermissions);
            yaml.set(base + ".grantable", keys(member.grantablePermissions()));
            yaml.set(base + ".added-at", member.addedAt());
        }

        for (ClaimBan ban : claim.bans().values()) {
            String base = "bans." + ban.uuid();
            yaml.set(base + ".issued-by", ban.issuedBy() == null ? null : ban.issuedBy().toString());
            yaml.set(base + ".issued-at", ban.issuedAt());
            yaml.set(base + ".expires-at", ban.expiresAt());
            yaml.set(base + ".reason", ban.reason());
        }

        saveFlags(claim, yaml);
        saveFeatureAudiences(claim, yaml);

        ClaimTitles titles = claim.titles();
        saveStyled(yaml, "titles.enter.title", titles.enterTitle());
        saveStyled(yaml, "titles.enter.subtitle", titles.enterSubtitle());
        saveStyled(yaml, "titles.leave.title", titles.leaveTitle());
        saveStyled(yaml, "titles.leave.subtitle", titles.leaveSubtitle());
        yaml.set("titles.fade-in", titles.fadeInTicks());
        yaml.set("titles.stay", titles.stayTicks());
        yaml.set("titles.fade-out", titles.fadeOutTicks());

        EntryFee fee = claim.entryFee();
        yaml.set("entry-fee.enabled", fee.rawEnabled());
        yaml.set("entry-fee.type", fee.type().key());
        yaml.set("entry-fee.amount", fee.amount());
        yaml.set("entry-fee.item", ItemText.encode(fee.item()));
        yaml.set("entry-fee.pass-seconds", fee.passDurationSeconds());

        yaml.set("bank.items", ItemText.encodeAll(claim.bank().items()));
        yaml.set("bank.experience", claim.bank().experiencePoints());

        yaml.set("fence.enabled", claim.fence().enabled());
        yaml.set("fence.material", claim.fence().material().name());
        for (Map.Entry<ClaimPoint, FenceSegment> entry : claim.fence().segments().entrySet()) {
            yaml.set("fence.segments." + encodePoint(entry.getKey()), entry.getValue().serialize());
        }
        List<String> suppressed = new ArrayList<>();
        claim.fence().suppressed().forEach(point -> suppressed.add(point.serialize()));
        yaml.set("fence.suppressed", suppressed);

        List<String> effectList = new ArrayList<>();
        claim.effects().values().forEach(effect -> effectList.add(effect.serialize()));
        yaml.set("effects", effectList);
        yaml.set("effects-enabled", claim.effectsEnabled());

        yaml.set("pantry.enabled", claim.pantry().enabled());
        yaml.set("pantry.threshold", claim.pantry().threshold());
        yaml.set("pantry.allow-deposits", claim.pantry().allowDeposits());
        yaml.set("pantry.items", ItemText.encodeAll(claim.pantry().items()));

        yaml.set("equipment.enabled", claim.equipment().enabled());
        yaml.set("equipment.stock", ItemText.encodeAll(claim.equipment().stock()));
        List<EquipRule> equipRules = claim.equipment().rules();
        for (int index = 0; index < equipRules.size(); index++) {
            EquipRule rule = equipRules.get(index);
            String base = "equipment.rules." + index;
            yaml.set(base + ".item", ItemText.encode(rule.template()));
            yaml.set(base + ".target", rule.target().key());
            yaml.set(base + ".hotbar", rule.hotbarSlot());
            yaml.set(base + ".keep", rule.keepAmount());
        }

        yaml.set("potion-store", ItemText.encodeAll(claim.potionStore().potions()));
        yaml.set("potion-active.item", ItemText.encode(claim.potionStore().activeBrew()));
        yaml.set("potion-active.until", claim.potionStore().activeUntil());
        yaml.set("atmosphere.weather", claim.atmosphere().weather().key());
        yaml.set("atmosphere.time-preset", claim.atmosphere().timePreset().key());
        yaml.set("atmosphere.time-ticks", claim.atmosphere().customTicks());

        // What was actually paid, so a later resize can be settled proportionally.
        yaml.set("paid.type", claim.paidCostType().key());
        yaml.set("paid.amount", claim.paidAmount());
        yaml.set("paid.area", claim.paidArea());
        yaml.set("paid.settled", claim.settledAmount());
        yaml.set("paid.item", ItemText.encode(claim.paidItem()));

        writeAtomically(yaml, claim.id());
    }

    // ------------------------------------------------------------ flags

    /**
     * Reads the flag overrides, accepting both the per-audience form and the single boolean written before
     * audiences existed.
     * <p>
     * In a file older than {@link #DATA_VERSION} a bare boolean is spread over all three audiences, except
     * for the groups the flag used to exempt in code — see {@link ClaimFlag#legacyExemptAudiences()}. That
     * keeps an upgraded claim behaving exactly as its owner left it instead of, say, locking them out of a
     * home they had closed to teleports. In a current file the same bare boolean is simply the compact
     * form for "all three agree", and is taken at face value.
     */
    public static void loadFlags(Claim claim, ConfigurationSection section, int fileVersion) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            java.util.Optional<ClaimFlag> parsed = ClaimFlag.byKey(key);
            if (parsed.isEmpty()) {
                continue;
            }
            ClaimFlag flag = parsed.get();
            ConfigurationSection perAudience = section.getConfigurationSection(key);
            if (perAudience == null) {
                // A single value means two different things depending on who wrote it. Before audiences
                // existed it was "this flag, for everybody, with the exemptions the listener applied in
                // code". Since then it is the compact form saveFlags uses when all three groups happen to
                // agree — and there the exemptions must NOT be re-applied, or an owner who deliberately
                // denied teleporting in to everybody, themselves included, would find it back on after
                // every restart. The file's own version is what tells the two apart.
                boolean value = section.getBoolean(key);
                boolean legacy = fileVersion < DATA_VERSION;
                for (ClaimAudience audience : ClaimAudience.values()) {
                    claim.setFlagOverride(flag, audience, legacy
                            ? value || flag.legacyExemptAudiences().contains(audience)
                            : value);
                }
                continue;
            }
            for (ClaimAudience audience : ClaimAudience.values()) {
                if (perAudience.isBoolean(audience.key())) {
                    claim.setFlagOverride(flag, audience, perAudience.getBoolean(audience.key()));
                }
            }
        }
    }

    /**
     * Writes the flag overrides, collapsing a flag that reads the same for everybody back into the compact
     * boolean form — which is also what an older build of the plugin would still understand.
     */
    public static void saveFlags(Claim claim, ConfigurationSection yaml) {
        claim.flagOverrides().forEach((flag, values) -> {
            String base = "flags." + flag.key();
            if (values.size() == ClaimAudience.values().length
                    && values.values().stream().distinct().count() == 1L) {
                yaml.set(base, values.values().iterator().next());
                return;
            }
            values.forEach((audience, value) -> yaml.set(base + "." + audience.key(), value));
        });
    }

    // ------------------------------------------------------------ who a feature serves

    /**
     * Reads which groups each audience-aware feature serves.
     * <p>
     * Before this existed the pantry and auto-equip each had a single "…visitors too" boolean, which is
     * the same statement with only two of the three groups spelled out; those are translated rather than
     * dropped, so an owner who had switched visitors off keeps them switched off.
     */
    public static void loadFeatureAudiences(Claim claim, ConfigurationSection yaml) {
        Map<ClaimFeature, EnumSet<ClaimAudience>> served = new HashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("feature-audiences");
        if (section == null) {
            // No section at all means a file written before audiences existed. Back then the pantry and
            // auto-equip each carried a single "…visitors too" boolean, which is the same statement with
            // two of the three groups lumped together; translate it rather than dropping it.
            if (!yaml.getBoolean("pantry.feed-visitors", true)) {
                served.put(ClaimFeature.PANTRY, EnumSet.of(ClaimAudience.OWNER, ClaimAudience.TRUSTED));
            }
            if (!yaml.getBoolean("equipment.equip-visitors", false)) {
                served.put(ClaimFeature.AUTO_EQUIP, EnumSet.of(ClaimAudience.OWNER, ClaimAudience.TRUSTED));
            }
            claim.restoreFeatureAudiences(served);
            return;
        }
        for (String key : section.getKeys(false)) {
            java.util.Optional<ClaimFeature> feature = ClaimFeature.byKey(key);
            if (feature.isEmpty()) {
                continue;
            }
            EnumSet<ClaimAudience> audiences = EnumSet.noneOf(ClaimAudience.class);
            for (String raw : section.getStringList(key)) {
                ClaimAudience.byKey(raw).ifPresent(audiences::add);
            }
            served.put(feature.get(), audiences);
        }
        claim.restoreFeatureAudiences(served);
    }

    /**
     * Writes the groups every audience-aware feature serves, narrowed or not.
     * <p>
     * Spelled out in full rather than only when it differs from the default, because the presence of the
     * section is what tells the loader this file is new enough to be believed. Left out, a claim that
     * deliberately kits out visitors would be read back as one that does not — the old
     * {@code equip-visitors} key it would fall back to defaulted to off.
     */
    public static void saveFeatureAudiences(Claim claim, ConfigurationSection yaml) {
        for (ClaimFeature feature : ClaimFeature.values()) {
            if (!feature.audienceAware()) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            claim.featureAudiences(feature).forEach(audience -> keys.add(audience.key()));
            yaml.set("feature-audiences." + feature.key(), keys);
        }
    }

    private static List<String> keys(EnumSet<ClaimPermission> permissions) {
        List<String> keys = new ArrayList<>();
        permissions.forEach(permission -> keys.add(permission.key()));
        return keys;
    }

    private void saveStyled(YamlConfiguration yaml, String path, StyledText text) {
        if (text == null || text.isBlank()) {
            yaml.set(path, null);
            return;
        }
        yaml.set(path + ".text", text.raw());
        yaml.set(path + ".color", text.colorKey());
        yaml.set(path + ".bold", text.bold());
        yaml.set(path + ".italic", text.italic());
        yaml.set(path + ".underlined", text.underlined());
        yaml.set(path + ".strikethrough", text.strikethrough());
        yaml.set(path + ".obfuscated", text.obfuscated());
    }

    /**
     * Encodes a point as a YAML-path-safe key.
     * <p>
     * Bukkit splits configuration paths on {@code .} and negative coordinates would otherwise collide with
     * the separator, so the pair is written as {@code x_z} with {@code n} marking a negative number.
     */
    private static String encodePoint(ClaimPoint point) {
        return coordinate(point.x()) + "_" + coordinate(point.z());
    }

    private static String coordinate(int value) {
        return value < 0 ? "n" + (-value) : String.valueOf(value);
    }

    private static ClaimPoint decodePoint(String key) {
        String[] parts = key.split("_");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed fence segment key: " + key);
        }
        return new ClaimPoint(parseCoordinate(parts[0]), parseCoordinate(parts[1]));
    }

    private static int parseCoordinate(String raw) {
        return raw.startsWith("n") ? -Integer.parseInt(raw.substring(1)) : Integer.parseInt(raw);
    }

    private void writeAtomically(YamlConfiguration yaml, UUID id) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(id + ".yml");
        // The temporary name must be unique per write. Two saves of the same claim can overlap — an
        // auto-save and an edit, say — and with a shared name the first one to finish moves the file
        // away while the second is still writing to it, which then dies with NoSuchFileException and
        // loses that save entirely.
        Path temporary = directory.resolve(id + ".yml." + WRITE_COUNTER.incrementAndGet() + ".tmp");
        Files.writeString(temporary, yaml.saveToString());
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // A failed move must not leave the scratch file behind.
            Files.deleteIfExists(temporary);
        }
    }

    public void delete(UUID claimId) throws IOException {
        Files.deleteIfExists(directory.resolve(claimId + ".yml"));
    }

    public Path directory() {
        return directory;
    }
}
