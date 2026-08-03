package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules the enforcement listeners have to keep, checked against their own source.
 *
 * <p>Each of these is a defect that was actually there. They are held as a source scan because the alternative is
 * a running server, two players, a piston and a bucket of splash potions — and because the shape of each mistake
 * is visible in the code: an {@code instanceof LivingEntity} in front of a damage check, a hand comparison in
 * front of an interaction check, a destination that is never looked at.
 *
 * <p>A scan is a blunt instrument and these are deliberately narrow: each one names the exact pattern that was
 * wrong, so it fails when somebody writes that pattern again and stays quiet otherwise.
 */
class ProtectionRulesTest {

    private static final Path LISTENERS =
            Path.of("src/main/java/de/raindancer/core/world/protection");

    private record Source(String name, String body) {
    }

    private static List<Source> listeners() {
        try (Stream<Path> files = Files.list(LISTENERS)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (name.endsWith("Listener")) {
                    found.add(new Source(name, Files.readString(file)));
                }
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the protection package", unreadable);
        }
    }

    private static String bodyOf(String listener) {
        return listeners().stream()
                .filter(source -> source.name().equals(listener))
                .findFirst()
                .orElseThrow(() -> new AssertionError(listener + " is gone — this test is about its rules"))
                .body();
    }

    @Test
    @DisplayName("the scan found the listeners, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(listeners()).extracting(Source::name)
                .contains("BlockProtectionListener", "InteractionProtectionListener",
                        "EnvironmentProtectionListener", "MobControlListener");
    }

    @Test
    @DisplayName("interaction is checked for both hands")
    void nothingIsWavedThroughForBeingInTheOffHand() {
        // Refusing only the main hand let a player carry the thing they wanted to use in the off-hand and open
        // any chest on the server: the event fires once per hand and only one was being checked.
        assertThat(bodyOf("InteractionProtectionListener"))
                .as("an off-hand comparison in front of an interaction check is a way past every rule here")
                .doesNotContain("getHand() != EquipmentSlot.HAND");
    }

    @Test
    @DisplayName("explosion damage is checked for everything, not only the living")
    void framesAndPaintingsAreProtectedToo() {
        // Item frames, paintings, minecarts and boats are exactly what somebody detonating TNT at a border is
        // after, and none of them is a LivingEntity.
        assertThat(bodyOf("EnvironmentProtectionListener"))
                .as("an instanceof LivingEntity in front of the explosion-damage check protects the cows and "
                        + "leaves the map wall on the floor")
                .doesNotContain("getEntity() instanceof LivingEntity\n                    && deniedFor");
    }

    @Test
    @DisplayName("a piston is judged on where blocks land, not only on what moves")
    void pistonsCannotPushIntoProtectedGround() {
        String body = bodyOf("EnvironmentProtectionListener");

        assertThat(body)
                .as("checking only the moved blocks leaves the obvious machine working: piston outside, "
                        + "unclaimed block at the border, push it in")
                .contains("getRelative(direction)");
        assertThat(body)
                .as("both piston events carry a direction and both have to pass it")
                .contains("event.getDirection()");
    }

    @Test
    @DisplayName("a mob may walk out of an area it is already in")
    void mobsAreNotTrappedAtTheBorder() {
        String body = bodyOf("MobControlListener");

        // MONSTER_ENTRY defaults to "not allowed", so asking the flag about a destination in open country
        // answered no and the mob stood at the border for ever.
        assertThat(body)
                .as("the entry rule has to look up the destination area and return early when there is none, "
                        + "or leaving is refused as though it were entering")
                .contains("land.areaAt(event.getTo())");
        assertThat(body)
                .as("asking the flag by location alone is what trapped them")
                .doesNotContain("isAllowedAt(event.getTo(), LandFlag.MONSTER_ENTRY)");
    }

    @Test
    @DisplayName("potions are judged for every creature they land on")
    void splashPotionsDoNotKillAnimalsThroughAFence() {
        String body = bodyOf("InteractionProtectionListener");

        // Potion damage produces no damage event naming the thrower, so without this a splash of Harming over
        // a fence killed every cow, sheep, villager and tamed wolf inside and nothing objected.
        assertThat(body)
                .as("the splash handler has to judge non-players too")
                .contains("mayBeSplashed");
        assertThat(body)
                .as("only players being checked is the hole this closes")
                .doesNotContain("affected instanceof Player hurt && !pvpAllowed");
    }

    @Test
    @DisplayName("a brewed lingering potion is recognised as harmful")
    void theBasePotionTypeIsRead() {
        // A brewed potion carries its effect as the base type and getCustomEffects() is empty for it, so
        // checking only the custom list read every stock Lingering Potion of Harming as harmless.
        assertThat(bodyOf("InteractionProtectionListener"))
                .as("the base potion type has to be read, or vanilla lingering potions walk straight through")
                .contains("getBasePotionType()");
    }

    @Test
    @DisplayName("the potion flag covers drinking as well as throwing")
    void potionsAreGovernedBothWays() {
        String body = bodyOf("InteractionProtectionListener");

        assertThat(body)
                .as("drinking is the half people ask for second and want first — an arena where fighters "
                        + "bring what they brought")
                .contains("PlayerItemConsumeEvent");
        assertThat(body)
                .as("and throwing, judged where it was thrown from")
                .contains("ProjectileLaunchEvent");
        assertThat(body).contains("LandFlag.POTIONS");
    }

    @Test
    @DisplayName("every flag has wording shipped for it")
    void noFlagRendersAsItsOwnKey() throws IOException {
        // A flag whose keys are missing renders as "land.flag.potions.name" on the button, which is the sort
        // of thing that ships because nobody opened that particular menu.
        String messages = Files.readString(Path.of("src/main/resources/messages.yml"));
        List<String> missing = new ArrayList<>();
        for (LandFlag flag : LandFlag.values()) {
            if (!messages.contains(flag.key() + ":")) {
                missing.add(flag.key());
            }
        }
        assertThat(missing).as("these flags have no wording in messages.yml").isEmpty();
    }
}
