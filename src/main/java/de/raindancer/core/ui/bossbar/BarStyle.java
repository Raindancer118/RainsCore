package de.raindancer.core.ui.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import java.util.Objects;

/**
 * What a boss bar looks like: its text, how full it is, its colour and its overlay.
 *
 * <p>A value rather than a live {@link BossBar}, for the same reason
 * {@link de.raindancer.core.ui.scoreboard.Sidebar} is one: two of them can be compared, which is what
 * lets a plugin rebuild and offer its bar every tick while {@link BossBars} works out that nothing
 * actually changed and sends nothing. A live BossBar cannot be compared that way — mutating it
 * <em>is</em> the update.
 *
 * @param text     what the bar says
 * @param progress how full, 0 to 1; anything outside is clamped rather than thrown, because a
 *                 division that produced 1.0000001 should not take a flight down
 */
public record BarStyle(Component text, float progress, BossBar.Color colour,
                       BossBar.Overlay overlay) {

    public BarStyle {
        Objects.requireNonNull(text, "text");
        progress = Math.max(0f, Math.min(1f, Float.isNaN(progress) ? 0f : progress));
        colour = colour == null ? BossBar.Color.WHITE : colour;
        overlay = overlay == null ? BossBar.Overlay.PROGRESS : overlay;
    }

    /** A full white bar with this text, to be refined with the methods below. */
    public static BarStyle of(Component text) {
        return new BarStyle(text, 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    }

    public BarStyle text(Component newText) {
        return new BarStyle(newText, progress, colour, overlay);
    }

    public BarStyle progress(float newProgress) {
        return new BarStyle(text, newProgress, colour, overlay);
    }

    public BarStyle colour(BossBar.Color newColour) {
        return new BarStyle(text, progress, newColour, overlay);
    }

    public BarStyle overlay(BossBar.Overlay newOverlay) {
        return new BarStyle(text, progress, colour, newOverlay);
    }

    /** A live bar looking like this. */
    BossBar toBar() {
        return BossBar.bossBar(text, progress, colour, overlay);
    }

    /** Makes an existing bar look like this, in place, so the client animates rather than blinks. */
    void applyTo(BossBar bar) {
        bar.name(text);
        bar.progress(progress);
        bar.color(colour);
        bar.overlay(overlay);
    }
}
