package com.brandopakel.remy.playerengine.architect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The architect: turns "build me a cozy timber cottage with a mossy stone base" into a
 * validated {@link Blueprint}. One call to a strong chat model (OpenRouter by default),
 * given a shortlist of block families from the live catalog with their colours and
 * materials. If the design fails validation, the errors go back for one fix-up round.
 *
 * This is the only expensive model Remy uses, and only when you ask for a design.
 */
public final class Architect {
    private static final Logger LOGGER = LoggerFactory.getLogger("remy_architect");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** One OpenAI-compatible chat endpoint. Tried in order; the first with a key that answers wins. */
    public static final class Provider {
        public String name;
        public String url;
        public String model;
        public String apiKeyEnv = "";
        public String apiKey = "";
        public boolean enabled = true;

        Provider() {
        }

        Provider(String name, String url, String model, String apiKeyEnv) {
            this.name = name;
            this.url = url;
            this.model = model;
            this.apiKeyEnv = apiKeyEnv;
        }

        String key() {
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
            return apiKeyEnv == null || apiKeyEnv.isBlank() ? null : System.getenv(apiKeyEnv);
        }
    }

    public static final class Config {
        /**
         * Default: Google's Gemini API free tier (no card; key from aistudio.google.com/apikey),
         * Pro first, Flash if Pro is rate-limited. OpenRouter only if you add a paid key.
         */
        public List<Provider> providers = new ArrayList<>(List.of(
                new Provider("gemini-pro", GEMINI, "gemini-2.5-pro", "GEMINI_API_KEY"),
                new Provider("gemini-flash", GEMINI, "gemini-2.5-flash", "GEMINI_API_KEY"),
                new Provider("openrouter", "https://openrouter.ai/api/v1/chat/completions", "anthropic/claude-sonnet-5.5", "OPENROUTER_API_KEY")));
        public int maxX = 24;
        public int maxY = 20;
        public int maxZ = 24;
        public int paletteFamilies = 160;
        public int maxTokens = 32000;
        public int timeoutSeconds = 300;
        public double temperature = 0.7;

        public List<Provider> usable() {
            List<Provider> out = new ArrayList<>();
            for (Provider p : providers) {
                if (p.enabled && p.key() != null && !p.key().isBlank()) out.add(p);
            }
            return out;
        }

        public String keyHint() {
            return "set GEMINI_API_KEY (free key from aistudio.google.com/apikey) and restart CurseForge";
        }
    }

    static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

    public record Result(Blueprint blueprint, String error, List<String> warnings, String usage) {
    }

    private Architect() {
    }

    public static Config config() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("remy").resolve("architect.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(new Config()));
            }
            Config c = GSON.fromJson(Files.readString(file), Config.class);
            return c == null ? new Config() : c;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}: {}", file, e.toString());
            return new Config();
        }
    }

    /** Resolves "ns:id[props]" against the live block registry. */
    public static final Function<String, BlockState> REGISTRY_RESOLVER = s -> {
        try {
            return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), s, false).blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    };

    public static Blueprint.Parsed parse(String json, Config cfg) {
        return Blueprint.parse(json, REGISTRY_RESOLVER, cfg.maxX, cfg.maxY, cfg.maxZ);
    }

    // ---------------------------------------------------------------- design call

    public static CompletableFuture<Result> design(String request, BlockCatalog.Snapshot catalog) {
        Config cfg = config();
        List<Provider> providers = cfg.usable();
        if (providers.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(null, "no design model key: " + cfg.keyHint(), List.of(), ""));
        }
        String palette = paletteText(shortlist(request, catalog, cfg.paletteFamilies));
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(cfg)));
        messages.add(message("user", "Request: " + request + "\n\nAvailable blocks (family | material | look | members):\n" + palette));
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder usage = new StringBuilder();
            List<String> failures = new ArrayList<>();
            for (Provider provider : providers) {
                try {
                    String reply = chat(cfg, provider, messages, usage);
                    Blueprint.Parsed parsed = parse(reply, cfg);
                    if (!parsed.ok()) {
                        LOGGER.info("Architect design from {} failed validation, asking for a fix: {}", provider.name, parsed.errors());
                        List<JsonObject> retry = new ArrayList<>(messages);
                        retry.add(message("assistant", reply));
                        retry.add(message("user", "That design has problems:\n- " + String.join("\n- ", parsed.errors())
                                + "\nReturn the complete corrected JSON only."));
                        reply = chat(cfg, provider, retry, usage);
                        parsed = parse(reply, cfg);
                    }
                    saveDesign(request, reply);
                    if (!parsed.ok()) {
                        failures.add(provider.name + ": design didn't validate (" + String.join("; ", parsed.errors()) + ")");
                        continue;
                    }
                    usage.append("via ").append(provider.name).append(" (").append(provider.model).append(")");
                    return new Result(parsed.blueprint(), null, parsed.warnings(), usage.toString());
                } catch (Exception e) {
                    LOGGER.warn("Architect provider {} failed: {}", provider.name, e.toString());
                    failures.add(provider.name + ": " + e.getMessage());
                }
            }
            return new Result(null, String.join(" | ", failures), List.of(), usage.toString());
        });
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    public static String modelsLabel(Config cfg) {
        List<Provider> usable = cfg.usable();
        return usable.isEmpty() ? "(no model configured)"
                : usable.get(0).model + (usable.size() > 1 ? " (+" + (usable.size() - 1) + " fallbacks)" : "");
    }

    private static String chat(Config cfg, Provider provider, List<JsonObject> messages, StringBuilder usage) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", provider.model);
        body.addProperty("max_tokens", cfg.maxTokens);
        body.addProperty("temperature", cfg.temperature);
        JsonArray arr = new JsonArray();
        messages.forEach(arr::add);
        body.add("messages", arr);
        HttpRequest req = HttpRequest.newBuilder(URI.create(provider.url))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.key())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException(provider.model + " HTTP " + r.statusCode() + ": " + truncate(r.body(), 300));
        }
        JsonObject json = JsonParser.parseString(r.body()).getAsJsonObject();
        if (json.has("usage") && json.get("usage").isJsonObject()) {
            JsonObject u = json.getAsJsonObject("usage");
            usage.append(u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : 0).append(" in / ")
                    .append(u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : 0).append(" out tokens; ");
        }
        if (json.has("error")) {
            throw new IOException("architect model error: " + truncate(json.get("error").toString(), 300));
        }
        var choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("architect model returned no choices");
        }
        var msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (msg == null || !msg.has("content") || msg.get("content").isJsonNull() || msg.get("content").getAsString().isBlank()) {
            throw new IOException("architect model returned an empty reply");
        }
        return msg.get("content").getAsString();
    }

    private static void saveDesign(String request, String reply) {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("remy").resolve("designs");
            Files.createDirectories(dir);
            String slug = request.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            slug = slug.substring(0, Math.min(40, slug.length())).replaceAll("^-|-$", "");
            Files.writeString(dir.resolve(System.currentTimeMillis() / 1000 + "-" + slug + ".json"), Blueprint.stripFences(reply));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not save design: {}", e.toString());
        }
    }

    static String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "...";
    }

    static String systemPrompt(Config cfg) {
        return """
                You are a master Minecraft builder designing a structure that a companion will build in a modded world.
                Reply with ONE JSON object and nothing else:
                {"name": "...", "summary": "one sentence", "palette": {"<char>": "<block state>"}, "layers": [[row, row, ...], ...]}

                Coordinates
                - layers[0] is the bottom layer and sits at ground level (it replaces the ground blocks): use it for the foundation/floor.
                - Each layer is a list of rows. Row 0 is the NORTH edge, the last row is the SOUTH edge. Characters in a row go WEST to EAST.
                - Every row in every layer must have exactly the same length (the width), and every layer the same number of rows (the depth).
                - The main entrance faces SOUTH (last row). It will be rotated to face the player automatically.
                - Max size: %d wide (x) x %d tall (y) x %d deep (z). Prefer under 3000 blocks total.

                Palette
                - Keys are single characters. ' ' (space) is reserved and means "leave the terrain as it is" (use it around irregular shapes).
                - Map a character to "minecraft:air" for space that must be cleared (inside rooms, doorways, under roofs).
                - Values are block states using ONLY block ids from the list given (namespace:path), with optional properties:
                  stairs: facing=north|south|east|west (the side with the tall back / the direction you walk to go up), half=bottom|top (top = upside-down, for eaves and arches)
                  slabs: type=bottom|top|double. logs/pillars: axis=x|y|z. doors: facing, half=lower (the upper half is added for you), hinge=left|right.
                  trapdoors: facing, half=bottom|top, open=true|false (open trapdoors against walls make shutters). lanterns: hanging=true under ceilings.
                - Use different characters for the same block with different properties.

                Design quality
                - Build like an experienced survival builder: a raised foundation or plinth, log or stone pillars at corners, framed walls with
                  inset panels, windows with frames, sills or shutters, a pitched or layered roof with a one-block overhang made of stairs and slabs,
                  upside-down stairs for eaves, a chimney or dormer when it suits the style, and lighting so mobs don't spawn inside.
                - Mix 2-4 closely related textures for large surfaces so walls aren't flat. Pick blocks by the colours given; keep a coherent palette.
                - Prefer blocks marked "craftable" (the companion gathers and crafts materials in survival). Use modded blocks when they fit the request.
                - Clear interior space with air. Add a simple interior (floor pattern, a table, chests, a bed) when there is room.
                """.formatted(cfg.maxX, cfg.maxY, cfg.maxZ);
    }

    // ---------------------------------------------------------------- shortlist

    record Family(String id, String material, String look, List<String> members, boolean craftable, int score) {
    }

    private static final Set<String> STAPLES = Set.of(
            "minecraft:oak", "minecraft:spruce", "minecraft:dark_oak", "minecraft:birch", "minecraft:stone",
            "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:stone_brick", "minecraft:mossy_stone_brick",
            "minecraft:brick", "minecraft:deepslate_brick", "minecraft:glass", "minecraft:glass_pane", "minecraft:lantern",
            "minecraft:torch", "minecraft:chain", "minecraft:smooth_stone", "minecraft:white_wool", "minecraft:chest",
            "minecraft:crafting_table", "minecraft:barrel", "minecraft:flower_pot", "minecraft:red_bed", "minecraft:hay",
            "minecraft:polished_andesite", "minecraft:calcite", "minecraft:mud_brick", "minecraft:packed_mud");

    static List<Family> shortlist(String request, BlockCatalog.Snapshot catalog, int limit) {
        Set<String> words = tokens(request);
        boolean wantsMods = words.contains("modded") || words.contains("mods") || words.contains("every") || words.contains("all");
        List<Family> scored = new ArrayList<>();
        for (Map.Entry<String, List<BlockCatalog.Entry>> f : catalog.families().entrySet()) {
            List<BlockCatalog.Entry> members = f.getValue();
            BlockCatalog.Entry rep = members.stream()
                    .filter(e -> e.shape.equals("full") || e.shape.equals("pillar")).findFirst().orElse(members.get(0));
            String look = BlockCatalog.describeColor(rep.color) + " " + rep.color;
            boolean craftable = members.stream().anyMatch(e -> e.craftable);
            String fam = f.getKey();
            String famPath = fam.substring(fam.indexOf(':') + 1);
            String modName = catalog.modNames.getOrDefault(rep.mod, rep.mod).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String w : words) {
                if (w.length() < 3) continue;
                if (famPath.contains(w)) score += 6;
                if (rep.material.equals(w)) score += 4;
                if (look.contains(w)) score += 3;
                if (rep.mod.equals(w) || modName.contains(w)) score += 4;
                for (BlockCatalog.Entry e : members) {
                    if (e.id.contains(w)) { score += 1; break; }
                }
            }
            if (STAPLES.contains(fam)) score += 3;
            if (craftable) score += 2;
            Set<String> shapes = new HashSet<>();
            members.forEach(e -> shapes.add(e.shape));
            score += Math.min(3, shapes.size() - 1);
            if (shapes.size() == 1 && (shapes.contains("plant") || shapes.contains("other"))) score -= 3;
            if (wantsMods && !rep.mod.equals("minecraft")) score += 2;
            List<String> ids = new ArrayList<>();
            members.forEach(e -> ids.add(e.id.substring(e.id.indexOf(':') + 1)));
            scored.add(new Family(fam, rep.material, look, ids, craftable, score));
        }
        scored.sort(Comparator.comparingInt(Family::score).reversed().thenComparing(Family::id));
        // Keep variety: don't let one mod take everything unless the request named it.
        List<Family> out = new ArrayList<>();
        Map<String, Integer> perMod = new LinkedHashMap<>();
        int perModCap = Math.max(20, limit / 3);
        for (Family f : scored) {
            if (out.size() >= limit) break;
            String mod = f.id().substring(0, f.id().indexOf(':'));
            if (perMod.getOrDefault(mod, 0) >= perModCap && f.score() < 8) continue;
            perMod.merge(mod, 1, Integer::sum);
            out.add(f);
        }
        return out;
    }

    static String paletteText(List<Family> families) {
        StringBuilder sb = new StringBuilder();
        for (Family f : families) {
            String ns = f.id().substring(0, f.id().indexOf(':'));
            sb.append(f.id()).append(" | ").append(f.material()).append(" | ").append(f.look()).append(" | ")
                    .append(ns).append(": ").append(String.join(" ", f.members()));
            if (f.craftable()) sb.append(" | craftable");
            sb.append('\n');
        }
        return sb.toString();
    }

    static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (w.isBlank()) continue;
            out.add(w);
            if (w.endsWith("s") && w.length() > 3) out.add(w.substring(0, w.length() - 1));
        }
        return out;
    }
}
