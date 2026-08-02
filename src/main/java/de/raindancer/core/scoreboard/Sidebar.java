package de.raindancer.core.scoreboard;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Objects;

/**
 * What a sidebar says: a title and some lines.
 *
 * <p>A value, so two of them can be compared — which is what lets {@link Scoreboards} skip sending a
 * sidebar that has not changed, and therefore lets a plugin rebuild and offer one every tick without
 * costing a packet every tick. That is the normal way to use this: build it fresh, hand it over, let
 * the manager work out whether anything actually needs saying.
 */
public record Sidebar(Component title, List<Component> lines) {

    /**
     * How many lines a client will show.
     *
     * <p>Fifteen is the vanilla limit. More than that are dropped rather than refused: a sidebar
     * with sixteen lines is a small mistake, and refusing to draw any of it is a bigger one.
     */
    public static final int MAX_LINES = 15;

    public Sidebar {
        Objects.requireNonNull(title, "title");
        List<Component> given = lines == null ? List.of() : lines;
        lines = given.size() <= MAX_LINES ? List.copyOf(given)
                : List.copyOf(given.subList(0, MAX_LINES));
    }

    public static Sidebar of(Component title, List<Component> lines) {
        return new Sidebar(title, lines);
    }

    public static Sidebar of(Component title, Component... lines) {
        return new Sidebar(title, List.of(lines));
    }
}
