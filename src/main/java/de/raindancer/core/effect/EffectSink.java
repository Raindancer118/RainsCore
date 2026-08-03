package de.raindancer.core.effect;

import java.util.UUID;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>Everything else — what a cue means, whether it has been played too recently, whether it is
 * switched off — is bookkeeping and is tested without a server.
 */
public interface EffectSink {

    /** A sound only this player hears, from where they are. */
    void toPlayer(UUID player, SoundCue sound);

    /** Particles only this player sees, where they are. */
    void toPlayer(UUID player, ParticleCue particles);

    /** A sound at a place, for everybody near enough. */
    void atPlace(String world, double x, double y, double z, SoundCue sound);

    /** Particles at a place, for everybody who can see it. */
    void atPlace(String world, double x, double y, double z, ParticleCue particles);

    /**
     * Stops one sound a player is hearing.
     *
     * <p>Only sounds. Particles are drawn and gone — there is nothing to stop — and a call that
     * pretended otherwise would be one that silently does nothing.
     */
    void stopForPlayer(UUID player, String soundKey);

    /** Stops everything a player is hearing from the server. */
    void stopAllForPlayer(UUID player);
}
