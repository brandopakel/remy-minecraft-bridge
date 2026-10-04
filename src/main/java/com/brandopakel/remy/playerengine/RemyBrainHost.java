package com.brandopakel.remy.playerengine;

import com.brandopakel.remy.playerengine.brain.RemyBrain;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.player2api.Character;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns Remy's PlayerEngine "hands" (a PlayerEngineController bound to the Remy entity)
 * and the bridge to the local "brain" service.
 *
 * Design rules:
 * - PlayerEngine's own Player2 LLM/auth path is never used. Commands go straight to its
 *   deterministic CommandExecutor ("get", "follow", "attack", "farm", ...).
 * - "stop" never depends on a model: chat "remy stop" is handled here in plain code.
 * - The brain (RemyBrain) runs inside the mod: rules first, then an optional decision model
 *   (Jev via OpenRouter or tev1 via local Ollama) configured in config/remy/brain.json.
 */
public final class RemyBrainHost {
    private static final Character REMY_CHARACTER = new Character(
            "remy-local", "Remy", "Remy", "", "Remy is a helpful Minecraft companion.", "", new String[0]);

    private RemyBrainHost() {
    }

    public static boolean hasController(RemyEntity remy) {
        return PlayerEngineController.staticControllers.containsKey(remy.getUuid());
    }

    /**
     * Returns Remy's controller, creating it once the owner is online. Returns null while
     * the owner is offline (Remy then just stands still; PlayerEngine needs an owner for
     * follow/give and to scope its chat output).
     */
    public static PlayerEngineController ensureController(RemyEntity remy) {
        PlayerEngineController existing = PlayerEngineController.staticControllers.get(remy.getUuid());
        if (existing != null) {
            if (remy.age % 100 == 0) {
                mutePlayerEngineChatAi(existing);
            }
            return existing;
        }
        if (remy.getOwnerUuid() == null || remy.getServer() == null || remy.age % 20 != 0) {
            return null;
        }
        ServerPlayerEntity owner = remy.getServer().getPlayerManager().getPlayer(remy.getOwnerUuid());
        return owner == null ? null : controllerFor(remy, owner);
    }

    public static PlayerEngineController controllerFor(RemyEntity remy, ServerPlayerEntity owner) {
        PlayerEngineController existing = PlayerEngineController.staticControllers.get(remy.getUuid());
        if (existing != null) {
            return existing;
        }
        // Stop the adapter's own lightweight navigation so the two don't fight.
        RemyPlayerEngineAdapter.stopLegacyNavigation(remy);
        PlayerEngineController controller = new PlayerEngineController(remy.getBaritone(), REMY_CHARACTER, "remy-local", owner);
        mutePlayerEngineChatAi(controller);
        RemyPlayerEngineAdapter.LOGGER.info("Attached PlayerEngine controller to Remy {} (Player2 chat AI muted)", remy.getUuid());
        return controller;
    }

    /**
     * PlayerEngine's ConversationManager forwards every owner chat line to its own Player2 LLM,
     * which starts a Player2 sign-in (device flow) and network calls. Remy has its own brain, so
     * we disable only that conversation queue; the task runner and commands keep working.
     * (setChatClefEnabled(false) would also stop the task runner, so it is not used.)
     */
    static void mutePlayerEngineChatAi(PlayerEngineController controller) {
        try {
            com.player2.playerengine.player2api.manager.ConversationManager
                    .getOrCreateEventQueueData(controller).setEnabled(false);
        } catch (RuntimeException e) {
            RemyPlayerEngineAdapter.LOGGER.warn("Could not mute PlayerEngine chat AI: {}", e.toString());
        }
    }

    public static void stop(RemyEntity remy) {
        PlayerEngineController controller = PlayerEngineController.staticControllers.get(remy.getUuid());
        if (controller != null) {
            controller.cancelUserTask();
            controller.stop();
        }
    }

    /** Runs a PlayerEngine command line such as "get oak_log 16" for Remy. */
    public static void run(RemyEntity remy, ServerPlayerEntity owner, String commandLine) {
        PlayerEngineController controller = controllerFor(remy, owner);
        CommandExecutor executor = controller.getCommandExecutor();
        String line = commandLine.trim();
        String full = line.startsWith(executor.getCommandPrefix()) ? line : executor.getCommandPrefix() + line;
        owner.sendMessage(Text.literal("Remy: on it (" + line + ")"), false);
        executor.execute(
                full,
                () -> owner.sendMessage(Text.literal("Remy: finished " + line), false),
                error -> owner.sendMessage(Text.literal("Remy: couldn't do '" + line + "': " + error.getMessage()), false));
    }

    public static List<String> commandNames(RemyEntity remy, ServerPlayerEntity owner) {
        List<String> names = new ArrayList<>();
        for (Command command : controllerFor(remy, owner).getCommandExecutor().allCommands()) {
            names.add(command.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Owner chat entry point. Returns true if the message was addressed to Remy.
     * Fast path (no model): stop, follow, come, status. Everything else goes to the brain.
     */
    public static boolean onOwnerChat(MinecraftServer server, ServerPlayerEntity owner, String raw) {
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!(lower.equals("remy") || lower.startsWith("remy ") || lower.startsWith("remy,"))) {
            return false;
        }
        String request = text.substring(4).replaceFirst("^[,\\s]+", "").trim();
        String req = request.toLowerCase(Locale.ROOT);
        switch (req) {
            case "stop", "halt", "wait", "freeze" -> {
                RemyPlayerEngineAdapter.chatStop(owner);
                return true;
            }
            case "follow", "follow me" -> {
                RemyPlayerEngineAdapter.chatFollow(owner);
                return true;
            }
            case "come", "come here" -> {
                RemyPlayerEngineAdapter.chatCome(owner);
                return true;
            }
            default -> {
            }
        }
        if (request.isEmpty()) {
            owner.sendMessage(Text.literal("Remy: yes?"), false);
            return true;
        }
        askBrain(server, owner, request);
        return true;
    }

    private static void askBrain(MinecraftServer server, ServerPlayerEntity owner, String request) {
        UUID ownerId = owner.getUuid();
        String ownerName = owner.getGameProfile().getName();
        RemyBrain.decide(request, ownerName, RemyPlayerEngineAdapter.describeFor(owner))
                .whenComplete((plan, error) -> server.execute(() -> {
                    ServerPlayerEntity current = server.getPlayerManager().getPlayer(ownerId);
                    if (current == null) {
                        return;
                    }
                    if (error != null || plan == null) {
                        current.sendMessage(Text.literal("<Remy> Sorry, my brain hiccuped: " + error), false);
                        return;
                    }
                    RemyPlayerEngineAdapter.LOGGER.info("Remy brain: '{}' -> {} {} via {}",
                            request, plan.intent(), plan.commands(), plan.via());
                    applyPlan(current, plan);
                }));
    }

    public static void applyPlan(ServerPlayerEntity owner, RemyBrain.Plan plan) {
        if (plan.say() != null && !plan.say().isBlank()) {
            owner.sendMessage(Text.literal("<Remy> " + plan.say()), false);
        }
        if (plan.control() != null) {
            switch (plan.control()) {
                case "stop" -> RemyPlayerEngineAdapter.chatStop(owner);
                case "follow" -> RemyPlayerEngineAdapter.chatFollow(owner);
                case "come" -> RemyPlayerEngineAdapter.chatCome(owner);
                case "village" -> com.brandopakel.remy.playerengine.village.VillageManager.start(owner.getServer(), owner, 0);
                default -> {
                }
            }
            return;
        }
        if (plan.commands().isEmpty()) {
            return;
        }
        RemyPlayerEngineAdapter.findRemyFor(owner).ifPresentOrElse(
                remy -> runAll(remy, owner, plan.commands(), 0),
                () -> owner.sendMessage(Text.literal("Remy isn't spawned. Use /remyengine spawn."), false));
    }

    /** Runs PlayerEngine command lines one after another (e.g. set_follow_mode then follow). */
    public static void runAll(RemyEntity remy, ServerPlayerEntity owner, List<String> lines, int index) {
        if (index >= lines.size()) {
            return;
        }
        PlayerEngineController controller = controllerFor(remy, owner);
        CommandExecutor executor = controller.getCommandExecutor();
        String line = lines.get(index).trim();
        String full = line.startsWith(executor.getCommandPrefix()) ? line : executor.getCommandPrefix() + line;
        boolean last = index == lines.size() - 1;
        java.util.concurrent.atomic.AtomicBoolean advanced = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable advance = () -> {
            if (!advanced.compareAndSet(false, true)) {
                return;
            }
            if (last) {
                owner.sendMessage(Text.literal("<Remy> Done: " + line), false);
            } else {
                runAll(remy, owner, lines, index + 1);
            }
        };
        executor.execute(
                full,
                advance,
                error -> owner.sendMessage(Text.literal("<Remy> I couldn't do '" + line + "': " + error.getMessage()), false));
        if (!last && line.startsWith("set_")) {
            // Mode switches finish instantly and may not fire the finish callback.
            advance.run();
        }
    }
}
