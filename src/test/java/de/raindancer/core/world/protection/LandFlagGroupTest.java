package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every flag is filed exactly once, and that the filing is visible.
 *
 * <p>The grouping exists so an owner looking for "stop the creepers" opens <em>Creatures</em> and reads five
 * toggles rather than reading twenty-six. That only holds while the groups are complete and disjoint, and both
 * fail quietly: a new flag lands nowhere and disappears from the screens, or lands in two places and toggling it
 * in one does not update the other.
 */
class LandFlagGroupTest {

    @Test
    @DisplayName("every flag is in a group")
    void nothingIsLeftUnfiled() {
        assertThat(LandFlagGroup.ungrouped())
                .as("these flags are in no group, so they show up under 'everything else' — which works, but "
                        + "means somebody added a flag and did not decide where it belongs")
                .isEmpty();
    }

    @Test
    @DisplayName("no flag is in two groups")
    void nothingIsFiledTwice() {
        Set<LandFlag> seen = new HashSet<>();
        List<LandFlag> twice = new ArrayList<>();
        for (LandFlagGroup group : LandFlagGroup.values()) {
            if (group == LandFlagGroup.OTHER) {
                continue;
            }
            for (LandFlag flag : group.flags()) {
                if (!seen.add(flag)) {
                    twice.add(flag);
                }
            }
        }
        assertThat(twice)
                .as("a flag in two groups is a toggle that appears twice and only updates one of them")
                .isEmpty();
    }

    @Test
    @DisplayName("every flag can be asked which group it is in")
    void everyFlagKnowsItsGroup() {
        for (LandFlag flag : LandFlag.values()) {
            assertThat(LandFlagGroup.of(flag)).as("%s", flag).isNotNull();
        }
    }

    @Test
    @DisplayName("the groups shown are the ones with something in them")
    void emptyGroupsAreNotShown() {
        // An empty group is a button that opens a page saying nothing, which is worse than one button fewer.
        for (LandFlagGroup group : LandFlagGroup.occupied()) {
            assertThat(group.flags()).as("%s is listed and empty", group).isNotEmpty();
        }
    }

    @Test
    @DisplayName("every group has wording shipped for it")
    void noGroupRendersAsItsOwnKey() throws IOException {
        String messages = Files.readString(Path.of("src/main/resources/messages.yml"));
        List<String> missing = new ArrayList<>();
        for (LandFlagGroup group : LandFlagGroup.values()) {
            if (!messages.contains(group.key() + ": {name:")) {
                missing.add(group.key());
            }
        }
        assertThat(missing).as("these groups have no wording, so their button reads as a message key").isEmpty();
    }

    @Test
    @DisplayName("the groups between them hold every flag there is")
    void theGroupsAddUp() {
        List<LandFlag> filed = new ArrayList<>();
        for (LandFlagGroup group : LandFlagGroup.values()) {
            filed.addAll(group.flags());
        }
        assertThat(filed).containsExactlyInAnyOrder(LandFlag.values());
    }
}
