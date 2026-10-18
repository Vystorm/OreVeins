package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class CommandTabTest {
    /** Every subcommand OreVeinsPlugin#onCommand handles. */
    private static final List<String> HANDLED = List.of("history", "reload", "status", "stats", "simulate", "failed",
            "retry", "validate", "compare", "web", "regenerate");

    /** Minimal sender without a mocking library: only hasPermission(String) is answered. */
    private static CommandSender sender(boolean admin) {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("hasPermission") && arguments != null && arguments[0] instanceof String node)
                        return admin && node.equals("oreveins.admin");
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    private static List<String> complete(CommandSender sender, String... args) {
        return OreVeinsPlugin.complete(sender, args, true, () -> List.of("world", "world_nether"));
    }

    @Test
    void everyHandledSubcommandIsSuggestedForAdminsAndHiddenWithout() {
        CommandSender admin = sender(true);
        List<String> all = complete(admin, "");
        for (String sub : HANDLED) assertTrue(all.contains(sub), sub);
        assertTrue(complete(sender(false), "").isEmpty());
        assertTrue(complete(sender(false), "regenerate", "").isEmpty());
        assertEquals(List.of("world_nether"), complete(admin, "STATS", "world_"));
        assertEquals(List.of("confirm"), complete(admin, "retry", "C"));
        assertEquals(List.of("preview"), complete(admin, "regenerate", "pr"));
    }
}
