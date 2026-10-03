package de.raindancer.core.testkit;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permission;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.withSettings;

/**
 * Players for unit tests with a working inventory, a cursor, permissions and a record of what they were
 * told.
 *
 * <pre>{@code
 * Player alex = TestPlayers.player("Alex", "manhunt.admin");
 * kit.give(alex);
 * assertThat(alex.getInventory().contains(Material.COMPASS)).isTrue();
 * assertThat(TestPlayers.said(alex)).anySatisfy(line -> assertThat(line).contains("tracker"));
 * }</pre>
 *
 * <p>A player is a mock: name, id (the offline-mode id for the name), inventory, cursor, permissions
 * and messages answer from state; anything else — location, world, game mode — is stubbed as usual.
 */
public final class TestPlayers {

    private TestPlayers() {
    }

    /** A player with these permissions, not an operator. */
    public static Player player(String name, String... permissions) {
        Objects.requireNonNull(name, "name");
        Person person = new Person(name, Set.of(permissions));
        Player player = Mockito.mock(Player.class, withSettings().name("Player " + name).defaultAnswer(person));
        person.inventory = TestInventories.player(player);
        return player;
    }

    /** Everything this player was sent, as plain text, oldest first. */
    public static List<String> said(Player player) {
        return Collections.unmodifiableList(new ArrayList<>(person(player).said));
    }

    /** Forgets what this player was sent so far. */
    public static void forgetSaid(Player player) {
        person(player).said.clear();
    }

    /** Gives this player more permissions. */
    public static void grant(Player player, String... permissions) {
        Collections.addAll(person(player).permissions, permissions);
    }

    /** Makes this player an operator, or not: an operator has every permission. */
    public static void op(Player player, boolean op) {
        person(player).op = op;
    }

    private static Person person(Player player) {
        if (player != null && Mockito.mockingDetails(player).isMock()
                && Mockito.mockingDetails(player).getMockCreationSettings().getDefaultAnswer() instanceof Person person) {
            return person;
        }
        throw new IllegalArgumentException("Not a TestPlayers player: " + player);
    }

    private static final class Person implements Answer<Object> {
        private final String name;
        private final UUID id;
        private final Set<String> permissions;
        private final List<String> said = new ArrayList<>();
        private PlayerInventory inventory;
        private ItemStack cursor;
        private boolean op;

        Person(String name, Set<String> permissions) {
            this.name = name;
            this.id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            this.permissions = new HashSet<>(permissions);
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            Object first = args.length == 0 ? null : args[0];
            switch (invocation.getMethod().getName()) {
                case "getName", "getPlayerListName":
                    return name;
                case "getUniqueId":
                    return id;
                case "getInventory":
                    return inventory;
                case "getItemOnCursor":
                    return cursor == null ? TestItems.air() : cursor;
                case "setItemOnCursor":
                    cursor = (ItemStack) first;
                    return null;
                case "isOnline", "isConnected", "isValid":
                    return true;
                case "isOp":
                    return op;
                case "setOp":
                    op = (Boolean) first;
                    return null;
                case "hasPermission":
                    String node = first instanceof Permission permission ? permission.getName() : (String) first;
                    return op || permissions.contains(node);
                case "isPermissionSet":
                    return permissions.contains(first instanceof Permission permission ? permission.getName() : (String) first);
                case "sendMessage":
                    for (Object argument : invocation.getRawArguments()) {
                        if (argument instanceof ComponentLike line) {
                            said.add(PlainTextComponentSerializer.plainText().serialize(line.asComponent()));
                        } else if (argument instanceof String line) {
                            said.add(line);
                        } else if (argument instanceof String[] lines) {
                            Collections.addAll(said, lines);
                        }
                    }
                    return null;
                case "sendRichMessage":
                    said.add(PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize((String) first)));
                    return null;
                case "sendPlainMessage":
                    said.add((String) first);
                    return null;
                case "toString":
                    return "Player " + name;
                default:
                    return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
        }
    }
}
