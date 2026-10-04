package com.brandopakel.remy.playerengine.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-command setup for Remy's free local brain on Windows: installs Ollama (official
 * installer from ollama.com, per-user, no admin), pulls the configured tev1 model through
 * Ollama's local API, and runs one test decision. Every step is fixed; nothing here takes
 * user-supplied commands. Progress goes to chat and logs/remy-brain-setup.log.
 */
public final class BrainSetup {
    private static final Logger LOGGER = LoggerFactory.getLogger("remy_brain_setup");
    public static final String INSTALLER_URL = "https://ollama.com/download/OllamaSetup.exe";
    private static final String OLLAMA = "http://127.0.0.1:11434";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BrainSetup() {
    }

    /** Request file: if present at game start, setup runs right away (no world needed). */
    public static final String REQUEST_FILE = "setup-brain.request";

    /**
     * Runs setup at game start (title screen) when config/remy/setup-brain.request exists.
     * Loading a world is the heaviest moment for memory, so this lets setup run without one.
     * Progress is written to logs/remy-brain-setup.log.
     */
    public static void startIfRequested() {
        Path request = FabricLoader.getInstance().getConfigDir().resolve("remy").resolve(REQUEST_FILE);
        if (!Files.exists(request)) {
            return;
        }
        try {
            Files.delete(request);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}: {}", request, e.toString());
            return;
        }
        LOGGER.info("Brain setup requested by {}; running at startup", request);
        start(null, null);
    }

    public static void start(MinecraftServer server, ServerPlayerEntity owner) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (owner != null) {
                owner.sendMessage(Text.literal("<Remy> Brain setup is already running."), false);
            }
            return;
        }
        UUID ownerId = owner == null ? null : owner.getUuid();
        Thread t = new Thread(() -> {
            Path logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("remy-brain-setup.log");
            try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8), true)) {
                run(server, ownerId, log);
            } catch (Exception e) {
                LOGGER.warn("Brain setup failed", e);
                tell(server, ownerId, "Brain setup failed: " + e.getMessage() + " (see logs/remy-brain-setup.log)");
            } finally {
                RUNNING.set(false);
            }
        }, "remy-brain-setup");
        t.setDaemon(true);
        t.start();
    }

    private static void run(MinecraftServer server, UUID ownerId, PrintWriter log) throws Exception {
        RemyBrain.reloadConfig();
        RemyBrain.Provider provider = RemyBrain.config().providers.stream()
                .filter(p -> p.url != null && p.url.contains("11434"))
                .findFirst().orElse(null);
        String model = provider != null ? provider.model : "tev1:0.8b";
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

        // 1. Ollama present?
        String version = ollamaVersion();
        if (version == null) {
            if (!windows) {
                tell(server, ownerId, "Ollama isn't running. On this OS install it from ollama.com, then run this again.");
                return;
            }
            tell(server, ownerId, "Ollama isn't installed yet. Downloading the official installer from ollama.com...");
            Path installer = FabricLoader.getInstance().getGameDir().resolve("remy").resolve("OllamaSetup.exe");
            download(server, ownerId, log, INSTALLER_URL, installer);
            tell(server, ownerId, "Installing Ollama (just for your Windows account, no admin needed)...");
            int code = runLogged(log, List.of(installer.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-"));
            log.println("installer exit code " + code);
            if (code != 0) {
                tell(server, ownerId, "The Ollama installer exited with code " + code + ". See logs/remy-brain-setup.log.");
                return;
            }
            version = waitForOllama(server, ownerId, log);
            if (version == null) {
                tell(server, ownerId, "Ollama installed but didn't start. Open \"Ollama\" from the Start menu once, then run this again.");
                return;
            }
        }
        tell(server, ownerId, "Ollama " + version + " is running.");
        if (!atLeast(version, 0, 35)) {
            tell(server, ownerId, "tev1 needs Ollama 0.35 or newer. Update Ollama (it updates itself from the tray icon), then run this again.");
            return;
        }

        // 2. Pull the model.
        if (!hasModel(model)) {
            tell(server, ownerId, "Downloading the " + model + " model...");
            pull(server, ownerId, log, model);
        }
        tell(server, ownerId, model + " is ready.");

        // 3. Test one decision through the same endpoint Remy uses.
        String answer = testDecision(model, log);
        if (answer == null) {
            tell(server, ownerId, "The test decision didn't come back. See logs/remy-brain-setup.log.");
            return;
        }
        tell(server, ownerId, "Test decision: \"chop some birch for me\" -> " + answer + ". My local brain is online!");
    }

    // ---------------------------------------------------------------- steps

    static String ollamaVersion() {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/version"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return null;
            }
            return JsonParser.parseString(r.body()).getAsJsonObject().get("version").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String waitForOllama(MinecraftServer server, UUID ownerId, PrintWriter log) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            String v = ollamaVersion();
            if (v != null) {
                return v;
            }
            if (i == 5) {
                // The installer normally starts the tray app; start it ourselves if it didn't.
                Path base = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Programs", "Ollama");
                Path app = base.resolve("ollama app.exe");
                Path cli = base.resolve("ollama.exe");
                try {
                    if (Files.exists(app)) {
                        log.println("starting " + app);
                        new ProcessBuilder(app.toString()).start();
                    } else if (Files.exists(cli)) {
                        log.println("starting " + cli + " serve");
                        new ProcessBuilder(cli.toString(), "serve").redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    } else {
                        log.println("no Ollama executable found under " + base);
                    }
                } catch (IOException e) {
                    log.println("could not start Ollama: " + e);
                }
            }
            Thread.sleep(2000);
        }
        return null;
    }

    private static void download(MinecraftServer server, UUID ownerId, PrintWriter log, String url, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            throw new IOException("download returned HTTP " + r.statusCode());
        }
        long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        log.println("downloading " + url + " (" + total + " bytes) -> " + dest);
        long done = 0;
        int lastPct = -1;
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 20 != lastPct / 20) {
                        lastPct = pct;
                        tell(server, ownerId, "Ollama download " + pct + "% (" + (done >> 20) + " of " + (total >> 20) + " MB)");
                    }
                }
            }
        }
        Files.move(part, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.println("downloaded " + done + " bytes");
    }

    private static int runLogged(PrintWriter log, List<String> cmd) throws IOException, InterruptedException {
        log.println("running " + String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                log.println(line);
            }
        }
        return p.waitFor();
    }

    private static boolean hasModel(String model) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            String want = model.contains(":") ? model : model + ":latest";
            for (var m : JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("models")) {
                String name = m.getAsJsonObject().get("name").getAsString();
                if (name.equals(want) || name.equals(model)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // treat as missing
        }
        return false;
    }

    private static void pull(MinecraftServer server, UUID ownerId, PrintWriter log, String model) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/pull"))
                        .timeout(Duration.ofMinutes(60)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int lastBucket = -1;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                log.println(line);
                JsonObject ev = JsonParser.parseString(line).getAsJsonObject();
                if (ev.has("error")) {
                    throw new IOException("Ollama: " + ev.get("error").getAsString());
                }
                if (ev.has("total") && ev.has("completed")) {
                    long total = ev.get("total").getAsLong();
                    long completed = ev.get("completed").getAsLong();
                    if (total > 50_000_000L) {
                        int pct = (int) (completed * 100 / Math.max(1, total));
                        if (pct / 25 != lastBucket) {
                            lastBucket = pct / 25;
                            tell(server, ownerId, model + " download " + pct + "% (" + (completed >> 20) + " of " + (total >> 20) + " MB)");
                        }
                    }
                }
            }
        }
        if (r.statusCode() != 200) {
            throw new IOException("pull returned HTTP " + r.statusCode());
        }
    }

    private static String testDecision(String model, PrintWriter log) {
        try {
            JsonObject body = RemyBrain.decisionRequest("chop some birch for me", "test",
                    List.of("minecraft:birch_log", "minecraft:oak_log", "minecraft:stone"));
            body.addProperty("model", model);
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/v1/systemone"))
                            .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            log.println("systemone HTTP " + r.statusCode() + ": " + r.body());
            if (r.statusCode() != 200) {
                return null;
            }
            JsonObject json = JsonParser.parseString(r.body()).getAsJsonObject();
            String intent = RemyBrain.choice(json, "intent");
            String target = RemyBrain.choice(json, "target");
            return (intent == null ? "?" : intent) + (target == null ? "" : " " + target);
        } catch (Exception e) {
            log.println("test decision failed: " + e);
            return null;
        }
    }

    static boolean atLeast(String version, int major, int minor) {
        try {
            String[] parts = version.replaceAll("[^0-9.].*$", "").split("\\.");
            int ma = Integer.parseInt(parts[0]);
            int mi = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return ma > major || (ma == major && mi >= minor);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void tell(MinecraftServer server, UUID ownerId, String msg) {
        LOGGER.info(msg);
        if (server == null || ownerId == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(ownerId);
            if (p != null) {
                p.sendMessage(Text.literal("<Remy> " + msg), false);
            }
        });
    }
}
