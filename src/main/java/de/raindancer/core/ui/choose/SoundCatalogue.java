package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every sound a server knows, sorted so somebody can find one.
 *
 * <p>The same problem as {@link Catalogue} and the same answer. There are well over a thousand sound
 * keys; a plugin that wants to let an owner pick one either ships a list of twelve or shows all of
 * them in registry order, and neither is a chooser.
 *
 * <p>Sorted by the first word of the key, because Minecraft's own names are already a hierarchy and
 * inventing a different one would only be a second thing to learn.
 */
public final class SoundCatalogue {

    private final Supplier<List<String>> sounds;
    private volatile Map<SoundFamily, List<String>> sorted;

    /** @param sounds the sound keys this server has — the registry, on a real one */
    public SoundCatalogue(Supplier<List<String>> sounds) {
        this.sounds = sounds;
    }

    public List<String> inFamily(SoundFamily family) {
        return sorted().getOrDefault(family, List.of());
    }

    public List<SoundFamily> families() {
        return List.of(SoundFamily.values()).stream()
                .filter(family -> !inFamily(family).isEmpty())
                .toList();
    }

    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /** Every sound whose key contains this; an exact match first. */
    public List<String> search(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT).replace(' ', '.');
        return all().stream()
                .filter(key -> key.toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((String key) ->
                        key.equalsIgnoreCase(wanted) ? 0 : 1).thenComparing(key -> key))
                .toList();
    }

    /**
     * The thing that makes the noise, as a material name.
     *
     * <h2>Why this is not just a note block</h2>
     * Because a grid of forty-five identical note blocks is not a chooser — it is a list of names in
     * a costume, and picking from it means reading {@code block.amethyst_block.chime} and imagining.
     * The key already says what makes the sound, so the icon can be that: the amethyst block, the
     * bell, the anvil, the zombie.
     *
     * <p>Taken from the middle of the key, which is where the game puts the thing:
     * {@code block.<b>bell</b>.use}, {@code entity.<b>zombie</b>.ambient}. A creature gets its spawn
     * egg, one without a spawn egg gets its head, and anything left over gets something that suits
     * its family rather than nothing.
     *
     * <p>Returns a name rather than a {@code Material} on purpose: resolving one needs the server's
     * registry, and the naming is the part worth testing.
     */
    public static String iconFor(String key) {
        if (key == null || key.isBlank()) {
            return "NOTE_BLOCK";
        }
        String name = key.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        SoundFamily family = SoundFamily.of(name);
        String[] parts = name.split("\\.");
        String subject = parts.length >= 2 ? parts[1].toUpperCase(Locale.ROOT) : "";

        return switch (family) {
            // A music disc is named after the track, so the key is very nearly the item already.
            case MUSIC -> name.startsWith("music_disc.")
                    ? "MUSIC_DISC_" + subject : "MUSIC_DISC_" + pickDisc(name);
            case ENTITY -> creature(subject);
            case UI -> "OAK_BUTTON";
            case AMBIENT -> name.startsWith("weather.") ? "WATER_BUCKET" : "DEEPSLATE";
            // Blocks and items are the easy case: the middle word usually is the material.
            case BLOCK, ITEM, OTHER -> subject.isEmpty() ? "NOTE_BLOCK" : subject;
        };
    }

    /**
     * A creature's icon: its spawn egg, or its head, or a failing that something of its own.
     *
     * <p>The exceptions are the mobs with no spawn egg. A note block standing in for the ender
     * dragon would be exactly the wrongness this method exists to remove.
     */
    private static String creature(String subject) {
        return switch (subject) {
            case "PLAYER" -> "PLAYER_HEAD";
            case "ENDER_DRAGON" -> "DRAGON_HEAD";
            case "WITHER" -> "WITHER_SKELETON_SKULL";
            case "ILLUSIONER", "GIANT", "ZOMBIE_HORSE", "SKELETON_HORSE" -> "BONE";
            case "ITEM", "ITEM_FRAME" -> "ITEM_FRAME";
            case "ARROW", "SPECTRAL_ARROW" -> "ARROW";
            case "BOAT", "CHEST_BOAT" -> "OAK_BOAT";
            case "MINECART" -> "MINECART";
            case "LIGHTNING_BOLT" -> "LIGHTNING_ROD";
            case "EXPERIENCE_ORB", "EXPERIENCE_BOTTLE" -> "EXPERIENCE_BOTTLE";
            case "GENERIC", "" -> "NOTE_BLOCK";
            default -> subject + "_SPAWN_EGG";
        };
    }

    /** Something to stand for a music track that is not a disc. */
    private static String pickDisc(String name) {
        return name.contains("nether") || name.contains("end") ? "PIGSTEP" : "CAT";
    }

    /**
     * A sound key, written for a menu.
     *
     * <p>{@code block.note_block.bell} becomes "Note Block Bell": the family is already the page
     * somebody is on, so repeating it in every line is noise.
     */
    public static String readable(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String name = key.replace("minecraft:", "");
        int firstDot = name.indexOf('.');
        String withoutFamily = firstDot > 0 ? name.substring(firstDot + 1) : name;
        return Catalogue.readable(withoutFamily.replace('.', '_').toUpperCase(Locale.ROOT));
    }

    private Map<SoundFamily, List<String>> sorted() {
        Map<SoundFamily, List<String>> known = sorted;
        if (known != null) {
            return known;
        }
        Map<SoundFamily, List<String>> built = new EnumMap<>(SoundFamily.class);
        for (String key : sounds.get()) {
            built.computeIfAbsent(SoundFamily.of(key), family -> new ArrayList<>()).add(key);
        }
        built.values().forEach(list -> list.sort(String::compareTo));
        sorted = built;
        return built;
    }

    public void refresh() {
        sorted = null;
    }
}
