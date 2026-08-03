package de.raindancer.core.choose;

import de.raindancer.core.time.Times;

import java.time.Duration;
import java.util.UUID;

/**
 * One person a plugin might want to pick.
 *
 * <p>Plain data, and deliberately not an {@code OfflinePlayer}: every question worth asking about
 * this list — what order, who to leave out, how long ago somebody was here — is then ordinary code
 * that can be tested, and only the last step needs a server.
 *
 * @param id       their unique id, which is the only thing about them that never changes
 * @param name     the name last seen; people change these, which is exactly why the id is the key
 * @param online   whether they are here now
 * @param lastSeen when they were last here, in milliseconds; 0 for somebody never seen
 */
public record PlayerEntry(UUID id, String name, boolean online, long lastSeen) {

    public PlayerEntry {
        if (id == null) {
            throw new IllegalArgumentException("a player entry needs an id");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a player entry needs a name");
        }
        name = name.trim();
    }

    /**
     * How long ago they were here, the way somebody would say it.
     *
     * <p>"here now" for somebody standing in front of you, because "0 seconds ago" is nonsense, and
     * "never seen" rather than a date in 1970 for somebody the server has no record of.
     */
    public String lastSeenDescribed(long now) {
        if (online) {
            return "here now";
        }
        if (lastSeen <= 0) {
            return "never seen";
        }
        long since = now - lastSeen;
        return since < 1_000 ? "just now" : Times.describe(Duration.ofMillis(since)) + " ago";
    }

    /** How long ago they were here. Empty for somebody who is here, or was never seen. */
    public java.util.Optional<Duration> away(long now) {
        return online || lastSeen <= 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.ofMillis(Math.max(0, now - lastSeen)));
    }
}
