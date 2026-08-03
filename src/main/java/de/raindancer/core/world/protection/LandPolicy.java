package de.raindancer.core.world.protection;

/**
 * What the server owner has decided about claims, as the four questions the resolvers actually ask.
 *
 * <p>An interface rather than a settings class, and that is the point of it. {@link FlagRules} and
 * {@link Features} resolve a flag or a feature by merging the server's policy with the claim owner's
 * choice, and the merging is the part with the edge cases in it. Behind an interface, all of it is
 * testable without a config file, a data folder or a server — a fake policy is four methods.
 *
 * <p>It is also what keeps the settings <em>format</em> out of the land model. Where these answers come
 * from is a question for whoever wires the server up; a claim does not care whether it was a YAML file, an
 * annotated record or a menu somebody clicked in.
 */
public interface LandPolicy {

    /** Whether a flag is available to owners, forced either way, or not enforced at all. */
    FlagPolicy policy(LandFlag flag);

    /** The value a claim whose owner has never touched this flag gets. */
    boolean flagDefault(LandFlag flag);

}
