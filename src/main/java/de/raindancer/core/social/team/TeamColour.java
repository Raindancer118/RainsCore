package de.raindancer.core.social.team;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * The sixteen colours a group of players can be told apart by.
 *
 * <h2>Why this is one enum rather than a dye colour and a text colour side by side</h2>
 * A team's colour is shown in four places — the leather armour its members wear, the name above their heads,
 * the scoreboard, and chat — and the entire purpose of a team colour is that somebody across the map can tell
 * at a glance who is on whose side. Configured as two separate fields, they are two fields that drift, and the
 * drift is invisible to whoever set it up and obvious to everybody playing: the team in brown leather has dark
 * red names.
 *
 * <p>So the mapping is made once, here, and nothing else chooses. Several of these deliberately do not map to
 * the obviously-named text colour, because Minecraft's palettes do not line up: {@code MAGENTA} and
 * {@code PINK} both wear {@code LIGHT_PURPLE} — there are two pinks in dye and one in text — and
 * {@code BROWN} wears {@code DARK_RED}, because there is no brown in text at all. Those compromises are the
 * whole value of the table; made independently by each caller, they are made differently by each caller.
 *
 * <h2>Why this is in RainsCore</h2>
 * It arrived with a Hunger Games tournament, where teams claim a colour each. It is not about tournaments. A
 * clans plugin, a parties plugin, a two-team minigame and a build competition all need the same sixteen
 * distinguishable colours mapped onto the same four surfaces, and each would write this table out again — with
 * its own answer for brown.
 *
 * <p>What is deliberately <em>not</em> here is anything about managing teams: no roster, no exclusivity, no
 * limits, no captain rules. Those differ per plugin — a tournament freezes its teams when the round starts, a
 * clans plugin never does — and a shared answer would be one or the other's wrong. See {@link Team}.
 *
 * <h2>Why it imports Bukkit and is still plain</h2>
 * {@link DyeColor} and {@link Color} are constants. Nothing here calls {@code Bukkit.get…}, touches a world,
 * or needs a running server, so the whole enum can be exercised in an ordinary unit test.
 */
public enum TeamColour {

    WHITE(DyeColor.WHITE, NamedTextColor.WHITE),
    ORANGE(DyeColor.ORANGE, NamedTextColor.GOLD),
    MAGENTA(DyeColor.MAGENTA, NamedTextColor.LIGHT_PURPLE),
    LIGHT_BLUE(DyeColor.LIGHT_BLUE, NamedTextColor.AQUA),
    YELLOW(DyeColor.YELLOW, NamedTextColor.YELLOW),
    LIME(DyeColor.LIME, NamedTextColor.GREEN),
    PINK(DyeColor.PINK, NamedTextColor.LIGHT_PURPLE),
    GRAY(DyeColor.GRAY, NamedTextColor.DARK_GRAY),
    LIGHT_GRAY(DyeColor.LIGHT_GRAY, NamedTextColor.GRAY),
    CYAN(DyeColor.CYAN, NamedTextColor.DARK_AQUA),
    PURPLE(DyeColor.PURPLE, NamedTextColor.DARK_PURPLE),
    BLUE(DyeColor.BLUE, NamedTextColor.BLUE),
    BROWN(DyeColor.BROWN, NamedTextColor.DARK_RED),
    GREEN(DyeColor.GREEN, NamedTextColor.DARK_GREEN),
    RED(DyeColor.RED, NamedTextColor.RED),
    BLACK(DyeColor.BLACK, NamedTextColor.BLACK);

    private final DyeColor dye;
    private final NamedTextColor text;

    TeamColour(DyeColor dye, NamedTextColor text) {
        this.dye = dye;
        this.text = text;
    }

    /** What the leather armour is dyed, and what the wool, concrete or banner of this team is. */
    public DyeColor dyeColour() {
        return dye;
    }

    /** The same colour as an RGB value, which is what leather armour meta actually takes. */
    public Color armourColour() {
        return dye.getColor();
    }

    /**
     * The banner a team with no emblem or custom badge of its own shows.
     *
     * <p>Bukkit names every banner material after its dye colour exactly — {@code ORANGE_BANNER},
     * {@code LIGHT_BLUE_BANNER} and so on — so this is a lookup, not a second table to keep in step with
     * {@link #dyeColour()}. A hand-written table is exactly the kind of duplicate this enum's own class note
     * warns against: it would agree with {@code dye} today and drift the day somebody adds a colour to one
     * and not the other.
     */
    public Material bannerMaterial() {
        return Material.valueOf(dye.name() + "_BANNER");
    }

    /** What the name above their head, the scoreboard entry and their chat lines are coloured. */
    public TextColor textColour() {
        return text;
    }

    /**
     * The same colour, as the narrower type a scoreboard team insists on.
     *
     * <p>Separate from {@link #textColour()} only because Bukkit's scoreboard API will not take a general
     * {@code TextColor}. Every value here is a {@link NamedTextColor} already, so the two cannot disagree —
     * which is why this returns the stored field rather than converting.
     */
    public NamedTextColor namedTextColour() {
        return text;
    }

    /**
     * Reads a colour by name, case- and space-insensitively.
     *
     * <p>Empty rather than an exception, and empty rather than a default. This is what a command argument and
     * a hand-edited config file both arrive as, and the caller is the only one that knows whether an
     * unreadable colour should be refused with a message or quietly skipped. A method that picked white would
     * make a typo look like a deliberate choice.
     */
    public static Optional<TeamColour> named(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (TeamColour colour : values()) {
            if (colour.name().equals(wanted)) {
                return Optional.of(colour);
            }
        }
        return Optional.empty();
    }

    /** How it reads to a person: {@code LIGHT_BLUE} as "Light blue". */
    public String describe() {
        String words = name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
