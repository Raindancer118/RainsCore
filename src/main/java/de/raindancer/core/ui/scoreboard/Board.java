package de.raindancer.core.ui.scoreboard;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * One player's sidebar, at the packet level.
 *
 * <h2>Why this interface exists</h2>
 * Behind it sits the copied-in FastBoard, which is raw reflection into the server's internals and
 * therefore cannot run in a test at all — it fails in a static initialiser off a real server. Every
 * rule worth getting right in {@link Scoreboards} is above this line: who wins, what happens when
 * they leave, what is sent and what is not. All of that is tested against a fake implementation.
 *
 * <p>Implementations may throw from any method. {@link Scoreboards} expects it: a scoreboard is
 * decoration, and a server whose internals FastBoard does not recognise must lose the sidebar, not
 * the plugin.
 */
public interface Board {

    /** Replaces the title and every line. */
    void update(Component title, List<Component> lines);

    /** Takes the sidebar away. Called at most once. */
    void delete();
}
