package de.raindancer.core.social.team;

import java.util.Locale;
import java.util.Objects;

/**
 * What a team is called by everything that is not a person.
 *
 * <h2>Why a team needs an id when it already has a name</h2>
 * Because the name changes. A team is made, renamed twice while people argue about it, and recoloured once
 * somebody notices two of them look blue — and all of that happens while it already has members, is written
 * in a file, and is referred to by whatever remembers who is on it. An identity that changes is not one:
 * rename a team keyed by its name and every member is quietly on a team that no longer exists.
 *
 * <p>So the id is fixed when the team is created and nothing changes it, while the display name and the
 * colour stay editable for as long as the owning plugin allows.
 *
 * <h2>Why it is lower-cased rather than merely compared case-insensitively</h2>
 * Because it is also a key in a file and a fragment of a scoreboard team name, and in both of those
 * {@code Rote-Raben} and {@code rote-raben} are two separate entries. Normalising on the way in means there
 * is one spelling on disk, so a hand-edited file cannot produce two teams that read identically in every
 * screen and are not the same team.
 *
 * @param value the normalised id — lower case, never blank, e.g. {@code team-3} or {@code rote-raben}
 */
public record TeamId(String value) {

    public TeamId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a team id cannot be blank");
        }
        /*
         * Whitespace is normalised here and not only in fromName, and that is the whole reason this
         * constructor has a body.
         *
         * Without it the two doors into this type disagree: `TeamId.fromName("Rote Raben")` gives
         * `rote-raben`, and `new TeamId("Rote Raben")` gives `rote raben` — two different values, unequal, with
         * different hash codes, for one team. They then key different entries in the roster's map, and the
         * failure is invisible in every screen: both render as the team's display name, so a menu shows two
         * identical-looking teams and joining one of them puts you on the wrong one.
         *
         * Both doors are used, which is what makes it live rather than theoretical. `fromName` is what a
         * command and a menu go through; the canonical constructor is what deserialisation goes through, and a
         * yaml file written by hand or by an older version can carry a space.
         */
        value = value.strip().replaceAll("\\s+", "-").toLowerCase(Locale.ROOT);
    }

    /**
     * The id a display name would get.
     *
     * <p>Runs of whitespace collapse to one dash, so {@code "Rote  Raben"} and {@code "Rote Raben"} are the
     * same team rather than two that read identically wherever they are shown.
     *
     * <p>The normalising itself is the constructor's, not this method's — see the note there. This is kept as
     * the way a *display name* is turned into an id, because that is what the call reads as at the site, and
     * because a caller who has a name should not have to know that the two happen to be the same operation
     * today.
     */
    public static TeamId fromName(String name) {
        Objects.requireNonNull(name, "name");
        return new TeamId(name);
    }

    @Override
    public String toString() {
        return value;
    }
}
