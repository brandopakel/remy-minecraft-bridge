package com.brandopakel.remy.playerengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.player2api.Character;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * - The brain is a separate localhost-only process (brain/ in this repo). If it is not
 *   running, Remy still obeys fast-path chat commands and /remyengine.
 */
public final class RemyBrainHost {
    public static final String BRAIN_URL = System.getProperty("remy.brain.url", "http://127.0.0.1:8765/decide");
    private static final Character REMY_CHARACTER = new Character(
            "remy-local", "Remy", "Remy", "", "Remy is a helpful Minecraft companion.", "", new String[0]);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private RemyBrainHost() {
    }

    public static boolean hasController(RemyEntity remy) {
        return PlayerEngineController.staticControllers.containsKey(remy.getUuid());
    }

    public static PlayerEngineController controllerFor(RemyEntity remy, ServerPlayerEntity owner) {
        PlayerEngineController existing = PlayerEngineController.staticControllers.get(remy.getUuid());
        if (existing != null) {
            return existing;
        }
        // Stop the adapter's own lightweight navigation so the two don't fight.
        RemyPlayerEngineAdapter.stopLegacyNavigation(remy);
        PlayerEngineController controller = new PlayerEngineController(remy.getBaritone(), REMY_CHARACTER, "remy-local", owner);
        RemyPlayerEngineAdapter.LOGGER.info("Attached PlayerEngine controller to Remy {}", remy.getUuid());
        return controller;
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
        JsonObject body = new JsonObject();
        body.addProperty("message", request);
        body.addProperty("owner", owner.getGameProfile().getName());
        body.addProperty("state", RemyPlayerEngineAdapter.describeFor(owner));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(BRAIN_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HTTP.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> server.execute(() -> {
                    ServerPlayerEntity current = server.getPlayerManager().getPlayer(ownerId);
                    if (current == null) {
                        return;
                    }
                    if (error != null || response == null || response.statusCode() != 200) {
                        current.sendMessage(Text.literal("Remy: my brain isn't running (start brain/ on your PC). "
                                + "I can still do: remy follow / come / stop, or /remyengine do <command>."), false);
                        return;
                    }
                    applyDecision(current, response.body());
                }));
    }

    private static void applyDecision(ServerPlayerEntity owner, String json) {
        JsonObject decision;
        try {
            decision = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            owner.sendMessage(Text.literal("Remy: I got confused (bad brain reply)."), false);
            return;
        }
        if (decision.has("say") && !decision.get("say").isJsonNull()) {
            owner.sendMessage(Text.literal("Remy: " + decision.get("say").getAsString()), false);
        }
        String action = decision.has("action") && !decision.get("action").isJsonNull()
                ? decision.get("action").getAsString() : "none";
        switch (action) {
            case "stop" -> RemyPlayerEngineAdapter.chatStop(owner);
            case "follow" -> RemyPlayerEngineAdapter.chatFollow(owner);
            case "come" -> RemyPlayerEngineAdapter.chatCome(owner);
            case "command" -> {
                String line = decision.has("command") ? decision.get("command").getAsString() : "";
                if (line.isBlank()) {
                    return;
                }
                RemyPlayerEngineAdapter.findRemyFor(owner)
                        .ifPresentOrElse(
                                remy -> run(remy, owner, line),
                                () -> owner.sendMessage(Text.literal("Remy isn't spawned. Use /remyengine spawn."), false));
            }
            default -> {
            }
        }
    }
}
