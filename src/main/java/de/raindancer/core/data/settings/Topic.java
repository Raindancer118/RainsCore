package de.raindancer.core.data.settings;

import org.bukkit.Material;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One page or submenu of the settings GUI.
 *
 * <p>Declared on the settings record, referred to by {@link In}. The path is a trail under one of
 * the three standard roots: {@code "management"}, {@code "management/fences"}. A path whose parent
 * has not been declared gets one made for it, so declaring only the leaf is allowed and common.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Topic {

    /** {@code "management/fences"} — slashes, lower case, under a standard root. */
    String path();

    /** What the button and the window say. */
    String title();

    /** The button. Falls back to the parent's icon, and finally to the root's. */
    Material icon() default Material.AIR;

    /** One sentence under the title, phrased for whoever is allowed to change these. */
    String description() default "";
}
