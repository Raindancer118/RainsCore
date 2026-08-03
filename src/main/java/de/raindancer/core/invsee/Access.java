package de.raindancer.core.invsee;

/**
 * What somebody watching an inventory is allowed to do to it.
 *
 * <p>Three levels rather than a boolean, because the middle one is the one people actually want:
 * being able to take a stolen item out of somebody's backpack without being able to unequip their
 * armour by clicking one slot too far.
 */
public enum Access {

    /** Look, and nothing else. */
    READ_ONLY("Looking"),

    /** Change what they are carrying, but not what they are wearing. */
    EDIT("Editing"),

    /** Change everything, armour and off-hand included. */
    EDIT_EVERYTHING("Editing everything");

    private final String saying;

    Access(String saying) {
        this.saying = saying;
    }

    public String saying() {
        return saying;
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
