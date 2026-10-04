package com.brandopakel.remy.playerengine.architect;

import com.brandopakel.remy.playerengine.RemyPlayerEngineAdapter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** Chat/command glue for the architect: design -> pending -> preview/build/undo. */
public final class ArchitectFlow {
    private ArchitectFlow() {
    }

    public static Path designsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("remy").resolve("designs");
    }

    public static void catalog(ServerPlayerEntity owner, boolean rebuild) {
        MinecraftServer server = owner.getServer();
        UUID id = owner.getUuid();
        tell(owner, rebuild ? "Re-reading every block in the pack..." : "Loading my block catalog...");
        BlockCatalog.get(server, rebuild).whenComplete((s, err) -> server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) return;
            if (err != null) {
                tell(p, "Catalog failed: " + err.getMessage());
                return;
            }
            Map<String, Integer> perMod = new TreeMap<>();
            int textured = 0, craftable = 0;
            for (BlockCatalog.Entry e : s.blocks) {
                perMod.merge(e.mod, 1, Integer::sum);
                if (e.textured) textured++;
                if (e.craftable) craftable++;
            }
            tell(p, "I know " + s.blocks.size() + " placeable blocks in " + s.families().size() + " families from "
                    + perMod.size() + " mods (" + textured + " with texture colours, " + craftable + " craftable). Saved to config/remy/catalog.json.");
        }));
    }

    public static void design(ServerPlayerEntity owner, String request) {
        MinecraftServer server = owner.getServer();
        UUID id = owner.getUuid();
        Architect.Config cfg = Architect.config();
        if (cfg.usable().isEmpty()) {
            tell(owner, "I need a design model for that: " + cfg.keyHint() + ". Meanwhile: /remyengine blueprint <name> loads a saved design ("
                    + String.join(", ", savedDesigns()) + ").");
            return;
        }
        tell(owner, "Sketching \"" + request + "\" with " + Architect.modelsLabel(cfg) + "... free models are slow, give me a few minutes.");
        BlockCatalog.get(server, false)
                .thenCompose(cat -> Architect.design(request, cat))
                .whenComplete((res, err) -> server.execute(() -> {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p == null) return;
                    if (err != null || res == null) {
                        tell(p, "The design call failed: " + (err == null ? "no result" : err.getMessage()));
                        return;
                    }
                    if (res.blueprint() == null) {
                        tell(p, "I couldn't produce a valid design: " + res.error());
                        return;
                    }
                    RemyPlayerEngineAdapter.LOGGER.info("Architect design '{}' ok; usage {}", request, res.usage());
                    offer(p, res.blueprint(), res.warnings());
                }));
    }

    static void offer(ServerPlayerEntity p, Blueprint bp, List<String> warnings) {
        BuildManager.setPending(p, bp);
        tell(p, "Design ready: " + bp.describe() + (bp.summary.isBlank() ? "" : " - " + bp.summary));
        if (!warnings.isEmpty()) {
            tell(p, "(" + String.join("; ", warnings) + ")");
        }
        tell(p, "Face where you want it and say \"remy preview\" (instant, undo-able) or \"remy build it\" (I gather and build in survival).");
    }

    public static void loadBlueprint(ServerPlayerEntity owner, String name) {
        Path file = designsDir().resolve(name.endsWith(".json") ? name : name + ".json");
        try {
            if (!Files.exists(file)) {
                tell(owner, "No design named " + name + ". Saved designs: " + String.join(", ", savedDesigns()));
                return;
            }
            Blueprint.Parsed parsed = Architect.parse(Files.readString(file), Architect.config());
            if (!parsed.ok()) {
                tell(owner, "That design doesn't validate: " + String.join("; ", parsed.errors()));
                return;
            }
            offer(owner, parsed.blueprint(), parsed.warnings());
        } catch (IOException e) {
            tell(owner, "Couldn't read " + file.getFileName() + ": " + e.getMessage());
        }
    }

    public static List<String> savedDesigns() {
        try (Stream<Path> s = Files.list(designsDir())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void preview(ServerPlayerEntity owner) {
        Blueprint bp = BuildManager.pending(owner);
        if (bp == null) {
            tell(owner, "There's no design yet. Ask me to build something first.");
            return;
        }
        BuildManager.buildInstant(owner, bp);
    }

    public static void buildForReal(ServerPlayerEntity owner) {
        Blueprint bp = BuildManager.pending(owner);
        if (bp == null) {
            tell(owner, "There's no design yet. Ask me to build something first.");
            return;
        }
        RemyPlayerEngineAdapter.findRemyFor(owner).ifPresentOrElse(
                remy -> BuildManager.buildSurvival(owner, remy, bp),
                () -> tell(owner, "Spawn me first with /remyengine spawn."));
    }

    public static void undo(ServerPlayerEntity owner) {
        if (!BuildManager.undo(owner)) {
            tell(owner, "There's nothing for me to undo.");
        }
    }

    static void tell(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal("<Remy> " + msg), false);
    }
}
