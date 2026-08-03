package de.raindancer.core.world.protection;

import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person may do to the creatures and the floor of somebody else's ground.
 *
 * <h2>What was missing</h2>
 * Everything here was a <em>permission</em> and nothing here was a flag. {@code DAMAGE_ANIMALS} and
 * {@code ANIMALS} are grants handed to named people, which answers "may Bob touch the cows" and cannot answer
 * "may visitors touch the cows" without editing every grant on the claim. And three of these had no answer at
 * all: nothing stopped a stranger fighting monsters in a spawn, and nothing stopped them taking the items or the
 * experience off the floor of a shop — orbs are not items, so even the item permission left the levels free.
 *
 * <h2>The tier is the attacker's</h2>
 * The sharp edge, and the one difference from every other audience-aware flag. PvP protects the person being hit,
 * so the <em>victim's</em> standing decides. These restrain the person swinging, so <em>theirs</em> does — which
 * is what makes "I may cull my own pigs, visitors may not" one setting rather than a contradiction.
 */
class HittingAndTakingTest {

    private static final Path PROTECTION = Path.of("src/main/java/de/raindancer/core/world/protection");

    private final LandPolicies policies = LandPolicies.builtIn();
    private final FlagRules flags = new FlagRules(policies);
    private final UUID owner = UUID.randomUUID();

    private FakeArea area() {
        return FakeArea.named("somebody's farm").ownedBy(owner);
    }

    /**
     * A creature with nothing behind it but a type.
     *
     * <p>Same trick as {@link FakePlayer} and for the same reason: the classifier asks {@code instanceof} and
     * {@code getType()}, and hand-writing the other three hundred methods of {@link org.bukkit.entity.Mob} is how
     * the classification ends up untested.
     */
    private static <T extends Entity> T fake(Class<T> kind, EntityType type) {
        return kind.cast(Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[]{kind},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "toString" -> "a fake " + type;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                }));
    }

    private static String read(String file) {
        try {
            return Files.readString(PROTECTION.resolve(file));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    /** The handler body starting at {@code signature}, up to the next thing in the file. */
    private static String handler(String file, String signature) {
        String body = read(file);
        int at = body.indexOf(signature);
        assertThat(at).as("%s in %s — renamed?", signature, file).isNotNegative();
        int next = body.indexOf("\n    @EventHandler", at);
        return body.substring(at, next < 0 ? body.length() : next);
    }

    @Nested
    @DisplayName("which flag a creature falls under")
    class Classification {

        @Test
        @DisplayName("a monster is judged by the monster flag")
        void monstersAreTheirOwnFlag() {
            assertThat(InteractionClassifier.forCreatureDamage(fake(Zombie.class, EntityType.ZOMBIE)))
                    .isEqualTo(LandFlag.HIT_MONSTERS);
        }

        @Test
        @DisplayName("the hostiles that are not Monsters count too")
        void theAwkwardHostilesAreNotForgotten() {
            // Neither implements Monster. Read as livestock, a claim protecting its animals would have stopped
            // people defending themselves from a ghast.
            assertThat(InteractionClassifier.forCreatureDamage(fake(Ghast.class, EntityType.GHAST)))
                    .isEqualTo(LandFlag.HIT_MONSTERS);
            assertThat(InteractionClassifier.forCreatureDamage(fake(Slime.class, EntityType.SLIME)))
                    .isEqualTo(LandFlag.HIT_MONSTERS);
        }

        @Test
        @DisplayName("anything else alive is livestock, villagers included")
        void everythingElseAliveIsTheMobFlag() {
            assertThat(InteractionClassifier.forCreatureDamage(fake(Cow.class, EntityType.COW)))
                    .isEqualTo(LandFlag.HIT_MOBS);
            assertThat(InteractionClassifier.forCreatureDamage(fake(Villager.class, EntityType.VILLAGER)))
                    .isEqualTo(LandFlag.HIT_MOBS);
        }

        @Test
        @DisplayName("a player is neither — PvP has that question")
        void playersAreLeftToPvp() {
            assertThat(InteractionClassifier.forCreatureDamage(fake(Player.class, EntityType.PLAYER))).isNull();
        }

        @Test
        @DisplayName("and nothing that is not alive is caught by accident")
        void furnitureIsNotACreature() {
            // Item frames and boats have permissions of their own; answering a creature flag for them would
            // quietly take those over.
            assertThat(InteractionClassifier.forCreatureDamage(fake(ItemFrame.class, EntityType.ITEM_FRAME)))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("what the flags resolve to")
    class Resolution {

        @Test
        @DisplayName("all five are allowed until somebody says otherwise")
        void nothingChangesOnAnUntouchedServer() {
            for (LandFlag flag : newFlags()) {
                assertThat(flags.isAllowed(area(), flag, LandAudience.VISITOR))
                        .as("%s on ground nobody has configured", flag)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("an owner keeps what they take away from visitors")
        void theTierIsSetSeparately() {
            for (LandFlag flag : newFlags()) {
                FakeArea farm = area()
                        .with(flag, LandAudience.OWNER, true)
                        .with(flag, LandAudience.VISITOR, false);

                assertThat(flags.isAllowed(farm, flag, LandAudience.OWNER)).as("owner, %s", flag).isTrue();
                assertThat(flags.isAllowed(farm, flag, LandAudience.VISITOR)).as("visitor, %s", flag).isFalse();
            }
        }

        @Test
        @DisplayName("every one of them is per tier, or the setting above is a lie")
        void allOfThemAreAudienceAware() {
            for (LandFlag flag : newFlags()) {
                assertThat(flag.audienceAware()).as("%s", flag).isTrue();
            }
        }

        @Test
        @DisplayName("a server may forbid the lot outright")
        void aServerCanForceThemOff() {
            for (LandFlag flag : newFlags()) {
                policies.policy(flag, FlagPolicy.FORCED_OFF);
                assertThat(flags.isAllowed(area().with(flag, true), flag, LandAudience.OWNER))
                        .as("%s forced off beats the owner's own switch", flag)
                        .isFalse();
            }
        }

        private LandFlag[] newFlags() {
            return new LandFlag[]{LandFlag.HIT_MOBS, LandFlag.HIT_MONSTERS, LandFlag.INTERACT_MOBS,
                    LandFlag.ITEM_PICKUP, LandFlag.XP_PICKUP};
        }
    }

    @Nested
    @DisplayName("where they are enforced")
    class Enforcement {

        @Test
        @DisplayName("a blow at a creature consults the flag before the permission")
        void damageIsJudged() {
            String onDamage = handler("InteractionProtectionListener.java",
                    "public void onDamage(EntityDamageByEntityEvent");
            assertThat(onDamage)
                    .as("without this a claim can only protect creatures person by person")
                    .contains("mayHarm(");
        }

        @Test
        @DisplayName("so does a potion thrown at one")
        void potionsCannotWalkPastIt() {
            String body = read("InteractionProtectionListener.java");
            int at = body.indexOf("private boolean mayBeSplashed(");
            assertThat(at).isNotNegative();
            // Harming II over the fence produces no damage event naming the thrower, which is how this exact
            // hole was found the first time.
            assertThat(body.substring(at, at + 900)).contains("forCreatureDamage(");
        }

        @Test
        @DisplayName("shearing, milking and saddling go through the interaction flag")
        void handlingIsJudged() {
            assertThat(handler("InteractionProtectionListener.java",
                    "public void onShear(PlayerShearEntityEvent"))
                    .contains("mayInteractWith(");
            assertThat(handler("InteractionProtectionListener.java",
                    "public void onEntityInteract(PlayerInteractEntityEvent"))
                    .contains("mayInteractWith(");
            assertThat(handler("InteractionProtectionListener.java",
                    "public void onEntityInteractAt(PlayerInteractAtEntityEvent"))
                    .contains("mayInteractWith(");
        }

        @Test
        @DisplayName("items and orbs are both stopped, and quietly")
        void pickupsAreJudgedWithoutChat() {
            String items = handler("InteractionProtectionListener.java",
                    "public void onPickup(EntityPickupItemEvent");
            String orbs = handler("InteractionProtectionListener.java",
                    "public void onExperiencePickup(");

            assertThat(items).contains("LandFlag.ITEM_PICKUP");
            assertThat(orbs).contains("LandFlag.XP_PICKUP");
            // Both fire every tick while somebody stands over what they may not have. A line per tick is worse
            // than the pile simply staying where it is.
            assertThat(items).doesNotContain("refuse(");
            assertThat(orbs).doesNotContain("refuse(");
        }

        @Test
        @DisplayName("a refused blow says why, once")
        void refusalsAreExplained() {
            String body = read("InteractionProtectionListener.java");
            assertThat(body).contains("land.hit-mobs-refused")
                    .contains("land.hit-monsters-refused")
                    .contains("land.interact-mobs-refused");
        }

        @Test
        @DisplayName("one answer to what counts as hostile, shared by everything that asks")
        void hostilityIsDecidedInOnePlace() {
            // Three listeners ask — spawning, entry, hitting — and a copy each is how a hoglin ends up hostile
            // in one and livestock in another.
            assertThat(read("MobControlListener.java"))
                    .contains("InteractionClassifier.isHostile")
                    .as("the copy that used to live here").doesNotContain("EntityType.MAGMA_CUBE");
        }
    }
}
