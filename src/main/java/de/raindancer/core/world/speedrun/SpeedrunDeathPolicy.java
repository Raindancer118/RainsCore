package de.raindancer.core.world.speedrun;

/**
 * Whether — and how — a death ends a speedrun lobby's run. A settings-storable wrapper around
 * {@link de.raindancer.core.world.speedrun.conditions.DeathEndCondition.DeathPolicy}: that enum has
 * no "off" of its own, because {@link SpeedrunLobby} either arms a {@code DeathEndCondition} or it
 * does not — and "not armed" cannot be one of the two values the condition itself understands.
 */
public enum SpeedrunDeathPolicy {
    /** A death does not end the run. */
    OFF,
    /** The first participant to die ends it for everybody. */
    ANY,
    /** Only once every participant has died. */
    ALL
}
