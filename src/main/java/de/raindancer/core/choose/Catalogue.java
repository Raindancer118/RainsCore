package de.raindancer.core.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything a server has, sorted into the drawers people already know.
 *
 * <h2>Why this exists</h2>
 * Because every plugin that lets somebody pick a block builds the same screen, and builds it badly.
 * A thousand-odd materials in enum order is not an order: {@code ACACIA_BOAT} sits between
 * {@code ACACIA_BUTTON} and {@code ACACIA_CHEST_BOAT}, so somebody looking for redstone scrolls past
 * eleven pages of wood. The two usual answers are both wrong — a hand-written shortlist is always
 * missing the block somebody wants, and the full list in enum order is unusable.
 *
 * <h2>Why the materials are injected</h2>
 * {@code Material.isItem()} needs the server's registry, so a catalogue that filtered with it could
 * only ever be tested on a running server. What is worth testing here is the sorting, so the list of
 * names comes in from outside and the sorting is ordinary code.
 *
 * <h2>How the sorting works</h2>
 * By name, in a fixed order of rules, most specific first. It is not perfect and cannot be — the
 * server does not publish its own creative tabs in a form that survives a version change — but it is
 * right for everything anybody actually goes looking for, and anything it is unsure of lands in
 * {@link Category#MISC} rather than vanishing.
 */
public final class Catalogue {

    private final Supplier<List<String>> materials;

    /** Worked out once and kept: this is a thousand strings through a dozen rules. */
    private volatile Map<Category, List<String>> sorted;

    /** @param materials the material names to sort — on a server, every one that is an item */
    public Catalogue(Supplier<List<String>> materials) {
        this.materials = materials;
    }

    /** Everything in one drawer, in alphabetical order. */
    public List<String> itemsIn(Category category) {
        return sorted().getOrDefault(category, List.of());
    }

    /** Everything, in one list, alphabetically. */
    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /** Which drawers actually have anything in them. */
    public List<Category> categories() {
        return List.of(Category.values()).stream()
                .filter(category -> !itemsIn(category).isEmpty())
                .toList();
    }

    /**
     * Everything whose name contains this.
     *
     * <p>Spaces work where underscores are, because nobody types an underscore into a search box, and
     * an exact match comes first — searching for the thing you named should not put four other things
     * above it.
     */
    public List<String> search(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return all().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((String name) ->
                        name.equalsIgnoreCase(wanted) ? 0 : 1).thenComparing(name -> name))
                .toList();
    }

    private Map<Category, List<String>> sorted() {
        Map<Category, List<String>> known = sorted;
        if (known != null) {
            return known;
        }
        Map<Category, List<String>> built = new EnumMap<>(Category.class);
        for (String name : materials.get()) {
            built.computeIfAbsent(categoryOf(name), category -> new ArrayList<>()).add(name);
        }
        built.values().forEach(list -> list.sort(String::compareTo));
        sorted = built;
        return built;
    }

    /** Forgets the sorting — for a server that has just added or removed content. */
    public void refresh() {
        sorted = null;
    }

    // ---------------------------------------------------------------------------- the rules

    /**
     * Which drawer one material belongs in.
     *
     * <p>Order matters throughout: {@code REDSTONE_TORCH} has to be caught by redstone before torch
     * catches it for decoration, and {@code GOLDEN_CARROT} by food before {@code GOLDEN_} suggests
     * anything else. Each rule is here because something landed in the wrong place without it.
     */
    public static Category categoryOf(String material) {
        if (material == null || material.isBlank()) {
            return Category.MISC;
        }
        String name = material.toUpperCase(Locale.ROOT);

        if (isRedstone(name)) {
            return Category.REDSTONE;
        }
        if (isTransport(name)) {
            return Category.TRANSPORTATION;
        }
        if (isBrewing(name)) {
            return Category.BREWING;
        }
        if (isFood(name)) {
            return Category.FOOD;
        }
        if (isCombat(name)) {
            return Category.COMBAT;
        }
        if (isTool(name)) {
            return Category.TOOLS;
        }
        if (isDecoration(name)) {
            return Category.DECORATIONS;
        }
        if (isBuilding(name)) {
            return Category.BUILDING_BLOCKS;
        }
        return Category.MISC;
    }

    private static boolean isRedstone(String name) {
        return name.startsWith("REDSTONE") || name.endsWith("_BUTTON") || name.endsWith("_PLATE")
                || name.contains("PISTON") || name.contains("REPEATER")
                || name.contains("COMPARATOR") || name.contains("OBSERVER")
                || name.contains("DISPENSER") || name.contains("DROPPER")
                || name.contains("HOPPER") || name.contains("LEVER") || name.contains("TRIPWIRE")
                || name.contains("DAYLIGHT_DETECTOR") || name.contains("TARGET")
                || name.contains("SCULK_SENSOR") || name.contains("LECTERN")
                || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_FENCE_GATE") || name.contains("NOTE_BLOCK")
                || name.contains("SLIME_BLOCK") || name.contains("HONEY_BLOCK")
                || name.contains("CRAFTER") || name.contains("COPPER_BULB");
    }

    private static boolean isTransport(String name) {
        return name.contains("MINECART") || name.endsWith("_BOAT") || name.endsWith("_RAFT")
                || name.endsWith("_RAIL") || name.equals("RAIL") || name.equals("ELYTRA")
                || name.equals("SADDLE") || name.contains("HORSE_ARMOR")
                || name.equals("CARROT_ON_A_STICK") || name.equals("WARPED_FUNGUS_ON_A_STICK");
    }

    private static boolean isBrewing(String name) {
        return name.contains("POTION") || name.equals("BREWING_STAND") || name.equals("CAULDRON")
                || name.equals("GLASS_BOTTLE") || name.equals("NETHER_WART")
                || name.equals("BLAZE_POWDER") || name.equals("FERMENTED_SPIDER_EYE")
                || name.equals("GLISTERING_MELON_SLICE") || name.equals("MAGMA_CREAM")
                || name.equals("GHAST_TEAR") || name.equals("DRAGON_BREATH")
                || name.equals("PHANTOM_MEMBRANE") || name.equals("RABBIT_FOOT")
                || name.equals("SPIDER_EYE") || name.equals("GUNPOWDER")
                || name.equals("REDSTONE_DUST") || name.equals("GLOWSTONE_DUST");
    }

    private static boolean isFood(String name) {
        return name.startsWith("COOKED_") || name.startsWith("RAW_")
                || name.equals("APPLE") || name.equals("GOLDEN_APPLE")
                || name.equals("ENCHANTED_GOLDEN_APPLE") || name.equals("BREAD")
                || name.equals("CARROT") || name.equals("GOLDEN_CARROT") || name.equals("POTATO")
                || name.equals("BAKED_POTATO") || name.equals("POISONOUS_POTATO")
                || name.equals("BEETROOT") || name.equals("BEETROOT_SOUP")
                || name.equals("MUSHROOM_STEW") || name.equals("RABBIT_STEW")
                || name.equals("SUSPICIOUS_STEW") || name.equals("MELON_SLICE")
                || name.equals("SWEET_BERRIES") || name.equals("GLOW_BERRIES")
                || name.equals("CHORUS_FRUIT") || name.equals("DRIED_KELP")
                || name.equals("HONEY_BOTTLE") || name.equals("MILK_BUCKET")
                || name.equals("PUMPKIN_PIE") || name.equals("CAKE") || name.equals("COOKIE")
                || name.equals("BEEF") || name.equals("PORKCHOP") || name.equals("CHICKEN")
                || name.equals("MUTTON") || name.equals("RABBIT") || name.equals("COD")
                || name.equals("SALMON") || name.equals("TROPICAL_FISH")
                || name.equals("PUFFERFISH") || name.equals("ROTTEN_FLESH");
    }

    private static boolean isCombat(String name) {
        return name.endsWith("_SWORD") || name.endsWith("_AXE") && name.contains("BATTLE")
                || name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT")
                || name.equals("ARROW") || name.endsWith("_ARROW") || name.equals("SHIELD")
                || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.equals("TURTLE_HELMET") || name.equals("TOTEM_OF_UNDYING")
                || name.equals("FIREWORK_ROCKET") || name.equals("MACE")
                || name.equals("WIND_CHARGE") || name.contains("TNT");
    }

    private static boolean isTool(String name) {
        return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE") || name.equals("SHEARS") || name.equals("FLINT_AND_STEEL")
                || name.equals("FISHING_ROD") || name.equals("COMPASS") || name.equals("CLOCK")
                || name.equals("SPYGLASS") || name.equals("BRUSH") || name.equals("LEAD")
                || name.equals("NAME_TAG") || name.endsWith("_BUCKET") || name.equals("BUCKET")
                || name.endsWith("_SIGN") || name.equals("BOOK") || name.equals("WRITABLE_BOOK")
                || name.endsWith("_BOOKSHELF") || name.contains("MAP");
    }

    private static boolean isDecoration(String name) {
        return name.endsWith("_SAPLING") || name.endsWith("_LEAVES") || name.endsWith("_BED")
                || name.equals("PAINTING") || name.equals("ITEM_FRAME")
                || name.equals("GLOW_ITEM_FRAME") || name.equals("FLOWER_POT")
                || name.equals("ARMOR_STAND") || name.endsWith("_BANNER")
                || name.endsWith("_CARPET") || name.endsWith("_CANDLE") || name.equals("CANDLE")
                || name.contains("TORCH") || name.contains("LANTERN") || name.equals("CHAIN")
                || name.endsWith("_HEAD") || name.endsWith("_SKULL") || name.contains("POTTED")
                || name.endsWith("_FLOWER") || name.equals("DANDELION") || name.equals("POPPY")
                || name.equals("PEONY") || name.equals("ROSE_BUSH") || name.equals("LILAC")
                || name.contains("CORAL") || name.contains("SHULKER_BOX")
                || name.contains("FLOWER") || name.contains("MUSHROOM") && !name.contains("STEW")
                || name.equals("VINE") || name.contains("SCULK") || name.contains("GLASS_PANE")
                || name.contains("CAMPFIRE") || name.equals("BEACON")
                || name.equals("CONDUIT") || name.equals("END_ROD");
    }

    private static boolean isBuilding(String name) {
        return name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STAIRS") || name.endsWith("_SLAB") || name.endsWith("_WALL")
                || name.endsWith("_FENCE") || name.endsWith("_BRICKS") || name.endsWith("_BRICK")
                || name.endsWith("_CONCRETE") || name.endsWith("_TERRACOTTA")
                || name.endsWith("_WOOL") || name.endsWith("_GLASS") || name.endsWith("_ORE")
                || name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("SAND")
                || name.contains("DIRT") || name.contains("GRASS_BLOCK")
                || name.contains("COPPER") || name.contains("QUARTZ") || name.contains("PRISMARINE")
                || name.contains("NETHERRACK") || name.contains("BASALT")
                || name.contains("BLACKSTONE") || name.contains("PURPUR")
                || name.contains("END_STONE") || name.contains("OBSIDIAN")
                || name.endsWith("_BLOCK") || name.equals("GRAVEL") || name.equals("CLAY")
                || name.equals("SNOW") || name.equals("ICE") || name.equals("PACKED_ICE")
                || name.equals("BLUE_ICE") || name.equals("GLOWSTONE") || name.equals("SPONGE");
    }

    // ---------------------------------------------------------------------------- second level

    /**
     * Which family within its category a material belongs to — "Oak", "Deepslate", "Red", "Diamond".
     *
     * <p>The second level, and the one that decides whether a chooser is usable. "Building Blocks" on
     * a modern server is several hundred materials and eleven of every twelve are wood: somebody
     * looking for deepslate scrolls past acacia, bamboo, birch, cherry, crimson and dark oak first.
     * The creative inventory has exactly this problem and players only cope because they have
     * memorised where things are.
     *
     * <p>Order matters again, and for the same reason as {@link #categoryOf}: {@code DARK_OAK} has to
     * be recognised before {@code OAK}, and {@code LIGHT_BLUE} before {@code BLUE}, or a whole tree's
     * worth of blocks lands in the wrong drawer.
     */
    public static String groupOf(String material) {
        if (material == null || material.isBlank()) {
            return "Other";
        }
        String name = material.toUpperCase(Locale.ROOT);
        for (String family : FAMILIES) {
            if (name.startsWith(family + "_") || name.equals(family)
                    || name.contains("_" + family + "_") || name.endsWith("_" + family)) {
                return readable(family);
            }
        }
        return "Other";
    }

    /**
     * The families present in one category, in the order they should be shown.
     *
     * <p>A family holding one thing is folded back into "Other": clicking through to a page with a
     * single item on it is worse than a slightly longer list, and a grid of one-item pages is the
     * failure this whole second level exists to avoid.
     */
    public List<String> groupsIn(Category category) {
        Map<String, List<String>> grouped = groupedIn(category);
        List<String> names = new ArrayList<>(grouped.keySet());
        names.remove("Other");
        names.sort(String::compareTo);
        if (grouped.containsKey("Other")) {
            // Always last, whatever it is called: it is the drawer of leftovers and nobody looks
            // there first.
            names.add("Other");
        }
        return names;
    }

    /** Everything in one family of one category, alphabetically. */
    public List<String> itemsIn(Category category, String group) {
        return groupedIn(category).getOrDefault(group, List.of());
    }

    private Map<String, List<String>> groupedIn(Category category) {
        Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        for (String material : itemsIn(category)) {
            grouped.computeIfAbsent(groupOf(material), family -> new ArrayList<>()).add(material);
        }
        // Fold the singletons together afterwards rather than while sorting: whether a family is
        // worth a page depends on how many ended up in it, which is not known until they all have.
        List<String> lonely = new ArrayList<>();
        grouped.entrySet().removeIf(entry -> {
            if (!entry.getKey().equals("Other") && entry.getValue().size() < 2) {
                lonely.addAll(entry.getValue());
                return true;
            }
            return false;
        });
        if (!lonely.isEmpty()) {
            grouped.computeIfAbsent("Other", family -> new ArrayList<>()).addAll(lonely);
        }
        grouped.values().forEach(list -> list.sort(String::compareTo));
        return grouped;
    }

    /**
     * The families a material name might belong to, longest first.
     *
     * <p>Longest first is load-bearing: {@code DARK_OAK} before {@code OAK}, {@code LIGHT_BLUE}
     * before {@code BLUE}, {@code POLISHED_BLACKSTONE} before {@code BLACKSTONE}.
     */
    private static final List<String> FAMILIES = List.of(
            // Compound names first, or the colour rule below eats half of them: RED_SANDSTONE is a
            // kind of sandstone, not a red thing, and RED_NETHER_BRICK is a kind of brick.
            "RED_SANDSTONE", "RED_NETHER_BRICK", "NETHER_BRICK",
            // Woods — the ones that swamp the building blocks tab.
            "DARK_OAK", "PALE_OAK", "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "MANGROVE",
            "CHERRY", "BAMBOO", "CRIMSON", "WARPED",
            // The sixteen colours, before the things that come in sixteen colours. Light ones first
            // so LIGHT_BLUE is not eaten by BLUE.
            "LIGHT_BLUE", "LIGHT_GRAY", "WHITE", "ORANGE", "MAGENTA", "YELLOW", "LIME", "PINK",
            "GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
            // Stones and their many polished, chiselled, cracked relatives.
            "POLISHED_BLACKSTONE", "BLACKSTONE", "COBBLED_DEEPSLATE", "DEEPSLATE", "SANDSTONE",
            "END_STONE", "STONE", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "CALCITE", "BASALT",
            "PRISMARINE", "QUARTZ", "PURPUR", "NETHERRACK", "MUD_BRICK", "BRICK", "COPPER",
            "AMETHYST", "OBSIDIAN",
            // What tools and armour are made of. GOLDEN rather than GOLD: the items are GOLDEN_.
            "NETHERITE", "DIAMOND", "GOLDEN", "IRON", "CHAINMAIL", "WOODEN", "LEATHER", "TURTLE",
            // Creatures, for spawn eggs and heads and the rest.
            "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN", "VILLAGER", "PIGLIN",
            // Odds and ends that still come in families. Last, because these are the ones a colour
            // or a wood should win against.
            "MUSIC_DISC", "POTTERY_SHERD", "SMITHING_TEMPLATE", "TERRACOTTA", "CONCRETE", "CANDLE",
            "GLASS", "WOOL", "CARPET", "BANNER", "SHULKER_BOX", "CORAL", "FROGLIGHT", "MINECART",
            "BOAT", "RAIL");

    // ---------------------------------------------------------------------------- presentation

    /**
     * The words that stay in capitals.
     *
     * <p>An allow-list rather than a rule about short words, which is what this was first: "NO",
     * "USE" and "HIT" are all three letters and all capitals in a sound key, and none of them is an
     * acronym. A deny-list of those would have been missing one for ever.
     */
    private static final java.util.Set<String> ACRONYMS = java.util.Set.of("TNT", "XP", "UI", "ID");

    /**
     * A material name, written the way a person would.
     *
     * <p>{@code DIAMOND_PICKAXE} becomes "Diamond Pickaxe" — but {@code TNT} stays {@code TNT},
     * because "Tnt" is the sort of small wrongness that makes a menu look machine-made.
     */
    public static String readable(String material) {
        if (material == null || material.isBlank()) {
            return "";
        }
        StringBuilder built = new StringBuilder();
        for (String word : material.trim().split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!built.isEmpty()) {
                built.append(' ');
            }
            if ("GOLDEN".equals(word)) {
                // The blocks say GOLD and the items say GOLDEN; a menu showing both is a menu that
                // looks like it has two kinds of gold in it.
                built.append("Gold");
            } else if (ACRONYMS.contains(word)) {
                built.append(word);
            } else {
                built.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return built.toString();
    }
}
