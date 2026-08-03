package de.raindancer.core.land;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a claim written by an old version still comes back whole.
 * <p>
 * Every release so far has read older files by recognising their keys, but nothing checked it: the
 * evidence was a comment in the loader and a pair of tests on two isolated helpers. A server upgrading
 * from 1.3.0 is putting years of somebody's building behind a format the current code has never been
 * asked to read end to end, and the failure mode is not an exception — it is a claim that loads with the
 * wrong people trusted, or a flag quietly flipped.
 * <p>
 * The fixtures below are the real shape 1.3.0–1.5.3 wrote, taken from those tags. Two kinds of field are
 * left out because reading them needs a live server rather than because they are uninteresting: anything
 * item-valued (icon, bank contents, cost item), which goes through {@code ItemStack} deserialisation, and
 * the fence block, whose material is validated against {@code Tag.FENCES}. Neither has changed shape
 * since 1.3.0; what did change — flags, who a perk serves, the split payment figures — is what is pinned
 * here.
 */
class LegacyClaimFormatTest {

    private static final UUID CLAIM_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID WORLD_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID FRIEND = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private static Claim load(String yaml) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (InvalidConfigurationException malformed) {
            throw new AssertionError("the fixture is not valid YAML", malformed);
        }
        return ClaimStorage.fromYaml(parsed, "fixture.yml");
    }

    /** A complete claim exactly as 1.3.0 wrote one, minus the item-valued fields. */
    private static String claimAsWrittenBy130() {
        return """
                id: 11111111-2222-3333-4444-555555555555
                name: Ahornhof
                world-id: 66666666-7777-8888-9999-000000000000
                world-name: world
                created-at: 1700000000000
                shape:
                  vertices:
                  - 10,10
                  - 30,10
                  - 30,30
                  - 10,30
                  min-y: -64
                  max-y: 200
                owners:
                - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                public-permissions:
                - doors
                members:
                  12345678-1234-1234-1234-123456789abc:
                    permissions:
                    - build
                    - break
                    admin-permissions:
                    - manage_members
                    grantable:
                    - build
                    added-at: 1700000001000
                bans:
                  99999999-9999-9999-9999-999999999999:
                    issued-by: aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                    issued-at: 1700000002000
                    expires-at: 0
                    reason: griefing
                flags:
                  pvp: false
                  monster-spawning: true
                titles:
                  fade-in: 5
                  stay: 30
                  fade-out: 8
                  enter:
                    title:
                      text: Welcome
                      color: gold
                      bold: true
                entry-fee:
                  enabled: true
                  type: xp-points
                  amount: 7
                  pass-seconds: 600
                pantry:
                  enabled: true
                  feed-visitors: false
                  threshold: 6
                  allow-deposits: true
                equipment:
                  enabled: true
                  equip-visitors: true
                effects-enabled: true
                atmosphere:
                  weather: clear
                  time-preset: noon
                  time-ticks: -1
                paid:
                  type: xp-points
                  amount: 400
                  area: 441
                """;
    }

    @Nested
    @DisplayName("A claim file from 1.3.0")
    class From130 {

        @Test
        @DisplayName("loads at all, with its identity and shape intact")
        void identityAndShape() {
            Claim claim = load(claimAsWrittenBy130());

            assertThat(claim).isNotNull();
            assertThat(claim.id()).isEqualTo(CLAIM_ID);
            assertThat(claim.name()).isEqualTo("Ahornhof");
            assertThat(claim.worldId()).isEqualTo(WORLD_ID);
            assertThat(claim.worldName()).isEqualTo("world");
            assertThat(claim.createdAt()).isEqualTo(1700000000000L);
            assertThat(claim.shape().vertices()).hasSize(4);
            assertThat(claim.shape().minY()).isEqualTo(-64);
            assertThat(claim.shape().maxY()).isEqualTo(200);
            assertThat(claim.shape().areaBlocks()).isEqualTo(441);
        }

        @Test
        @DisplayName("keeps its owner, its trusted player and everything that player was given")
        void people() {
            Claim claim = load(claimAsWrittenBy130());

            assertThat(claim.owners()).containsExactly(OWNER);
            assertThat(claim.members()).containsOnlyKeys(FRIEND);
            assertThat(claim.members().get(FRIEND).permissions())
                    .containsExactlyInAnyOrder(ClaimPermission.BUILD, ClaimPermission.BREAK);
            assertThat(claim.members().get(FRIEND).adminPermissions()).isNotEmpty();
            assertThat(claim.members().get(FRIEND).grantablePermissions())
                    .containsExactly(ClaimPermission.BUILD);
            assertThat(claim.publicPermissions()).containsExactly(ClaimPermission.DOORS);
        }

        @Test
        @DisplayName("keeps whoever was banned, and why")
        void bans() {
            Claim claim = load(claimAsWrittenBy130());

            UUID banned = UUID.fromString("99999999-9999-9999-9999-999999999999");
            assertThat(claim.bans()).containsKey(banned);
            assertThat(claim.bans().get(banned).reason()).isEqualTo("griefing");
            assertThat(claim.bans().get(banned).expiresAt()).isZero();
        }

        @Test
        @DisplayName("spreads its single flag value across all three groups")
        void flagsBecomePerAudience() {
            Claim claim = load(claimAsWrittenBy130());

            // "pvp: false" was one switch for everybody, so all three groups must read false — anything
            // else would quietly re-enable PvP for someone the owner had switched it off for.
            for (ClaimAudience audience : ClaimAudience.values()) {
                assertThat(claim.flagOverride(ClaimFlag.PVP, audience))
                        .as("pvp for " + audience)
                        .contains(false);
            }
        }

        @Test
        @DisplayName("turns \"feed visitors: no\" into owners and trusted, and nothing else")
        void pantryAudience() {
            Claim claim = load(claimAsWrittenBy130());

            assertThat(claim.featureAudiences(ClaimFeature.PANTRY))
                    .isEqualTo(EnumSet.of(ClaimAudience.OWNER, ClaimAudience.TRUSTED));
        }

        @Test
        @DisplayName("keeps its titles, fee, atmosphere, fence and what was paid for it")
        void theRest() {
            Claim claim = load(claimAsWrittenBy130());

            assertThat(claim.titles().fadeInTicks()).isEqualTo(5);
            assertThat(claim.titles().stayTicks()).isEqualTo(30);
            assertThat(claim.titles().enterTitle().raw()).isEqualTo("Welcome");
            assertThat(claim.titles().enterTitle().bold()).isTrue();

            assertThat(claim.entryFee().enabled()).isTrue();
            assertThat(claim.entryFee().type()).isEqualTo(CostType.XP_POINTS);
            assertThat(claim.entryFee().amount()).isEqualTo(7);
            assertThat(claim.entryFee().passDurationSeconds()).isEqualTo(600);

            assertThat(claim.atmosphere().isActive()).isTrue();

            assertThat(claim.paidCostType()).isEqualTo(CostType.XP_POINTS);
            assertThat(claim.paidAmount()).isEqualTo(400);
            assertThat(claim.paidArea()).isEqualTo(441);
            // 1.3.0 had one figure where there are now two; the invested amount has to start equal to it,
            // or the first resize would refund against zero.
            assertThat(claim.settledAmount()).isEqualTo(400);
        }

        @Test
        @DisplayName("is marked for rewriting, so the upgrade lands on disk once")
        void isScheduledForRewrite() {
            assertThat(load(claimAsWrittenBy130()).dirty())
                    .as("an old file must be rewritten in the current format")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("A claim file this version wrote")
    class Current {

        @Test
        @DisplayName("is left alone rather than rewritten on every start")
        void currentFilesAreNotDirty() {
            Claim claim = load("""
                    data-version: 2
                    id: 11111111-2222-3333-4444-555555555555
                    name: Ahornhof
                    world-id: 66666666-7777-8888-9999-000000000000
                    world-name: world
                    shape:
                      vertices:
                      - 10,10
                      - 30,10
                      - 30,30
                      - 10,30
                      min-y: -64
                      max-y: 200
                    owners:
                    - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                    feature-audiences:
                      pantry:
                      - owner
                    """);

            assertThat(claim).isNotNull();
            assertThat(claim.dirty()).isFalse();
            assertThat(claim.featureAudiences(ClaimFeature.PANTRY))
                    .isEqualTo(EnumSet.of(ClaimAudience.OWNER));
        }

        @Test
        @DisplayName("keeps a per-group flag exactly as it was set")
        void perAudienceFlagsSurvive() {
            Claim claim = load("""
                    data-version: 2
                    id: 11111111-2222-3333-4444-555555555555
                    world-id: 66666666-7777-8888-9999-000000000000
                    shape:
                      vertices:
                      - 10,10
                      - 30,10
                      - 30,30
                      - 10,30
                      min-y: 0
                      max-y: 100
                    owners:
                    - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                    flags:
                      pvp:
                        owner: true
                        visitor: false
                    """);

            assertThat(claim).isNotNull();
            assertThat(claim.flagOverride(ClaimFlag.PVP, ClaimAudience.OWNER)).contains(true);
            assertThat(claim.flagOverride(ClaimFlag.PVP, ClaimAudience.VISITOR)).contains(false);
            assertThat(claim.flagOverride(ClaimFlag.PVP, ClaimAudience.TRUSTED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("A file that is not a claim")
    class Broken {

        @Test
        @DisplayName("is skipped rather than taking the whole load down")
        void missingIdIsSkipped() {
            assertThat(load("name: nameless\n")).isNull();
        }

        @Test
        @DisplayName("with too few corners is skipped, because it cannot enclose anything")
        void degenerateShapeIsSkipped() {
            assertThat(load("""
                    id: 11111111-2222-3333-4444-555555555555
                    world-id: 66666666-7777-8888-9999-000000000000
                    shape:
                      vertices:
                      - 10,10
                      - 30,10
                      min-y: 0
                      max-y: 10
                    owners:
                    - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                    """)).isNull();
        }
    }
}
