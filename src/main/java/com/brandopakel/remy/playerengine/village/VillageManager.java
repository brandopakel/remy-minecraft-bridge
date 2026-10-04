package com.brandopakel.remy.playerengine.village;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * "Remy, build a village here": runs the agentcraft settlement generator (bytewife/agentcraft,
 * GDMC 2021, patched for 1.20 by village/agentcraft-remy.patch) against this world through
 * {@link GdmcBridge}. The bridge only exists while a village is being generated.
 */
public final class VillageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("remy_village");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class Config {
        /** Command used to start Python; on Windows "py -3" is the safest default. */
        public List<String> python = new ArrayList<>(List.of("py", "-3"));
        /** Folder containing agentcraft's run.py (relative paths resolve against the game folder). */
        public String agentcraftDir = "remy/agentcraft";
        public int port = 9000;
        public int steps = 1000;
        public int seconds = 300;
        public double frameSeconds = 0.25;
        public int defaultRadius = 48;
        public int maxRadius = 100;
    }

    private static Process running;
    private static GdmcBridge bridge;
    private static UUID runningOwner;

    private VillageManager() {
    }

    public static Config config() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("remy").resolve("village.json");
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

    private static Path agentcraftDir(Config cfg) {
        Path p = Path.of(cfg.agentcraftDir);
        return p.isAbsolute() ? p : FabricLoader.getInstance().getGameDir().resolve(p);
    }

    public static synchronized boolean isRunning() {
        return running != null && running.isAlive();
    }

    public static synchronized void stop(ServerPlayerEntity owner) {
        if (running != null) {
            running.destroy();
        }
        cleanup();
        owner.sendMessage(Text.literal("<Remy> Stopped the village builder."), false);
    }

    private static synchronized void cleanup() {
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        running = null;
        runningOwner = null;
    }

    /** Installs agentcraft's Python requirements with pip (one-time setup). */
    public static void setup(MinecraftServer server, ServerPlayerEntity owner) {
        Config cfg = config();
        Path dir = agentcraftDir(cfg);
        if (!Files.exists(dir.resolve("run.py"))) {
            owner.sendMessage(Text.literal("<Remy> agentcraft isn't installed at " + dir + "."), false);
            return;
        }
        List<String> cmd = new ArrayList<>(cfg.python);
        cmd.addAll(List.of("-m", "pip", "install", "--user", "-r", "requirements.txt"));
        runLogged(server, owner, cmd, dir, "village-setup", null,
                "Installing the village builder's Python packages...", "Village builder setup finished.");
    }

    public static synchronized void start(MinecraftServer server, ServerPlayerEntity owner, int radius) {
        if (isRunning()) {
            owner.sendMessage(Text.literal("<Remy> A village is already being built. /remyengine village stop to cancel."), false);
            return;
        }
        Config cfg = config();
        Path dir = agentcraftDir(cfg);
        if (!Files.exists(dir.resolve("run.py"))) {
            owner.sendMessage(Text.literal("<Remy> The village builder (agentcraft) isn't installed at " + dir
                    + ". See village/README.md in remy-minecraft-bridge."), false);
            return;
        }
        int r = Math.max(24, Math.min(radius <= 0 ? cfg.defaultRadius : radius, cfg.maxRadius));
        BlockPos p = owner.getBlockPos();
        int x1 = p.getX() - r, z1 = p.getZ() - r, x2 = p.getX() + r, z2 = p.getZ() + r;
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        try {
            bridge = new GdmcBridge(server, owner.getServerWorld(), token, new int[]{x1, 0, z1, x2, 255, z2});
            bridge.start(cfg.port);
        } catch (IOException e) {
            cleanup();
            owner.sendMessage(Text.literal("<Remy> Couldn't open the local builder port " + cfg.port + ": " + e.getMessage()), false);
            return;
        }
        List<String> cmd = new ArrayList<>(cfg.python);
        cmd.addAll(List.of("run.py", "-a", x1 + "," + z1 + "," + x2 + "," + z2,
                "-t", Integer.toString(cfg.seconds), "-s", Integer.toString(cfg.steps),
                "-f", Double.toString(cfg.frameSeconds), "--nochronicle", "--leavesign"));
        runningOwner = owner.getUuid();
        owner.sendMessage(Text.literal("<Remy> Planning a village from " + x1 + "," + z1 + " to " + x2 + "," + z2
                + ". Settlers will appear as armor stands; stand back and watch it grow!"), false);
        java.util.Map<String, String> env = java.util.Map.of(
                "REMY_GDMC_URL", "http://127.0.0.1:" + cfg.port,
                "REMY_GDMC_TOKEN", token,
                "PYTHONIOENCODING", "utf-8",
                "PYTHONUNBUFFERED", "1");
        running = runLogged(server, owner, cmd, dir, "village", env, null, null);
    }

    private static Process runLogged(MinecraftServer server, ServerPlayerEntity owner, List<String> cmd, Path dir,
                                     String logName, java.util.Map<String, String> env, String startMsg, String doneMsg) {
        UUID ownerId = owner.getUuid();
        Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("remy-" + logName + ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            if (env != null) pb.environment().putAll(env);
            Process proc = pb.start();
            if (startMsg != null) owner.sendMessage(Text.literal("<Remy> " + startMsg), false);
            Thread t = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        out.println(line);
                        out.flush();
                        String l = line;
                        if (l.contains("is building") || l.contains("main street") || l.contains("finished")
                                || l.contains("Error") || l.contains("Traceback")) {
                            server.execute(() -> tell(server, ownerId, l.trim()));
                        }
                    }
                    int code = proc.waitFor();
                    server.execute(() -> {
                        if ("village".equals(logName)) cleanup();
                        tell(server, ownerId, code == 0
                                ? (doneMsg != null ? doneMsg : "Village finished!")
                                : "The " + logName + " process exited with code " + code + " (see logs/remy-" + logName + ".log).");
                    });
                } catch (IOException | InterruptedException e) {
                    server.execute(() -> {
                        if ("village".equals(logName)) cleanup();
                        tell(server, ownerId, "The " + logName + " process failed: " + e.getMessage());
                    });
                }
            }, "remy-" + logName);
            t.setDaemon(true);
            t.start();
            return proc;
        } catch (IOException e) {
            if ("village".equals(logName)) cleanup();
            owner.sendMessage(Text.literal("<Remy> Couldn't start Python (" + String.join(" ", cmd.subList(0, Math.min(2, cmd.size())))
                    + "): " + e.getMessage() + ". Install Python 3 or set \"python\" in config/remy/village.json."), false);
            return null;
        }
    }

    private static void tell(MinecraftServer server, UUID ownerId, String msg) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(ownerId);
        if (p != null) p.sendMessage(Text.literal("<Remy> " + msg), false);
    }

    /** Stop the builder if the world shuts down mid-run. */
    public static synchronized void onServerStopping() {
        if (running != null) {
            running.destroy();
            try {
                running.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        cleanup();
    }
}
