package de.raindancer.core.settings;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of the settings tree: either a submenu of other topics, or a page of settings.
 *
 * <h2>Why a tree and not a list of groups</h2>
 * The thing that made the old menu unusable was that every setting on the server was one click deep.
 * Sixty buttons on a flat set of pages is not a menu, it is a wall — you find a setting by reading
 * all of them. A tree lets a page hold seven related things and a menu hold seven pages, so getting
 * to any one setting is three deliberate clicks instead of one scan.
 *
 * <p>Nodes are built by {@link SettingsTopics} while a schema is being read, and are not touched
 * again once it is finished. A node made as somebody's ancestor can be re-described up to that
 * point — a plugin may declare {@code config/limits/claims} after another plugin has already caused
 * {@code config/limits} to exist — which is why the look is not final.
 */
public final class SettingsTopic {

    private final String path;
    private String title;
    private Material icon;
    private String description;
    private final SettingsTopic parent;
    private final boolean declared;
    private final List<SettingsTopic> children = new ArrayList<>();
    private final List<Setting<?>> settings = new ArrayList<>();

    SettingsTopic(String path, String title, Material icon, String description,
                  SettingsTopic parent, boolean declared) {
        this.path = path;
        this.title = title;
        this.icon = icon;
        this.description = description;
        this.parent = parent;
        this.declared = declared;
    }

    /** {@code "management/fences"}. Unique within a schema. */
    public String path() {
        return path;
    }

    /** The last segment: {@code "fences"}. */
    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public String description() {
        return description;
    }

    /** The topic above this one, or null when this is a top-level category. */
    public SettingsTopic parent() {
        return parent;
    }

    /**
     * How far below a root this sits: 0 for a root, 1 for its child.
     *
     * <p>The GUI uses it to decide how much of the trail to show in the window title, so a setting
     * four levels down still says where it is without the title running off the edge.
     */
    public int depth() {
        int levels = 0;
        for (SettingsTopic above = parent; above != null; above = above.parent) {
            levels++;
        }
        return levels;
    }

    /** The submenus worth showing: the ones that are not empty. */
    public List<SettingsTopic> visibleChildren() {
        return children.stream().filter(child -> !child.isEmpty()).toList();
    }

    /** Whether a @Topic named this, as opposed to it being made as somebody's ancestor. */
    boolean wasDeclared() {
        return declared;
    }

    /** Gives a node made as an ancestor the look a later @Topic declares for it. */
    void describe(String newTitle, Material newIcon, String newDescription) {
        this.title = newTitle;
        this.icon = newIcon;
        if (newDescription != null && !newDescription.isBlank()) {
            this.description = newDescription;
        }
    }

    /** Its submenus, in the order they were declared. */
    public List<SettingsTopic> children() {
        return List.copyOf(children);
    }

    /** Its own settings, in the order the record declares them. */
    public List<Setting<?>> settings() {
        return List.copyOf(settings);
    }

    /**
     * Whether opening this shows other topics rather than settings.
     *
     * <p>A topic is allowed to hold both — a "Fences" page with a "Materials" submenu on it is a
     * reasonable thing — so this asks whether there is a submenu at all rather than whether there
     * are no settings.
     */
    public boolean isMenu() {
        return !children.isEmpty();
    }

    /**
     * Whether there is nothing under this at all, however deep.
     *
     * <p>The GUI hides an empty topic. A button that opens an empty page is worse than a missing
     * button: it is a thing to try, twice, before believing it.
     */
    public boolean isEmpty() {
        if (!settings.isEmpty()) {
            return false;
        }
        for (SettingsTopic child : children) {
            if (!child.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Every setting under this topic and everything beneath it, depth first. */
    public List<Setting<?>> allSettings() {
        List<Setting<?>> collected = new ArrayList<>(settings);
        for (SettingsTopic child : children) {
            collected.addAll(child.allSettings());
        }
        return List.copyOf(collected);
    }

    void addChild(SettingsTopic child) {
        children.add(child);
    }

    void addSetting(Setting<?> setting) {
        settings.add(setting);
    }

    @Override
    public String toString() {
        return path;
    }
}
