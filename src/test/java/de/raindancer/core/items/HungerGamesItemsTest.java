package de.raindancer.core.items;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Can this actually express the items it was built for?
 *
 * <h2>Why this test exists</h2>
 * The item system was written against a real one: the thirteen custom items in
 * {@code TheHungerGames}, which today live in a 1120-line enum where each is a constant, a branch of
 * a {@code switch}, and its own static map of per-player state. Building a general system and
 * <em>then</em> discovering it cannot express the thing it was generalised from is the classic way
 * to waste a week, so this rebuilds a representative handful through the new API and checks the
 * behaviour comes out the same.
 *
 * <p>Representative, not exhaustive: what is covered is one of each <em>shape</em> — a plain
 * cooldown ability, a single-use consumable, a passive that fires on lethal damage, one that can
 * decline, a thrown one, and a craftable one. The effects themselves are the plugin's business.
 */
class HungerGamesItemsTest {

    private static final UUID PLAYER = UUID.nameUUIDFromBytes("tribute".getBytes());

    private AtomicLong clock;
    private ItemAbilities abilities;
    private CustomItems items;
    private List<String> happened;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        abilities = new ItemAbilities(clock::get);
        items = new CustomItems(java.nio.file.Path.of("/tmp/unused-items.yml"));
        happened = new ArrayList<>();
    }

    /** Hermes' Stiefel: right-click to fly for four seconds, with a cooldown. */
    @Test
    @DisplayName("an ability on a cooldown — Hermes' boots")
    void plainCooldownAbility() {
        abilities.register(ItemAbility.builder("hg", "hermes-boots")
                .on(ItemTrigger.RIGHT_CLICK)
                .cooldown(Duration.ofSeconds(45))
                .describedAs("Rechtsklick: fliege bis zu 4 Sekunden.")
                .does(use -> happened.add("flew"))
                .build());
        items.define(CustomItem.builder("hg", "hermes-boots")
                .material(Material.FEATHER)
                .name("<yellow>Hermes' Stiefel")
                .lore(List.of("<gray>Rechtsklick: fliege bis zu 4 Sekunden."))
                .glowing(true)
                .ability("hg:hermes-boots")
                .build());

        assertThat(abilities.use(PLAYER, "hg:hermes-boots", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        assertThat(abilities.use(PLAYER, "hg:hermes-boots", ItemTrigger.RIGHT_CLICK).outcome())
                .isEqualTo(UseOutcome.ON_COOLDOWN);

        clock.addAndGet(Duration.ofSeconds(46).toMillis());
        assertThat(abilities.use(PLAYER, "hg:hermes-boots", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        assertThat(happened).containsExactly("flew", "flew");
    }

    /**
     * The smoke bomb: used once and gone. Their enum expresses this by returning {@code true} from
     * {@code use()} meaning "consume the item"; here it is one charge, and
     * {@link UseResult#itemIsSpent()} is the same answer.
     */
    @Test
    @DisplayName("a single-use item — the smoke bomb")
    void singleUseItem() {
        abilities.register(ItemAbility.builder("hg", "smoke-bomb")
                .on(ItemTrigger.RIGHT_CLICK)
                .charges(1)
                .does(use -> happened.add("smoke"))
                .build());

        UseResult first = abilities.use(PLAYER, "hg:smoke-bomb", ItemTrigger.RIGHT_CLICK);
        assertThat(first.ran()).isTrue();
        assertThat(first.itemIsSpent())
                .as("their use() returns true here, meaning take the item away")
                .isTrue();

        assertThat(abilities.use(PLAYER, "hg:smoke-bomb", ItemTrigger.RIGHT_CLICK).outcome())
                .isEqualTo(UseOutcome.NO_CHARGES);
        assertThat(happened).hasSize(1);
    }

    /**
     * Trottel-Schutz: passive, saves the holder once from lethal environmental damage, consumed
     * doing it — and must not save them from a player kill, which is why the ability decides and
     * says so rather than being fired unconditionally.
     */
    @Test
    @DisplayName("a passive that fires on lethal damage — the stupidness protector")
    void passiveOnLethalDamage() {
        boolean[] environmental = {true};
        abilities.register(ItemAbility.builder("hg", "stupidness-protector")
                .on(ItemTrigger.LETHAL_DAMAGE)
                .charges(1)
                .describedAs("Rettet dich einmal vor tödlichem Umweltschaden.")
                .attempts(use -> {
                    if (!environmental[0]) {
                        return false;
                    }
                    happened.add("saved");
                    return true;
                })
                .build());

        // A player kill: it declines, and is therefore not used up.
        environmental[0] = false;
        assertThat(abilities.use(PLAYER, "hg:stupidness-protector", ItemTrigger.LETHAL_DAMAGE)
                .outcome()).isEqualTo(UseOutcome.DECLINED);
        assertThat(happened).isEmpty();

        // Lava: it fires, and is gone.
        environmental[0] = true;
        UseResult saved = abilities.use(PLAYER, "hg:stupidness-protector",
                ItemTrigger.LETHAL_DAMAGE);
        assertThat(saved.ran()).isTrue();
        assertThat(saved.itemIsSpent()).isTrue();
        assertThat(happened).containsExactly("saved");
    }

    /** The grappling hook: aimed at nothing, it costs neither a use nor a cooldown. */
    @Test
    @DisplayName("an ability that can miss — the grappling hook")
    void anAbilityThatCanMiss() {
        boolean[] hasTarget = {false};
        abilities.register(ItemAbility.builder("hg", "grappling-hook")
                .on(ItemTrigger.RIGHT_CLICK)
                .cooldown(Duration.ofSeconds(5))
                .attempts(use -> {
                    if (!hasTarget[0]) {
                        return false;
                    }
                    happened.add("pulled");
                    return true;
                })
                .build());

        assertThat(abilities.use(PLAYER, "hg:grappling-hook", ItemTrigger.RIGHT_CLICK).outcome())
                .isEqualTo(UseOutcome.DECLINED);
        assertThat(abilities.remaining(PLAYER, "hg:grappling-hook"))
                .as("a shot at the sky must not start a five-second cooldown")
                .isEmpty();

        hasTarget[0] = true;
        assertThat(abilities.use(PLAYER, "hg:grappling-hook", ItemTrigger.RIGHT_CLICK).ran()).isTrue();
        assertThat(abilities.remaining(PLAYER, "hg:grappling-hook")).isPresent();
    }

    /** Krückauwasser: a splash potion, so the effect happens where it lands, not where it was held. */
    @Test
    @DisplayName("a thrown item — Krückauwasser")
    void thrownItem() {
        abilities.register(ItemAbility.builder("hg", "krueckauwasser")
                .on(ItemTrigger.PROJECTILE_HIT)
                .does(use -> happened.add("splashed"))
                .build());

        assertThat(abilities.use(PLAYER, "hg:krueckauwasser", ItemTrigger.RIGHT_CLICK).outcome())
                .as("throwing it is vanilla; the ability belongs to the landing")
                .isEqualTo(UseOutcome.WRONG_TRIGGER);
        assertThat(abilities.use(PLAYER, "hg:krueckauwasser", ItemTrigger.PROJECTILE_HIT).ran())
                .isTrue();
    }

    /**
     * The Exmatrikulator: the expensive craftable one. Its recipe lives in the config as three rows
     * of block names, which is exactly what their {@code registerRecipes} reads today.
     */
    @Test
    @DisplayName("a craftable item — the Exmatrikulator")
    void craftableItem() {
        CustomItem exmatrikulator = CustomItem.builder("hg", "exmatrikulator")
                .material(Material.BREEZE_ROD)
                .name("<light_purple>Exmatrikulator")
                .ability("hg:exmatrikulator")
                .recipe(List.of(
                        "LIGHTNING_ROD DIAMOND_BLOCK LIGHTNING_ROD",
                        "NETHERITE_INGOT DIAMOND_BLOCK NETHERITE_INGOT",
                        "LIGHTNING_ROD DIAMOND_BLOCK LIGHTNING_ROD"))
                .build();

        assertThat(exmatrikulator.isCraftable()).isTrue();
        assertThat(exmatrikulator.recipe()).hasSize(3);
        assertThat(exmatrikulator.ability()).contains("hg:exmatrikulator");
    }

    @Test
    @DisplayName("an item with no ability is just an item")
    void plainItem() {
        assertThat(CustomItem.builder("hg", "token").material(Material.PAPER).build().ability())
                .isEmpty();
    }

    /**
     * The shape their whole enum has: everything about an item is one declaration, and the plugin
     * writes only the effect.
     */
    @Test
    @DisplayName("an item is one declaration, however much it does")
    void everythingInOneDeclaration() {
        CustomItem medikit = CustomItem.builder("hg", "medikit")
                .material(Material.GLISTERING_MELON_SLICE)
                .name("<red>Medikit")
                .lore(List.of("<gray>Vollheilung nach kurzem Countdown.",
                        "<gray>Schaden bricht ab."))
                .glowing(true)
                .ability("hg:medikit")
                .tag("countdown-seconds", "5")
                .build();

        items.define(medikit);

        CustomItem stored = items.byKey("hg:medikit").orElseThrow();
        assertThat(stored.material()).isEqualTo(Material.GLISTERING_MELON_SLICE);
        assertThat(stored.lore()).hasSize(2);
        assertThat(stored.isGlowing()).isTrue();
        assertThat(stored.ability()).contains("hg:medikit");
        assertThat(stored.tag("countdown-seconds")).contains("5");
    }
}
