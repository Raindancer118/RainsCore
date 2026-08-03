package de.raindancer.core.land;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link LandPolicy} held in memory, starting from what each flag and feature says about itself.
 *
 * <p>The defaults are not written down here. {@link ClaimFlag#builtInDefault()} and
 * {@link ClaimFeature#builtInDefault()} already carry them, next to the flag they belong to, and a second
 * list of the same values in this class would be the usual thing that drifts: somebody adds a flag,
 * forgets the list, and the flag defaults to whatever the map's absent value happens to be.
 *
 * <p>Only what somebody actually changed is stored, so {@link #changed()} is what a config file needs to
 * write — a file that spells out all twenty-six flags at their default values is a file nobody can read
 * for the two lines that matter.
 */
public final class LandPolicies implements LandPolicy {

    private final Map<ClaimFlag, FlagPolicy> flagPolicies = new EnumMap<>(ClaimFlag.class);
    private final Map<ClaimFlag, Boolean> flagDefaults = new EnumMap<>(ClaimFlag.class);
    private final Map<ClaimFeature, FeaturePolicy> featurePolicies = new EnumMap<>(ClaimFeature.class);

    /** Everything as the flags and features themselves say it should be. */
    public static LandPolicies builtIn() {
        return new LandPolicies();
    }

    @Override
    public FlagPolicy policy(ClaimFlag flag) {
        return flagPolicies.getOrDefault(flag, FlagPolicy.AVAILABLE);
    }

    /** @param policy null puts it back to what it would be with no config at all */
    public void policy(ClaimFlag flag, FlagPolicy policy) {
        if (policy == null || policy == FlagPolicy.AVAILABLE) {
            flagPolicies.remove(flag);
        } else {
            flagPolicies.put(flag, policy);
        }
    }

    @Override
    public boolean flagDefault(ClaimFlag flag) {
        Boolean changed = flagDefaults.get(flag);
        return changed == null ? flag.builtInDefault() : changed;
    }

    /** @param value null puts it back to the flag's own built-in default */
    public void flagDefault(ClaimFlag flag, Boolean value) {
        if (value == null || value == flag.builtInDefault()) {
            flagDefaults.remove(flag);
        } else {
            flagDefaults.put(flag, value);
        }
    }

    @Override
    public FeaturePolicy featurePolicy(ClaimFeature feature) {
        return featurePolicies.getOrDefault(feature, feature.builtInDefault());
    }

    @Override
    public void featurePolicy(ClaimFeature feature, FeaturePolicy policy) {
        if (policy == null || policy == feature.builtInDefault()) {
            featurePolicies.remove(feature);
        } else {
            featurePolicies.put(feature, policy);
        }
    }

    /** Whether anything at all differs from the built-in behaviour. */
    public boolean isUntouched() {
        return flagPolicies.isEmpty() && flagDefaults.isEmpty() && featurePolicies.isEmpty();
    }

    /** What a config file has to write down: only the decisions somebody actually made. */
    public Changed changed() {
        return new Changed(Map.copyOf(flagPolicies), Map.copyOf(flagDefaults),
                Map.copyOf(featurePolicies));
    }

    /** Reads a stored set of decisions back, replacing whatever was here. */
    public void restore(Changed stored) {
        flagPolicies.clear();
        flagDefaults.clear();
        featurePolicies.clear();
        stored.flagPolicies().forEach(this::policy);
        stored.flagDefaults().forEach(this::flagDefault);
        stored.featurePolicies().forEach(this::featurePolicy);
    }

    /** The decisions that differ from the defaults, as plain data a store can write. */
    public record Changed(Map<ClaimFlag, FlagPolicy> flagPolicies,
                          Map<ClaimFlag, Boolean> flagDefaults,
                          Map<ClaimFeature, FeaturePolicy> featurePolicies) {

        public boolean isEmpty() {
            return flagPolicies.isEmpty() && flagDefaults.isEmpty() && featurePolicies.isEmpty();
        }
    }

    /**
     * Reads the three keys a stored line can carry, ignoring anything it does not recognise.
     *
     * <p>Ignoring rather than refusing: a flag removed in a later version leaves its line behind in
     * somebody's file, and a config that fails to load over it would take every other flag with it.
     */
    public void set(String flagKey, String policyKey, Boolean defaultValue) {
        Optional<ClaimFlag> flag = ClaimFlag.byKey(flagKey);
        if (flag.isEmpty()) {
            return;
        }
        FlagPolicy.byKey(policyKey).ifPresent(policy -> policy(flag.get(), policy));
        if (defaultValue != null) {
            flagDefault(flag.get(), defaultValue);
        }
    }
}
