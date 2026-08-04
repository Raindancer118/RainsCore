package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every creature a server knows, sorted into drawers.
 *
 * <h2>Why the names are injected</h2>
 * The same reason {@link Catalogue} takes its materials from outside: asking the registry what
 * exists needs a running server, and what is worth testing here is the sorting. So the list of names
 * comes in and the sorting is ordinary code with an ordinary test.
 *
 * <p>Worked out once and kept. It is a few hundred strings through one lookup, which is nothing —
 * but it is nothing <em>per page render</em>, and a chooser is re-rendered on every click.
 */
public final class MobCatalogue {

    private final Supplier<List<String>> types;

    private volatile Map<MobFamily, List<String>> sorted;

    /** @param types the entity-type names to sort — on a server, every one that can be spawned */
    public MobCatalogue(Supplier<List<String>> types) {
        this.types = types;
    }

    public List<String> inFamily(MobFamily family) {
        return sorted().getOrDefault(family, List.of());
    }

    /** Which drawers actually have anything in them. */
    public List<MobFamily> families() {
        return List.of(MobFamily.values()).stream()
                .filter(family -> !inFamily(family).isEmpty())
                .toList();
    }

    /** Everything, alphabetically. */
    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /**
     * Everything a wave or a pack may be built from.
     *
     * <p>Not a matter of taste: a "wave" of armour stands is a prank on whoever pressed the button,
     * and a wave of cows is a lag spike with no way to end it. What earns a place here is something
     * that can be fought and that will eventually die or be killed.
     */
    public List<String> fightable() {
        List<String> everything = new ArrayList<>();
        for (MobFamily family : MobFamily.values()) {
            if (family.fightable()) {
                everything.addAll(inFamily(family));
            }
        }
        everything.sort(String::compareTo);
        return everything;
    }

    /** Everything whose name contains this, with an exact match first. */
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

    /**
     * The icon for one creature, as a material name.
     *
     * <p>Its spawn egg, which is the picture of that mob every player already knows. The ones with no
     * egg — the dragon, the wither, the snow golem, everything in {@link MobFamily#OBJECT} — fall back
     * to their family's icon rather than to nothing.
     *
     * <p>A name rather than a {@code Material} for the usual reason: resolving one needs the server's
     * registry, and the naming is the part worth testing.
     */
    public static String iconFor(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return MobFamily.OTHER.icon();
        }
        String name = entityType.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        return switch (name) {
            // The handful with no spawn egg at all.
            case "ENDER_DRAGON" -> "DRAGON_HEAD";
            case "WITHER" -> "WITHER_SKELETON_SKULL";
            case "SNOW_GOLEM" -> "CARVED_PUMPKIN";
            case "IRON_GOLEM" -> "IRON_BLOCK";
            case "PLAYER" -> "PLAYER_HEAD";
            case "GIANT" -> "ZOMBIE_HEAD";
            case "ILLUSIONER" -> "TIPPED_ARROW";
            default -> MobFamily.of(name) == MobFamily.OBJECT
                    ? MobFamily.OBJECT.icon()
                    : name + "_SPAWN_EGG";
        };
    }

    private Map<MobFamily, List<String>> sorted() {
        Map<MobFamily, List<String>> found = sorted;
        if (found != null) {
            return found;
        }
        Map<MobFamily, List<String>> drawers = new EnumMap<>(MobFamily.class);
        for (String type : types.get()) {
            if (type == null || type.isBlank()) {
                continue;
            }
            drawers.computeIfAbsent(MobFamily.of(type), key -> new ArrayList<>()).add(type);
        }
        drawers.values().forEach(list -> list.sort(String::compareTo));
        sorted = Map.copyOf(drawers);
        return sorted;
    }
}
