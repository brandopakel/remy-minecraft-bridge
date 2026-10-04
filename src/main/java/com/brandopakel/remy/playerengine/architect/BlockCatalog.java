package com.brandopakel.remy.playerengine.architect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.AbstractGlassBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.recipe.Recipe;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Every placeable block in the running pack, described the way a builder thinks about it:
 * what it looks like (colours read from its texture), what it's made of, its shape, which
 * "family" it belongs to (oak planks/stairs/slab/fence...), and whether it can be crafted.
 *
 * Built from the live registries, so it covers whatever modpack Remy is in. Cached to
 * config/remy/catalog.json and rebuilt when the mod list changes.
 */
public final class BlockCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger("remy_catalog");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int VERSION = 1;

    public static final class Entry {
        public String id;
        public String mod;
        public String family;
        public String shape;
        public String material;
        public String color;
        public String dark;
        public String light;
        public boolean craftable;
        public boolean textured;
    }

    public static final class Snapshot {
        public int version = VERSION;
        public String modsKey;
        public List<Entry> blocks = new ArrayList<>();
        public Map<String, String> modNames = new TreeMap<>();

        public Map<String, List<Entry>> families() {
            Map<String, List<Entry>> out = new LinkedHashMap<>();
            for (Entry e : blocks) {
                out.computeIfAbsent(e.family, k -> new ArrayList<>()).add(e);
            }
            return out;
        }

        public Entry find(String id) {
            for (Entry e : blocks) {
                if (e.id.equals(id)) {
                    return e;
                }
            }
            return null;
        }
    }

    private static volatile Snapshot current;
    private static CompletableFuture<Snapshot> building;

    private BlockCatalog() {
    }

    public static Snapshot currentOrNull() {
        return current;
    }

    /** Returns the catalog, loading the cache or building it off-thread the first time. */
    public static synchronized CompletableFuture<Snapshot> get(MinecraftServer server, boolean rebuild) {
        if (current != null && !rebuild) {
            return CompletableFuture.completedFuture(current);
        }
        if (building != null && !building.isDone()) {
            return building;
        }
        String modsKey = modsKey();
        Path cache = cacheFile();
        if (!rebuild) {
            Snapshot cached = readCache(cache, modsKey);
            if (cached != null) {
                current = cached;
                return CompletableFuture.completedFuture(cached);
            }
        }
        // Registry and recipe reads are quick and done on the server thread; texture work is not.
        Set<Item> craftable = craftableItems(server);
        List<Block> blocks = new ArrayList<>();
        for (Block b : Registries.BLOCK) {
            blocks.add(b);
        }
        building = CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            Snapshot s = build(blocks, craftable, new TextureColors());
            s.modsKey = modsKey;
            writeCache(cache, s);
            current = s;
            LOGGER.info("Remy block catalog: {} blocks, {} families in {} ms", s.blocks.size(),
                    s.families().size(), System.currentTimeMillis() - t0);
            return s;
        });
        return building;
    }

    static Set<Item> craftableItems(MinecraftServer server) {
        Set<Item> out = new HashSet<>();
        if (server == null) {
            return out;
        }
        for (Recipe<?> recipe : server.getRecipeManager().values()) {
            try {
                Item item = recipe.getOutput(server.getRegistryManager()).getItem();
                if (item != Items.AIR) {
                    out.add(item);
                }
            } catch (RuntimeException ignored) {
                // some modded recipes compute output lazily; skip them
            }
        }
        return out;
    }

    static Snapshot build(List<Block> blocks, Set<Item> craftable, TextureColors textures) {
        Snapshot s = new Snapshot();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            s.modNames.put(mod.getMetadata().getId(), mod.getMetadata().getName());
        }
        for (Block block : blocks) {
            Item item = block.asItem();
            if (item == Items.AIR) {
                continue; // not placeable by hand (technical, wall variants, fluids...)
            }
            Identifier id = Registries.BLOCK.getId(block);
            BlockState state = block.getDefaultState();
            Entry e = new Entry();
            e.id = id.toString();
            e.mod = id.getNamespace();
            e.shape = shape(block, state);
            e.family = id.getNamespace() + ":" + family(id.getPath());
            e.material = material(state, id.getPath());
            e.craftable = craftable.contains(item);
            var tones = textures.forBlock(e.id);
            if (tones.isPresent() && tones.get().coverage() > 0f) {
                e.color = TextureColors.hex(tones.get().average());
                e.dark = TextureColors.hex(tones.get().dark());
                e.light = TextureColors.hex(tones.get().light());
                e.textured = true;
            } else {
                int map = state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).color;
                e.color = TextureColors.hex(map);
                e.dark = e.color;
                e.light = e.color;
            }
            s.blocks.add(e);
        }
        return s;
    }

    // ---------------------------------------------------------------- classification

    static String shape(Block b, BlockState s) {
        if (b instanceof StairsBlock) return "stairs";
        if (b instanceof SlabBlock) return "slab";
        if (b instanceof WallBlock) return "wall";
        if (b instanceof FenceGateBlock) return "fence_gate";
        if (b instanceof FenceBlock) return "fence";
        if (b instanceof DoorBlock) return "door";
        if (b instanceof TrapdoorBlock) return "trapdoor";
        if (b instanceof PaneBlock) return "pane";
        if (b instanceof CarpetBlock) return "carpet";
        if (b instanceof ButtonBlock) return "button";
        if (b instanceof PressurePlateBlock) return "pressure_plate";
        if (b instanceof LanternBlock) return "lantern";
        if (b instanceof TorchBlock) return "torch";
        if (b instanceof LeavesBlock) return "leaves";
        if (b instanceof BedBlock) return "bed";
        if (b instanceof AbstractSignBlock) return "sign";
        if (b instanceof LadderBlock) return "ladder";
        if (b instanceof ChainBlock) return "chain";
        if (b instanceof FlowerPotBlock) return "pot";
        if (b instanceof PlantBlock) return "plant";
        if (b instanceof AbstractGlassBlock || b instanceof TransparentBlock) return "glass";
        try {
            if (s.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                return b instanceof PillarBlock ? "pillar" : "full";
            }
        } catch (RuntimeException ignored) {
            // some modded blocks need a real world here
        }
        return "other";
    }

    private static final String[] SUFFIXES = {
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_door", "_trapdoor", "_button",
            "_pressure_plate", "_pane", "_carpet", "_planks", "_log", "_wood", "_hyphae", "_stem", "_block"};

    static String family(String path) {
        String p = path;
        if (p.startsWith("stripped_")) {
            p = p.substring("stripped_".length());
        }
        for (String suffix : SUFFIXES) {
            if (p.endsWith(suffix) && p.length() > suffix.length()) {
                p = p.substring(0, p.length() - suffix.length());
                break;
            }
        }
        if (p.endsWith("bricks")) p = p.substring(0, p.length() - 1);
        else if (p.endsWith("tiles")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static final Map<BlockSoundGroup, String> SOUND_MATERIALS = new HashMap<>();

    static {
        SOUND_MATERIALS.put(BlockSoundGroup.WOOD, "wood");
        SOUND_MATERIALS.put(BlockSoundGroup.BAMBOO_WOOD, "wood");
        SOUND_MATERIALS.put(BlockSoundGroup.CHERRY_WOOD, "wood");
        SOUND_MATERIALS.put(BlockSoundGroup.NETHER_WOOD, "wood");
        SOUND_MATERIALS.put(BlockSoundGroup.STONE, "stone");
        SOUND_MATERIALS.put(BlockSoundGroup.DEEPSLATE, "stone");
        SOUND_MATERIALS.put(BlockSoundGroup.POLISHED_DEEPSLATE, "stone");
        SOUND_MATERIALS.put(BlockSoundGroup.DEEPSLATE_BRICKS, "brick");
        SOUND_MATERIALS.put(BlockSoundGroup.DEEPSLATE_TILES, "tile");
        SOUND_MATERIALS.put(BlockSoundGroup.TUFF, "stone");
        SOUND_MATERIALS.put(BlockSoundGroup.CALCITE, "stone");
        SOUND_MATERIALS.put(BlockSoundGroup.NETHER_BRICKS, "brick");
        SOUND_MATERIALS.put(BlockSoundGroup.MUD_BRICKS, "brick");
        SOUND_MATERIALS.put(BlockSoundGroup.PACKED_MUD, "mud");
        SOUND_MATERIALS.put(BlockSoundGroup.METAL, "metal");
        SOUND_MATERIALS.put(BlockSoundGroup.COPPER, "metal");
        SOUND_MATERIALS.put(BlockSoundGroup.CHAIN, "metal");
        SOUND_MATERIALS.put(BlockSoundGroup.ANVIL, "metal");
        SOUND_MATERIALS.put(BlockSoundGroup.LANTERN, "metal");
        SOUND_MATERIALS.put(BlockSoundGroup.GLASS, "glass");
        SOUND_MATERIALS.put(BlockSoundGroup.WOOL, "wool");
        SOUND_MATERIALS.put(BlockSoundGroup.GRASS, "plant");
        SOUND_MATERIALS.put(BlockSoundGroup.GRAVEL, "earth");
        SOUND_MATERIALS.put(BlockSoundGroup.SAND, "sand");
        SOUND_MATERIALS.put(BlockSoundGroup.ROOTED_DIRT, "earth");
        SOUND_MATERIALS.put(BlockSoundGroup.MOSS_BLOCK, "moss");
        SOUND_MATERIALS.put(BlockSoundGroup.AMETHYST_BLOCK, "crystal");
        SOUND_MATERIALS.put(BlockSoundGroup.BONE, "bone");
        SOUND_MATERIALS.put(BlockSoundGroup.NETHERRACK, "stone");
        SOUND_MATERIALS.put(BlockSoundGroup.BASALT, "stone");
    }

    private static final String[][] NAME_MATERIALS = {
            {"plank", "wood"}, {"log", "wood"}, {"timber", "wood"}, {"wood", "wood"}, {"bamboo", "wood"},
            {"brick", "brick"}, {"tile", "tile"}, {"shingle", "tile"}, {"terracotta", "terracotta"},
            {"concrete", "concrete"}, {"glass", "glass"}, {"wool", "wool"}, {"thatch", "thatch"},
            {"hay", "thatch"}, {"straw", "thatch"}, {"quartz", "quartz"}, {"marble", "stone"},
            {"limestone", "stone"}, {"slate", "stone"}, {"cobble", "stone"}, {"stone", "stone"},
            {"andesite", "stone"}, {"diorite", "stone"}, {"granite", "stone"}, {"basalt", "stone"},
            {"iron", "metal"}, {"copper", "metal"}, {"gold", "metal"}, {"brass", "metal"}, {"zinc", "metal"},
            {"steel", "metal"}, {"moss", "moss"}, {"leaves", "leaves"}, {"mud", "mud"}, {"sand", "sand"},
            {"plaster", "plaster"}, {"stucco", "plaster"}};

    static String material(BlockState state, String path) {
        for (String[] m : NAME_MATERIALS) {
            if (path.contains(m[0])) {
                return m[1];
            }
        }
        try {
            String fromSound = SOUND_MATERIALS.get(state.getSoundGroup());
            if (fromSound != null) {
                return fromSound;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "other";
    }

    // ---------------------------------------------------------------- cache

    static String modsKey() {
        List<String> mods = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            mods.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString());
        }
        mods.sort(String::compareTo);
        return VERSION + ":" + Integer.toHexString(String.join(",", mods).hashCode());
    }

    static Path cacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("remy").resolve("catalog.json");
    }

    private static Snapshot readCache(Path file, String modsKey) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            Snapshot s = GSON.fromJson(Files.readString(file), new TypeToken<Snapshot>() { }.getType());
            return s != null && modsKey.equals(s.modsKey) && s.version == VERSION ? s : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring unreadable catalog cache: {}", e.toString());
            return null;
        }
    }

    private static void writeCache(Path file, Snapshot s) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(s));
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", file, e.toString());
        }
    }

    /** "warm reddish brown" style words for a colour, so prompts and search can talk about looks. */
    public static String describeColor(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, null);
        float h = hsb[0] * 360f, sat = hsb[1], v = hsb[2];
        String lightness = v < 0.25f ? "very dark" : v < 0.45f ? "dark" : v > 0.85f ? "light" : "";
        if (sat < 0.12f) {
            String grey = v < 0.2f ? "black" : v > 0.85f ? "white" : "grey";
            return (lightness.isEmpty() || grey.equals("black") || grey.equals("white") ? "" : lightness + " ") + grey;
        }
        String hue;
        if (h < 15 || h >= 345) hue = "red";
        else if (h < 40) hue = sat < 0.5f && v < 0.7f ? "brown" : "orange";
        else if (h < 65) hue = sat < 0.45f ? "tan" : "yellow";
        else if (h < 160) hue = "green";
        else if (h < 200) hue = "teal";
        else if (h < 255) hue = "blue";
        else if (h < 290) hue = "purple";
        else hue = "pink";
        String muted = sat < 0.3f ? "muted " : "";
        return ((lightness.isEmpty() ? "" : lightness + " ") + muted + hue).trim();
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
