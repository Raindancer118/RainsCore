package de.raindancer.core.land;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a claim keeps hold of a player who briefly leaves its vertical range.
 * <p>
 * Stacked claims are the normal case: a house claim 56–85 sitting inside a full-height claim over the
 * same ground. A jump lifts the player one block, which without a grace band takes them out of the inner
 * claim and into the outer one — a leave and an enter, with the message, the border flash and the toll
 * prompt that go with them, on every single hop.
 */
class BorderStickinessTest {

    private static final UUID WORLD = UUID.randomUUID();

    /** A 5×5 claim over x/z 0..4, ceiling at 85, like a house claim. */
    private static Claim house() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 4, 4, 56, 85);
        return new Claim(UUID.randomUUID(), "house", WORLD, "world", shape, UUID.randomUUID());
    }

    @Test
    @DisplayName("a jump off the claim's ceiling does not count as leaving")
    void aJumpKeepsYouInside() {
        Claim house = house();
        assertThat(ClaimRegistry.stillHolds(house, WORLD, 2, 85, 2)).isTrue();  // standing on top
        assertThat(ClaimRegistry.stillHolds(house, WORLD, 2, 86, 2)).isTrue();  // mid jump
        assertThat(ClaimRegistry.stillHolds(house, WORLD, 2, 87, 2)).isTrue();  // slab or jump boost
    }

    @Test
    @DisplayName("climbing well above the claim is a real departure")
    void climbingOutIsALeave() {
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 2, 88, 2)).isFalse();
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 2, 120, 2)).isFalse();
    }

    @Test
    @DisplayName("the same grace applies at the floor, so a seam does not flicker either")
    void theFloorGetsTheSameGrace() {
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 2, 55, 2)).isTrue();
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 2, 54, 2)).isTrue();
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 2, 53, 2)).isFalse();
    }

    @Test
    @DisplayName("stepping off the footprint leaves immediately, however close the ground")
    void walkingOutIsNeverSticky() {
        Claim house = house();
        assertThat(ClaimRegistry.stillHolds(house, WORLD, 5, 70, 2)).isFalse();
        assertThat(ClaimRegistry.stillHolds(house, WORLD, -1, 70, 2)).isFalse();
        assertThat(ClaimRegistry.stillHolds(house, WORLD, 2, 70, 9)).isFalse();
    }

    @Test
    @DisplayName("a claim in another world never holds anybody")
    void anotherWorldNeverHolds() {
        assertThat(ClaimRegistry.stillHolds(house(), UUID.randomUUID(), 2, 70, 2)).isFalse();
        assertThat(ClaimRegistry.stillHolds(null, WORLD, 2, 70, 2)).isFalse();
    }

    @Test
    @DisplayName("deep inside the claim is held, which is the ordinary case")
    void insideIsHeld() {
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 0, 56, 0)).isTrue();
        assertThat(ClaimRegistry.stillHolds(house(), WORLD, 4, 85, 4)).isTrue();
    }
}
