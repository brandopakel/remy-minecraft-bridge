package com.brandopakel.remy.playerengine.brain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Remy's brain: one chat message in, one plan (a few PlayerEngine command lines) out.
 *
 * 1. Plain-code rules catch clear phrasing for free (and "stop" never needs a model).
 * 2. When the rules can't tell, or the item/mob is ambiguous, a decision model picks from
 *    OUR options: Jev (TypeSafe, hosted via OpenRouter) or tev1 (Together AI, local via
 *    Ollama >= 0.35). Both speak the same {model, state, questions} format and can only
 *    return one of the options we give them, so the model can never invent a command.
 * 3. Items, blocks and mobs come from the live modpack registries, so modded content
 *    works in any pack. Counts and names are filled in by code, never by the model.
 */
public final class RemyBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("remy_brain");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();

    public enum Intent {
        FOLLOW("Stay with the owner and follow them around."),
        COME("Walk over to where the owner is standing right now, once."),
        STOP("Stop, cancel, wait, stay put or calm down."),
        DEFEND("Protect or guard the owner, fight alongside them, be their bodyguard."),
        CLEAR_HOSTILES("Kill every hostile monster around, clear the area of mobs."),
        ATTACK("Attack or hunt a specific kind of mob or animal."),
        GATHER("Get, collect, gather, chop, craft or make a specific item."),
        MINE("Mine or dig a specific ore or block."),
        SETUP_FARM("Build or set up a new crop farm."),
        HARVEST_FARM("Harvest the grown crops from the farm."),
        GET_FOOD("Find or collect food in general, no specific item named."),
        GIVE("Hand or bring an item to the owner."),
        DEPOSIT("Put items away into a chest, store the inventory."),
        SMELT("Smelt, cook or blast an item in a furnace."),
        FISH("Go fishing."),
        EXPLORE("Explore or scout the surrounding area."),
        EAT("Eat something because hungry."),
        PICKUP("Pick up the items lying on the ground nearby."),
        BUILD("Build a house, structure, village or other building."),
        CHAT("Just talking, a greeting, a question, or nothing actionable.");

        public final String description;

        Intent(String description) {
            this.description = description;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Intent fromKey(String key) {
            for (Intent i : values()) {
                if (i.key().equals(key)) {
                    return i;
                }
            }
            return null;
        }
    }

    /** Result of a decision: command lines to run in order, plus what Remy says. */
    public record Plan(Intent intent, List<String> commands, String say, String control, String via) {
    }

    // ---------------------------------------------------------------- config

    public static final class Provider {
        public String name = "ollama";
        public String url = "http://127.0.0.1:11434/v1/systemone";
        public String model = "tev1:0.8b";
        public String apiKeyEnv = "";
        public String apiKey = "";
        public boolean enabled = true;
    }

    public static final class Config {
        public boolean useModel = true;
        public int timeoutSeconds = 8;
        public List<Provider> providers = new ArrayList<>();

        static Config defaults() {
            Config c = new Config();
            Provider jev = new Provider();
            jev.name = "openrouter";
            jev.url = "https://openrouter.ai/api/alpha/decisions";
            jev.model = "typesafe/jev-1.13";
            jev.apiKeyEnv = "OPENROUTER_API_KEY";
            Provider tev = new Provider();
            c.providers.add(jev);
            c.providers.add(tev);
            return c;
        }
    }

    private static Config config;

    public static synchronized Config config() {
        if (config == null) {
            config = loadConfig();
        }
        return config;
    }

    public static synchronized void reloadConfig() {
        config = loadConfig();
    }

    private static Config loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("remy").resolve("brain.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(Config.defaults()));
                LOGGER.info("Wrote default Remy brain config to {}", file);
            }
            Config c = GSON.fromJson(Files.readString(file), Config.class);
            return c == null ? Config.defaults() : c;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
            return Config.defaults();
        }
    }

    // ---------------------------------------------------------------- rules

    private static boolean has(String padded, String... phrases) {
        for (String p : phrases) {
            if (padded.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_:\\s-]", " ").replaceAll("\\s+", " ").trim() + " ";
    }

    /** Clear-cut phrasing -> intent, or null when a model (or a guess) is needed. */
    public static Intent ruleIntent(String message) {
        String t = normalize(message);
        if (t.matches("^ (stop|halt|wait|freeze|cancel|stay)\\b.*") || has(t, "stop it", "stand down", "cancel that")) {
            return Intent.STOP;
        }
        if (has(t, "follow", "come with", "tag along", "stick with")) return Intent.FOLLOW;
        if (has(t, "come here", "come to me", "over here", "get over here")) return Intent.COME;
        if (has(t, "protect", "guard", "bodyguard", "defend", "watch my back", "cover me")) return Intent.DEFEND;
        if (has(t, "kill all", "clear the", "all the mobs", "all monsters", "all the monsters", "every mob")) return Intent.CLEAR_HOSTILES;
        if (has(t, "fish", "fishing")) return Intent.FISH;
        if (has(t, "explore", "scout")) return Intent.EXPLORE;
        if (has(t, "harvest")) return Intent.HARVEST_FARM;
        if (has(t, "make a farm", "build a farm", "set up a farm", "start a farm", "setup a farm")) return Intent.SETUP_FARM;
        if (has(t, "smelt", "cook", "furnace")) return Intent.SMELT;
        if (has(t, "deposit", "put away", "store", "stash")) return Intent.DEPOSIT;
        if (has(t, "pick up", "pickup")) return Intent.PICKUP;
        if (has(t, "build", "construct", "village", "house")) return Intent.BUILD;
        if (has(t, "mine", "dig")) return Intent.MINE;
        if (has(t, "kill", "attack", "hunt", "fight")) return Intent.ATTACK;
        if (has(t, "give me", "hand me", "bring me")) return Intent.GIVE;
        if (has(t, "eat") && !has(t, "food")) return Intent.EAT;
        if (has(t, "food") && !has(t, "get", "gather", "collect")) return Intent.GET_FOOD;
        if (has(t, "get", "gather", "collect", "chop", "craft", "make", "grab", "fetch", "need")) return Intent.GATHER;
        if (has(t, "hello", "hi", "hey", "thanks", "thank you", "how are")) return Intent.CHAT;
        return null;
    }

    private static final Map<String, Integer> WORD_NUMBERS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("twelve", 12), Map.entry("twenty", 20),
            Map.entry("dozen", 12));

    public static int parseCount(String message, int fallback) {
        String t = normalize(message);
        java.util.regex.Matcher stacks = java.util.regex.Pattern.compile(" (\\d+|a|one|two|three|four) stacks? ").matcher(t);
        if (stacks.find()) {
            String n = stacks.group(1);
            int k = n.equals("a") ? 1 : WORD_NUMBERS.getOrDefault(n, n.matches("\\d+") ? Integer.parseInt(n) : 1);
            return Math.min(k * 64, 640);
        }
        java.util.regex.Matcher num = java.util.regex.Pattern.compile(" (\\d{1,4}) ").matcher(t);
        if (num.find()) {
            return Math.max(1, Math.min(Integer.parseInt(num.group(1)), 640));
        }
        if (has(t, "a few", "a couple", "couple")) return 4;
        if (has(t, "some", "bunch", "lots", "a lot", "plenty")) return 16;
        for (Map.Entry<String, Integer> e : WORD_NUMBERS.entrySet()) {
            if (t.contains(" " + e.getKey() + " ")) {
                return e.getValue();
            }
        }
        return fallback;
    }

    // ---------------------------------------------------------------- targets

    private static final Map<String, String> ITEM_SYNONYMS = Map.ofEntries(
            Map.entry("wood", "minecraft:oak_log"), Map.entry("log", "minecraft:oak_log"),
            Map.entry("tree", "minecraft:oak_log"), Map.entry("plank", "minecraft:oak_planks"),
            Map.entry("iron", "minecraft:iron_ingot"), Map.entry("gold", "minecraft:gold_ingot"),
            Map.entry("copper", "minecraft:copper_ingot"), Map.entry("stone", "minecraft:cobblestone"),
            Map.entry("rock", "minecraft:cobblestone"), Map.entry("seed", "minecraft:wheat_seeds"),
            Map.entry("food", "minecraft:bread"), Map.entry("steak", "minecraft:cooked_beef"),
            Map.entry("pork", "minecraft:cooked_porkchop"), Map.entry("bed", "minecraft:white_bed"),
            Map.entry("wool", "minecraft:white_wool"), Map.entry("sword", "minecraft:iron_sword"),
            Map.entry("pickaxe", "minecraft:iron_pickaxe"), Map.entry("pick", "minecraft:iron_pickaxe"),
            Map.entry("axe", "minecraft:iron_axe"), Map.entry("shovel", "minecraft:iron_shovel"),
            Map.entry("table", "minecraft:crafting_table"));
    private static final Map<String, String> BLOCK_SYNONYMS = Map.ofEntries(
            Map.entry("iron", "minecraft:iron_ore"), Map.entry("coal", "minecraft:coal_ore"),
            Map.entry("diamond", "minecraft:diamond_ore"), Map.entry("gold", "minecraft:gold_ore"),
            Map.entry("copper", "minecraft:copper_ore"), Map.entry("redstone", "minecraft:redstone_ore"),
            Map.entry("lapis", "minecraft:lapis_ore"), Map.entry("emerald", "minecraft:emerald_ore"),
            Map.entry("cobble", "minecraft:stone"), Map.entry("rock", "minecraft:stone"));

    static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.matches(".*(ch|sh|x|ss|o)es$")) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
    }

    /** Rank registry ids against the words in a message; best first. */
    public static List<String> match(String message, Iterable<Identifier> ids, Map<String, String> synonyms, int limit) {
        String[] raw = normalize(message).trim().split(" ");
        List<String> words = new ArrayList<>();
        for (String w : raw) {
            if (!w.isBlank()) {
                words.add(w.contains(":") ? w : singular(w));
            }
        }
        List<String> phrases = new ArrayList<>();
        for (int n = 3; n >= 1; n--) {
            for (int i = 0; i + n <= words.size(); i++) {
                phrases.add(String.join("_", words.subList(i, i + n)));
            }
        }
        Map<String, Double> scored = new LinkedHashMap<>();
        for (String w : words) {
            if (w.contains(":")) {
                scored.merge(w, 2.0, Math::max); // explicit modded id typed by the owner
            }
        }
        for (String p : phrases) {
            String syn = synonyms.get(p);
            if (syn != null) {
                scored.merge(syn, 0.7 + p.length() / 100.0, Math::max);
            }
        }
        for (Identifier id : ids) {
            String path = id.getPath();
            String full = id.toString();
            for (String p : phrases) {
                if (p.length() < 3) continue;
                double s = 0;
                if (path.equals(p)) s = 1.0 + p.length() / 100.0 + (id.getNamespace().equals("minecraft") ? 0.01 : 0);
                else if (path.endsWith("_" + p) || path.startsWith(p + "_")) s = 0.6 + p.length() / 200.0 - path.length() / 400.0;
                if (s > 0) scored.merge(full, s, Math::max);
            }
        }
        return scored.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    static List<String> candidatesFor(Intent intent, String message) {
        if (intent == Intent.ATTACK) {
            List<Identifier> mobs = new ArrayList<>();
            for (var type : Registries.ENTITY_TYPE) {
                SpawnGroup g = type.getSpawnGroup();
                if (g == SpawnGroup.MONSTER || g == SpawnGroup.CREATURE) {
                    mobs.add(Registries.ENTITY_TYPE.getId(type));
                }
            }
            return match(message, mobs, Map.of(), 5);
        }
        if (intent == Intent.MINE) {
            return match(message, Registries.BLOCK.getIds(), BLOCK_SYNONYMS, 5);
        }
        return match(message, Registries.ITEM.getIds(), ITEM_SYNONYMS, 6);
    }

    /** PlayerEngine accepts bare names for vanilla content and full ids for modded content. */
    static String commandId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    static String pretty(int n, String id) {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return n + " " + name.replace('_', ' ');
    }

    // ---------------------------------------------------------------- plan

    static Plan toPlan(Intent intent, String message, String owner, String target, String via) {
        List<String> cmds = new ArrayList<>();
        String say;
        String control = null;
        switch (intent) {
            case STOP -> { control = "stop"; say = "Stopping."; }
            case FOLLOW -> { control = "follow"; say = "Right behind you."; }
            case COME -> { control = "come"; say = "Coming!"; }
            case DEFEND -> { cmds.add("set_follow_mode DEFENDER"); cmds.add("follow " + owner); say = "I've got your back."; }
            case CLEAR_HOSTILES -> { cmds.add("hero"); say = "Clearing out the monsters."; }
            case ATTACK -> {
                if (target == null) { cmds.add("hero"); say = "I'll take out whatever's hostile."; }
                else { int n = parseCount(message, 1); cmds.add("attack " + commandId(target) + " " + n); say = "Going after " + pretty(n, target) + "."; }
            }
            case GATHER -> {
                if (target == null) { say = "What should I get? Name the item, like \"get 16 oak logs\"."; }
                else { int n = parseCount(message, 16); cmds.add("get " + commandId(target) + " " + n); say = "Getting " + pretty(n, target) + "."; }
            }
            case MINE -> {
                if (target == null) { say = "What should I mine?"; }
                else { int n = parseCount(message, 16); cmds.add("mine " + commandId(target) + " " + n); say = "Mining " + pretty(n, target) + "."; }
            }
            case GIVE -> {
                if (target == null) { say = "What do you want me to hand you?"; }
                else { int n = parseCount(message, 1); cmds.add("give " + owner + " " + commandId(target) + " " + n); say = "Here you go: " + pretty(n, target) + "."; }
            }
            case SMELT -> {
                if (target == null) { say = "What should I smelt?"; }
                else { int n = parseCount(message, 8); cmds.add("smelt " + commandId(target) + " " + n); say = "Smelting " + pretty(n, target) + "."; }
            }
            case SETUP_FARM -> { cmds.add("setup_farm"); say = "I'll set up a farm nearby."; }
            case HARVEST_FARM -> { cmds.add("harvest_farm"); say = "Harvesting the ripe crops."; }
            case GET_FOOD -> { cmds.add("food " + parseCount(message, 10)); say = "Finding us some food."; }
            case DEPOSIT -> { cmds.add("deposit"); say = "Putting my stuff in a chest."; }
            case FISH -> { cmds.add("fish"); say = "Gone fishing."; }
            case EXPLORE -> { cmds.add("explore"); say = "Scouting around."; }
            case EAT -> { cmds.add("eat_food"); say = "Snack time."; }
            case PICKUP -> { cmds.add("pickup_drops"); say = "Grabbing the loot."; }
            case BUILD -> {
                if (normalize(message).matches(".* (village|town|settlement|city|hamlet) .*")) {
                    control = "village";
                    say = "A whole village? Let's do it.";
                } else {
                    say = "I can't build single houses from a description yet. Try \"remy build a village here\".";
                }
            }
            default -> { say = "Hey! Tell me to follow, guard you, fight, farm, mine, or get something."; }
        }
        return new Plan(intent, cmds, say, control, via);
    }

    private static final java.util.Set<Intent> NEEDS_TARGET =
            java.util.EnumSet.of(Intent.ATTACK, Intent.GATHER, Intent.MINE, Intent.GIVE, Intent.SMELT);

    /**
     * Decide asynchronously. Registry reads happen on the calling (server) thread; only the
     * model HTTP call runs off-thread.
     */
    public static CompletableFuture<Plan> decide(String message, String owner, String state) {
        Intent ruled = ruleIntent(message);
        List<String> items = candidatesFor(Intent.GATHER, message);
        List<String> mobs = candidatesFor(Intent.ATTACK, message);
        List<String> blocks = candidatesFor(Intent.MINE, message);
        Config cfg = config();

        boolean ambiguous = ruled != null && NEEDS_TARGET.contains(ruled) && pool(ruled, items, mobs, blocks).size() > 1;
        if (!cfg.useModel || (ruled != null && !ambiguous)) {
            return CompletableFuture.completedFuture(finish(ruled, message, owner, null, items, mobs, blocks, "rules"));
        }
        List<String> options = ruled != null ? pool(ruled, items, mobs, blocks) : mergeOptions(items, mobs);
        JsonObject body = decisionRequest(message, state, options);
        return askProviders(cfg, body, 0, "").thenApply(res -> {
            Intent intent = ruled;
            String target = null;
            String via = ruled != null ? "rules" : "guess";
            if (res.json != null) {
                String modelIntent = choice(res.json, "intent");
                if (intent == null && modelIntent != null) intent = Intent.fromKey(modelIntent);
                String t = choice(res.json, "target");
                if (t != null && options.contains(t)) target = t;
                via = (ruled != null ? "rules+" : "") + res.label;
            } else if (!res.error.isEmpty()) {
                via += " (model unavailable: " + res.error + ")";
            }
            return finish(intent, message, owner, target, items, mobs, blocks, via);
        });
    }

    private static List<String> pool(Intent intent, List<String> items, List<String> mobs, List<String> blocks) {
        return switch (intent) {
            case ATTACK -> mobs;
            case MINE -> blocks;
            default -> items;
        };
    }

    private static List<String> mergeOptions(List<String> items, List<String> mobs) {
        List<String> out = new ArrayList<>(items);
        for (String m : mobs) {
            if (!out.contains(m)) out.add(m);
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private static Plan finish(Intent intent, String message, String owner, String target,
                               List<String> items, List<String> mobs, List<String> blocks, String via) {
        Intent i = intent == null ? Intent.CHAT : intent;
        String t = target;
        if (t == null && NEEDS_TARGET.contains(i)) {
            List<String> p = pool(i, items, mobs, blocks);
            t = p.isEmpty() ? null : p.get(0);
        }
        return toPlan(i, message, owner, t, via);
    }

    // ---------------------------------------------------------------- model calls

    static JsonObject decisionRequest(String message, String state, List<String> options) {
        JsonObject body = new JsonObject();
        JsonObject st = new JsonObject();
        st.addProperty("player_message", message);
        st.addProperty("game_state", state == null ? "unknown" : state);
        body.add("state", st);
        JsonObject questions = new JsonObject();
        JsonObject intent = new JsonObject();
        intent.addProperty("type", "choice");
        intent.addProperty("instructions", "What does the player want their Minecraft companion Remy to do?");
        JsonObject criteria = new JsonObject();
        for (Intent i : Intent.values()) criteria.addProperty(i.key(), i.description);
        intent.add("criteria", criteria);
        questions.add("intent", intent);
        if (options.size() > 1) {
            JsonObject target = new JsonObject();
            target.addProperty("type", "choice");
            target.addProperty("instructions", "Which Minecraft item, block or mob is the player talking about?");
            JsonObject tc = new JsonObject();
            for (String o : options) tc.addProperty(o, o.substring(o.indexOf(':') + 1).replace('_', ' ') + " (" + o + ")");
            target.add("criteria", tc);
            questions.add("target", target);
        }
        body.add("questions", questions);
        return body;
    }

    /** Reads answers.<key>.choice (OpenRouter Jev) or tolerant variants (Ollama tev1). */
    static String choice(JsonObject res, String key) {
        for (String container : new String[]{"answers", "choices"}) {
            if (res.has(container) && res.get(container).isJsonObject()) {
                JsonObject c = res.getAsJsonObject(container);
                if (c.has(key)) {
                    JsonElement a = c.get(key);
                    if (a.isJsonPrimitive()) return a.getAsString();
                    if (a.isJsonObject() && a.getAsJsonObject().has("choice")) return a.getAsJsonObject().get("choice").getAsString();
                }
            }
        }
        return null;
    }

    private record ModelResult(JsonObject json, String label, String error) {
    }

    private static CompletableFuture<ModelResult> askProviders(Config cfg, JsonObject body, int index, String lastError) {
        if (index >= cfg.providers.size()) {
            return CompletableFuture.completedFuture(new ModelResult(null, "", lastError));
        }
        Provider p = cfg.providers.get(index);
        String key = p.apiKey != null && !p.apiKey.isBlank() ? p.apiKey
                : (p.apiKeyEnv != null && !p.apiKeyEnv.isBlank() ? System.getenv(p.apiKeyEnv) : null);
        boolean needsKey = p.apiKeyEnv != null && !p.apiKeyEnv.isBlank();
        if (!p.enabled || (needsKey && (key == null || key.isBlank()))) {
            return askProviders(cfg, body, index + 1, lastError);
        }
        JsonObject req = body.deepCopy();
        req.addProperty("model", p.model);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(p.url))
                .timeout(Duration.ofSeconds(Math.max(2, cfg.timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(req.toString()));
        if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
        long started = System.currentTimeMillis();
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) return new ModelResult(null, "", p.name + ": " + rootMessage(err));
                    if (resp.statusCode() != 200) return new ModelResult(null, "", p.name + " HTTP " + resp.statusCode());
                    try {
                        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                        LOGGER.info("Remy brain: {} answered in {} ms", p.name, System.currentTimeMillis() - started);
                        return new ModelResult(json, p.name + ":" + p.model, "");
                    } catch (RuntimeException e) {
                        return new ModelResult(null, "", p.name + ": bad JSON");
                    }
                })
                .thenCompose(r -> r.json != null ? CompletableFuture.completedFuture(r) : askProviders(cfg, body, index + 1, r.error));
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : " " + c.getMessage());
    }

    private RemyBrain() {
    }
}
