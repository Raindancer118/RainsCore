package de.raindancer.core.world.protection;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link LandPolicy} held in memory, starting from what each flag and feature says about itself.
 *
 * <p>The defaults are not written down here. {@link LandFlag#builtInDefault()} already carries them, next to the flag they belong to, and a second
 * list of the same values in this class would be the usual thing that drifts: somebody adds a flag,
 * forgets the list, and the flag defaults to whatever the map's absent value happens to be.
 *
 * <p>Only what somebody actually changed is stored, so {@link #changed()} is what a config file needs to
 * write — a file that spells out all twenty-six flags at their default values is a file nobody can read
 * for the two lines that matter.
 */
public final class LandPolicies implements LandPolicy {

    private final Map<LandFlag, FlagPolicy> flagPolicies = new EnumMap<>(LandFlag.class);
    private final Map<LandFlag, Boolean> flagDefaults = new EnumMap<>(LandFlag.class);

    /** Everything as the flags and features themselves say it should be. */
    public static LandPolicies builtIn() {
        return new LandPolicies();
    }

    @Override
    public FlagPolicy policy(LandFlag flag) {
        return flagPolicies.getOrDefault(flag, FlagPolicy.AVAILABLE);
    }

    /** @param policy null puts it back to what it would be with no config at all */
    public void policy(LandFlag flag, FlagPolicy policy) {
        if (policy == null || policy == FlagPolicy.AVAILABLE) {
            flagPolicies.remove(flag);
        } else {
            flagPolicies.put(flag, policy);
        }
    }

    @Override
    public boolean flagDefault(LandFlag flag) {
        Boolean changed = flagDefaults.get(flag);
        return changed == null ? flag.builtInDefault() : changed;
    }

    /** @param value null puts it back to the flag's own built-in default */
    public void flagDefault(LandFlag flag, Boolean value) {
        if (value == null || value == flag.builtInDefault()) {
            flagDefaults.remove(flag);
        } else {
            flagDefaults.put(flag, value);
        }
    }



    /** Whether anything at all differs from the built-in behaviour. */
    public boolean isUntouched() {
        return flagPolicies.isEmpty() && flagDefaults.isEmpty();
    }

    /** What a config file has to write down: only the decisions somebody actually made. */
    public Changed changed() {
        return new Changed(Map.copyOf(flagPolicies), Map.copyOf(flagDefaults));
    }

    /** Reads a stored set of decisions back, replacing whatever was here. */
    public void restore(Changed stored) {
        flagPolicies.clear();
        flagDefaults.clear();
        stored.flagPolicies().forEach(this::policy);
        stored.flagDefaults().forEach(this::flagDefault);
    }

    /** The decisions that differ from the defaults, as plain data a store can write. */
    public record Changed(Map<LandFlag, FlagPolicy> flagPolicies,
                          Map<LandFlag, Boolean> flagDefaults) {

        public boolean isEmpty() {
            return flagPolicies.isEmpty() && flagDefaults.isEmpty();
        }
    }

    /**
     * Reads the three keys a stored line can carry, ignoring anything it does not recognise.
     *
     * <p>Ignoring rather than refusing: a flag removed in a later version leaves its line behind in
     * somebody's file, and a config that fails to load over it would take every other flag with it.
     */
    public void set(String flagKey, String policyKey, Boolean defaultValue) {
        Optional<LandFlag> flag = LandFlag.byKey(flagKey);
        if (flag.isEmpty()) {
            return;
        }
        FlagPolicy.byKey(policyKey).ifPresent(policy -> policy(flag.get(), policy));
        if (defaultValue != null) {
            flagDefault(flag.get(), defaultValue);
        }
    }
}
