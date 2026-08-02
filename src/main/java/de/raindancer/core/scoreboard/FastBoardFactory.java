package de.raindancer.core.scoreboard;

import de.raindancer.core.internal.fastboard.adventure.FastBoard;
import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The packet layer: {@link Board} on top of the copied-in FastBoard.
 *
 * <p>The whole of the reflection-touching half of the sidebar, which is the point of the seam —
 * everything worth testing lives on the other side of it, in {@link Scoreboards}.
 *
 * <p>It lives in this package rather than with the other Bukkit adapters because it is the only
 * thing allowed to import the copied-in FastBoard: keeping that to one package is what makes
 * swapping the scoreboard implementation cost one class, and {@code NoExternalDependenciesTest}
 * enforces it.
 *
 * <h2>Why this throws rather than handling anything</h2>
 * FastBoard fails in a static initialiser on a server whose internals it does not recognise, and
 * that arrives as {@link ExceptionInInitializerError} — an {@link Error}, not an exception, and not
 * something this class can do anything sensible about. {@code Scoreboards} catches it, decides the
 * server has no sidebars, says so once through the logger and stops asking. Duplicating that
 * judgement here would only give two places to disagree about it.
 */
public final class FastBoardFactory implements BoardFactory {

    private static final LogChannel log = Log.of("scoreboard");

    @Override
    public Board create(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            // Gone between the decision and the packet. Normal, not exceptional — but there is no
            // board to give back, so this counts as a failure and the claim is dropped.
            throw new IllegalStateException("player " + player + " is not online");
        }
        return new FastBoardAdapter(new FastBoard(online));
    }

    /**
     * One player's board.
     *
     * <p>FastBoard's own API is close enough to {@link Board} that this is mostly delegation. The
     * one thing it adds is not letting a deleted board be written to: FastBoard answers that with an
     * {@link IllegalStateException}, and a player logging out mid-update is common enough that
     * turning it into a thrown exception every time would fill the log with something nobody can act
     * on.
     */
    private record FastBoardAdapter(FastBoard board) implements Board {

        @Override
        public void update(Component title, List<Component> lines) {
            if (board.isDeleted()) {
                log.debug("Ignoring a sidebar update for a board that is already gone.");
                return;
            }
            board.updateTitle(title);
            board.updateLines(lines);
        }

        @Override
        public void delete() {
            if (!board.isDeleted()) {
                board.delete();
            }
        }
    }
}
