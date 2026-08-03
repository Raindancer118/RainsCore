package de.raindancer.core.data.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which {@link Topic} this setting appears under. Required: a setting with nowhere to live would be
 * reachable by command and invisible in the GUI, which is exactly the half-configurable state this
 * design exists to make impossible.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD,
        ElementType.FIELD})
public @interface In {
    String value();
}
