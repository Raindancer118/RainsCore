package de.raindancer.core.social.team;

import org.bukkit.Material;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A named, coloured group of players, as it is right now.
 *
 * <h2>What this is and what it deliberately is not</h2>
 * It is the shape of a team: an identity, a name people call it, a colour it is told apart by, who is in it,
 * and who leads it if anybody does. Four plugins need exactly that and would each write it out.
 *
 * <p>It is <b>not</b> a roster and holds no rules. Nothing here says how many members are allowed, whether two
 * teams may share a colour, when membership stops being editable, or who may change any of it. Those answers
 * differ per plugin and are not reconcilable: a tournament freezes its teams the moment a round starts and
 * refuses a colour another team has claimed, whereas a clans plugin never freezes anything and may not care
 * about colours at all. A shared answer would be one of them being wrong, so each keeps its own registry and
 * this record is what they agree about.
 *
 * <h2>Members are everybody in it, not everybody still standing</h2>
 * This record does not know what "still standing" means — that is the owning plugin's idea, and in a
 * tournament it is the difference between a team of four winning and one player winning. So {@link #members()}
 * is simply who is in the team, and any question about their state is asked of whatever tracks state. Filtering
 * here would mean a winning team's membership shrinking as it won.
 *
 * @param id      fixed when the team is created and never changed — see {@link TeamId}
 * @param name    what people call it; the owning plugin decides how long it stays editable
 * @param colour  the colour it is told apart by; whether two teams may share one is the registry's rule
 * @param emblem  the character shown before its name, which is what lets two teams share a colour and still
 *                be told apart — see {@link TeamEmblem} for why sixteen colours is a hard ceiling
 * @param badge   the item this team is drawn as, chosen by its own members out of the item chooser rather
 *                than assigned; see {@link #badge()}
 * @param members everybody in it
 * @param captain who leads it, where the owning plugin has such a notion and one is set
 */
public record Team(
        TeamId id,
        String name,
        TeamColour colour,
        TeamEmblem emblem,
        Material badge,
        Set<UUID> members,
        Optional<UUID> captain) {

    public Team {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(colour, "colour");
        // Never null, and NONE rather than null is the whole point: a team without an emblem is a normal team
        // told apart by colour, not a team in an incomplete state. Every caller that reads an emblem would
        // otherwise have to null-check it, and one of them would not.
        emblem = emblem == null ? TeamEmblem.NONE : emblem;
        // Falls back to the emblem's own suggestion, or — for a plain team with no emblem — to a banner in the
        // team's own colour rather than TeamEmblem.NONE's fixed WHITE_BANNER. Without this, a red team that
        // never touched the item chooser is drawn as a white banner in every screen that reads this field,
        // while its name and nametag are correctly red — two data paths for one identity, silently disagreeing.
        // Never null: every screen draws this, and one null is a page that throws while forty people are
        // picking teams.
        badge = badge == null ? (emblem.isVisible() ? emblem.suggestedBadge() : colour.bannerMaterial()) : badge;
        Objects.requireNonNull(captain, "captain");
        // Copied rather than wrapped: a caller holding on to the set it passed in could otherwise add somebody
        // to a team nobody asked to change, and the change would show up in a screen with no event fired and
        // nothing written to disk.
        members = Set.copyOf(members);
    }

    /** A team with nobody in it, nobody leading it, and no emblem — told apart by its colour. */
    public static Team of(TeamId id, String name, TeamColour colour) {
        return new Team(id, name, colour, TeamEmblem.NONE, null, Set.of(), Optional.empty());
    }

    /** A team with nobody in it, told apart by its colour <em>and</em> its emblem. */
    public static Team of(TeamId id, String name, TeamColour colour, TeamEmblem emblem) {
        return new Team(id, name, colour, emblem, null, Set.of(), Optional.empty());
    }

    /** Whether that player is in this team. */
    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    /** Whether that player leads it. */
    public boolean isCaptain(UUID player) {
        return player != null && captain.filter(player::equals).isPresent();
    }

    /** How many are in it. */
    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    // ------------------------------------------------------------------ one change at a time

    public Team withName(String newName) {
        return new Team(id, newName, colour, emblem, badge, members, captain);
    }

    public Team withColour(TeamColour newColour) {
        return new Team(id, name, newColour, emblem, badge, members, captain);
    }

    /**
     * The same team, drawn as that item.
     *
     * <p>What the item chooser writes. Deliberately not validated against a list of "sensible" materials: a
     * team that wants to be a cake is a team that wants to be a cake, and a plugin refusing it would be
     * refusing the only part of a team its members actually chose.
     */
    public Team withBadge(Material newBadge) {
        return new Team(id, name, colour, emblem, newBadge, members, captain);
    }

    public Team withEmblem(TeamEmblem newEmblem) {
        return new Team(id, name, colour, newEmblem, badge, members, captain);
    }

    public Team withMembers(Set<UUID> newMembers) {
        return new Team(id, name, colour, emblem, badge, newMembers, captain);
    }

    /**
     * The same team, led by that player — or by nobody, which is what empty means.
     *
     * <p>Refuses a captain who is not a member. A team led by somebody who is not in it is a state every
     * caller would have to check for and one of them would forget, and the screens that draw a captain would
     * then show somebody who is on another team entirely.
     */
    public Team withCaptain(Optional<UUID> newCaptain) {
        Objects.requireNonNull(newCaptain, "captain");
        if (newCaptain.isPresent() && !members.contains(newCaptain.get())) {
            throw new IllegalArgumentException(
                    "a team cannot be led by somebody who is not in it: " + newCaptain.get());
        }
        return new Team(id, name, colour, emblem, badge, members, newCaptain);
    }

    /**
     * What tells this team apart from every other, as a pair.
     *
     * <p>A registry that enforces unique identities compares <em>this</em> rather than the colour, which is
     * what raises the ceiling from sixteen teams to sixteen times the number of emblems. A record so that it
     * can be a map key and a set element without anybody writing an equals.
     */
    public record Identity(TeamColour colour, TeamEmblem emblem) {

        public Identity {
            Objects.requireNonNull(colour, "colour");
            emblem = emblem == null ? TeamEmblem.NONE : emblem;
        }

        /** How it reads: "Red ♦", or just "Red" for a team with no emblem. */
        public String describe() {
            return colour.describe() + (emblem.isVisible() ? " " + emblem.glyph() : "");
        }
    }

    /** This team's identity — its colour and its emblem together. */
    public Identity identity() {
        return new Identity(colour, emblem);
    }

    /**
     * The name as it should be shown: the emblem, then the name.
     *
     * <p>Here rather than in each screen, because the emblem is only useful if it appears everywhere the name
     * does — a nametag with it and a scoreboard without it is worse than neither, since somebody learns the
     * symbol and then cannot find it where they look next.
     */
    public String display() {
        return emblem.prefix() + name;
    }
}
