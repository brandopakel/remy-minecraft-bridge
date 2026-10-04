package com.brandopakel.remy.playerengine.architect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads what a block looks like straight from the mod files: blockstate JSON -> model JSON
 * (following parents) -> texture PNG -> average colour plus a dark and a light tone.
 *
 * Works for any mod that ships its assets in its jar (all of them do), so the catalog
 * covers the whole pack without a client or a resource reload. On a dedicated server the
 * vanilla textures are absent; callers fall back to the block's map colour.
 */
public final class TextureColors {
    /** Average colour and two tones (darker/lighter halves by luminance), as 0xRRGGBB. */
    public record Tones(int average, int dark, int light, float coverage) {
    }

    private static final String[] TEXTURE_KEYS = {
            "all", "side", "texture", "front", "particle", "top", "end", "wool", "cross", "pattern", "plant", "bottom"};

    private final Map<String, List<Path>> rootsByNamespace = new HashMap<>();
    private final Map<String, Optional<Tones>> textureCache = new HashMap<>();

    public TextureColors() {
        this(defaultRoots());
    }

    /** For checks: index the given asset roots (directories or mounted jar roots). */
    public TextureColors(List<Path> roots) {
        for (Path root : roots) {
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) {
                continue;
            }
            try (var dirs = Files.list(assets)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String ns = dir.getFileName().toString().replace("/", "");
                    rootsByNamespace.computeIfAbsent(ns, k -> new ArrayList<>()).add(root);
                });
            } catch (IOException ignored) {
                // unreadable jar root; skip it
            }
        }
    }

    private static List<Path> defaultRoots() {
        List<Path> roots = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            roots.addAll(mod.getRootPaths());
        }
        return roots;
    }

    public int namespaceCount() {
        return rootsByNamespace.size();
    }

    /** Tones for block id like "minecraft:oak_planks", if its assets can be resolved. */
    public Optional<Tones> forBlock(String blockId) {
        String ns = namespace(blockId);
        String path = path(blockId);
        Optional<JsonObject> blockstate = readJson(ns, "blockstates/" + path + ".json");
        if (blockstate.isEmpty()) {
            return Optional.empty();
        }
        String model = firstModel(blockstate.get());
        if (model == null) {
            return Optional.empty();
        }
        String texture = resolveTexture(model);
        if (texture == null) {
            return Optional.empty();
        }
        return textureCache.computeIfAbsent(texture, this::readTones);
    }

    static String firstModel(JsonObject blockstate) {
        if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
            JsonObject variants = blockstate.getAsJsonObject("variants");
            // Prefer the plain/default variant if present.
            JsonElement v = variants.has("") ? variants.get("") : variants.entrySet().stream()
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            return modelOf(v);
        }
        if (blockstate.has("multipart") && blockstate.get("multipart").isJsonArray()) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    String m = modelOf(part.getAsJsonObject().get("apply"));
                    if (m != null) {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static String modelOf(JsonElement v) {
        if (v == null) {
            return null;
        }
        if (v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
            v = v.getAsJsonArray().get(0);
        }
        if (v.isJsonObject() && v.getAsJsonObject().has("model")) {
            return v.getAsJsonObject().get("model").getAsString();
        }
        return null;
    }

    /** Follows the model's parent chain, merging texture variables, and returns a texture id. */
    String resolveTexture(String modelId) {
        Map<String, String> textures = new LinkedHashMap<>();
        String current = modelId;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            String ns = namespace(current);
            String p = path(current);
            if (ns.equals("minecraft") && (p.equals("block/block") || p.equals("block/cube") || p.startsWith("builtin/"))) {
                break;
            }
            Optional<JsonObject> model = readJson(ns, "models/" + p + ".json");
            if (model.isEmpty()) {
                break;
            }
            JsonObject m = model.get();
            if (m.has("textures") && m.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("textures").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            current = m.has("parent") ? m.get("parent").getAsString() : null;
        }
        if (textures.isEmpty()) {
            return null;
        }
        String chosen = null;
        for (String key : TEXTURE_KEYS) {
            if (textures.containsKey(key)) {
                chosen = textures.get(key);
                break;
            }
        }
        if (chosen == null) {
            chosen = textures.values().iterator().next();
        }
        for (int i = 0; i < 8 && chosen != null && chosen.startsWith("#"); i++) {
            chosen = textures.get(chosen.substring(1));
        }
        return chosen == null || chosen.startsWith("#") ? null : chosen;
    }

    private Optional<Tones> readTones(String textureId) {
        String ns = namespace(textureId);
        String p = path(textureId);
        for (Path root : rootsByNamespace.getOrDefault(ns, List.of())) {
            Path png = root.resolve("assets").resolve(ns).resolve("textures").resolve(p + ".png");
            if (!Files.isRegularFile(png)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(png)) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    return Optional.of(tones(img));
                }
            } catch (IOException | RuntimeException ignored) {
                // corrupt or unsupported png; try the next root
            }
        }
        return Optional.empty();
    }

    /** Average colour over visible pixels; for animated strips only the first frame. */
    static Tones tones(BufferedImage img) {
        int w = img.getWidth();
        int h = Math.min(img.getHeight(), w);
        long r = 0, g = 0, b = 0;
        int n = 0;
        int total = w * h;
        int[] lum = new int[total];
        int[] rgbs = new int[total];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                if (a < 32) {
                    continue;
                }
                int cr = (argb >> 16) & 0xff, cg = (argb >> 8) & 0xff, cb = argb & 0xff;
                r += cr;
                g += cg;
                b += cb;
                rgbs[n] = argb & 0xffffff;
                lum[n] = (cr * 299 + cg * 587 + cb * 114) / 1000;
                n++;
            }
        }
        if (n == 0) {
            return new Tones(0, 0, 0, 0f);
        }
        int avg = rgb((int) (r / n), (int) (g / n), (int) (b / n));
        int avgLum = 0;
        for (int i = 0; i < n; i++) {
            avgLum += lum[i];
        }
        avgLum /= n;
        long dr = 0, dg = 0, db = 0, lr = 0, lg = 0, lb = 0;
        int dn = 0, ln = 0;
        for (int i = 0; i < n; i++) {
            int c = rgbs[i];
            if (lum[i] <= avgLum) {
                dr += (c >> 16) & 0xff;
                dg += (c >> 8) & 0xff;
                db += c & 0xff;
                dn++;
            } else {
                lr += (c >> 16) & 0xff;
                lg += (c >> 8) & 0xff;
                lb += c & 0xff;
                ln++;
            }
        }
        int dark = dn == 0 ? avg : rgb((int) (dr / dn), (int) (dg / dn), (int) (db / dn));
        int light = ln == 0 ? avg : rgb((int) (lr / ln), (int) (lg / ln), (int) (lb / ln));
        return new Tones(avg, dark, light, n / (float) total);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private Optional<JsonObject> readJson(String ns, String relative) {
        for (Path root : rootsByNamespace.getOrDefault(ns, List.of())) {
            Path file = root.resolve("assets").resolve(ns).resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement e = JsonParser.parseReader(reader);
                if (e.isJsonObject()) {
                    return Optional.of(e.getAsJsonObject());
                }
            } catch (IOException | RuntimeException ignored) {
                // malformed json in some mod; skip
            }
        }
        return Optional.empty();
    }

    static String namespace(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    static String path(String id) {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xffffff);
    }
}
