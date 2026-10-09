package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Some of the items there are, as an owner writes them: whole shop drawers, items by name or pattern
 * ({@code *_log}), and exceptions ({@code diamond_block}). For anything that applies to "building blocks and
 * iron, but not the valuable ones" — a role's perk, a bulk discount — so each does not grow its own matcher.
 */
public record ItemSelection(List<Category> categories, List<String> items, List<String> except) {

    public static final ItemSelection NOTHING = new ItemSelection(List.of(), List.of(), List.of());

    private static final int NAMED = 4;

    public ItemSelection {
        categories = List.copyOf(categories);
        items = items.stream().map(each -> each.strip().toUpperCase(Locale.ROOT)).filter(each -> !each.isEmpty()).toList();
        except = except.stream().map(each -> each.strip().toUpperCase(Locale.ROOT)).filter(each -> !each.isEmpty()).toList();
    }

    /**
     * Read from a settings list: a line naming a category ({@code building_blocks}) is the whole drawer, a
     * line starting with {@code !} is an exception, anything else an item or a pattern.
     */
    public static ItemSelection parse(List<String> lines) {
        List<Category> categories = new ArrayList<>();
        List<String> items = new ArrayList<>();
        List<String> except = new ArrayList<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            String text = line == null ? "" : line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("!")) {
                except.add(text.substring(1));
                continue;
            }
            try {
                categories.add(Category.valueOf(text.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException notACategory) {
                items.add(text);
            }
        }
        return new ItemSelection(categories, items, except);
    }

    public boolean isEmpty() {
        return categories.isEmpty() && items.isEmpty();
    }

    public boolean covers(String material) {
        if (material == null || material.isBlank()) {
            return false;
        }
        String name = material.toUpperCase(Locale.ROOT);
        if (except.stream().anyMatch(pattern -> matches(pattern, name))) {
            return false;
        }
        return items.stream().anyMatch(pattern -> matches(pattern, name))
                || categories.contains(Catalogue.categoryOf(name));
    }

    /** Whether a name matches a pattern where {@code *} stands for anything. */
    public static boolean matches(String pattern, String material) {
        String regex = Arrays.stream(pattern.toUpperCase(Locale.ROOT).split("\\*", -1))
                .map(Pattern::quote).reduce((a, b) -> a + ".*" + b).orElse("");
        return material.toUpperCase(Locale.ROOT).matches(regex);
    }

    /** "Food, Smoker" — the first few things it holds, then how many more. */
    public String says() {
        List<String> names = new ArrayList<>();
        categories.forEach(category -> names.add(category.title()));
        items.forEach(item -> names.add(item.contains("*") ? describePattern(item) : Catalogue.readable(item)));
        return String.join(", ", names.subList(0, Math.min(NAMED, names.size())))
                + (names.size() > NAMED ? " and " + (names.size() - NAMED) + " more" : "");
    }

    private static String describePattern(String pattern) {
        return "any " + Catalogue.readable(pattern.replace("*", "").replaceAll("^_|_$", "")).toLowerCase(Locale.ROOT);
    }
}
