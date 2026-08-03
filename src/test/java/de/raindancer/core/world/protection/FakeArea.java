package de.raindancer.core.world.protection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A piece of protected ground with nothing behind it.
 *
 * <p>Which is the point of {@link ProtectedArea} being an interface: Core's protection can be tested without a
 * claim, a claim store, a world or a server. If this class needed to be complicated, the interface would be
 * asking Core to know too much.
 */
final class FakeArea implements ProtectedArea {

    private final String id;
    private final String name;
    private final List<UUID> owners = new ArrayList<>();
    private final Map<UUID, LandAudience> standings = new HashMap<>();
    private final Map<UUID, List<LandAction>> granted = new HashMap<>();
    private final Map<LandFlag, Map<LandAudience, Boolean>> overrides = new EnumMap<>(LandFlag.class);

    FakeArea(String id, String name) {
        this.id = id;
        this.name = name;
    }

    static FakeArea named(String name) {
        return new FakeArea(name, name);
    }

    FakeArea ownedBy(UUID who) {
        owners.add(who);
        standings.put(who, LandAudience.OWNER);
        return this;
    }

    FakeArea trusting(UUID who, LandAction... actions) {
        standings.put(who, LandAudience.TRUSTED);
        granted.put(who, List.of(actions));
        return this;
    }

    FakeArea with(LandFlag flag, LandAudience audience, boolean value) {
        overrides.computeIfAbsent(flag, key -> new EnumMap<>(LandAudience.class)).put(audience, value);
        return this;
    }

    /** Forgets an override, so the server default gets its say again. */
    FakeArea clear(LandFlag flag) {
        overrides.remove(flag);
        return this;
    }

    FakeArea with(LandFlag flag, boolean value) {
        for (LandAudience audience : LandAudience.values()) {
            with(flag, audience, value);
        }
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<UUID> owners() {
        return List.copyOf(owners);
    }

    @Override
    public Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience) {
        Map<LandAudience, Boolean> forFlag = overrides.get(flag);
        return forFlag == null ? Optional.empty() : Optional.ofNullable(forFlag.get(audience));
    }

    @Override
    public LandAudience audienceOf(UUID who) {
        return standings.getOrDefault(who, LandAudience.VISITOR);
    }

    @Override
    public boolean may(UUID who, LandAction action) {
        if (owners.contains(who)) {
            return true;
        }
        return granted.getOrDefault(who, List.of()).contains(action);
    }
}
