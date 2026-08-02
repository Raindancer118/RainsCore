package de.raindancer.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The path this setting has in {@code config.yml}, when it must not be the one derived from the
 * component name.
 *
 * <p>The reason it exists is migration: a server already running has
 * {@code gameplay.remove-phantoms} in its file, and renaming that to {@code remove-phantoms}
 * because the record component is called {@code removePhantoms} would silently reset the setting on
 * every server that upgrades. Naming the old key keeps the file working untouched.
 *
 * <p>Dots nest, as everywhere else in YAML.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD,
        ElementType.FIELD})
public @interface Key {
    String value();
}
