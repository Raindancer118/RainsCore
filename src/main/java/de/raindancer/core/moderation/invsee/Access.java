package de.raindancer.core.moderation.invsee;

/**
 * What somebody watching an inventory is allowed to do to it.
 *
 * <p>Three levels rather than a boolean, because the middle one is the one people actually want:
 * being able to take a stolen item out of somebody's backpack without being able to unequip their
 * armour by clicking one slot too far.
 */
public enum Access {

    /** Look, and nothing else. */
    READ_ONLY("invsee.access.read-only", "Looking"),

    /** Change what they are carrying, but not what they are wearing. */
    EDIT("invsee.access.edit", "Editing"),

    /** Change everything, armour and off-hand included. */
    EDIT_EVERYTHING("invsee.access.edit-everything", "Editing everything");

    private final String key;
    private final String builtIn;

    Access(String key, String builtIn) {
        this.key = key;
        this.builtIn = builtIn;
    }

    /** Which line of the message file names this level. */
    public String key() {
        return key;
    }

    /** What to call this level, in whatever words this server uses. */
    public String saying(de.raindancer.core.ui.messages.Messages words) {
        return words == null ? builtIn : words.raw(key);
    }

    /** The built-in name, for a caller with no message file. */
    public String saying() {
        return builtIn;
    }

    public boolean canEdit() {
        return this != READ_ONLY;
    }

    /** Whether this level may touch a given part. */
    public boolean mayChange(Section section) {
        if (this == READ_ONLY || section == null) {
            return false;
        }
        return this == EDIT_EVERYTHING || !section.isEquipment();
    }
}
