package com.brandopakel.remy.playerengine.architect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A building design: a palette of block states and a stack of character-grid layers.
 *
 * Conventions (shared with the architect prompt):
 * - layers[0] is the bottom layer (ground/floor); each layer is a list of rows;
 * - rows run north to south (row 0 = north edge), characters run west to east;
 * - ' ' (space) means "leave whatever is there"; the palette maps other characters to
 *   block states like "minecraft:oak_stairs[facing=south,half=bottom]" or "minecraft:air";
 * - the front/entrance is the south side (last row).
 */
public final class Blueprint {
    public final String name;
    public final String summary;
    public final int sx, sy, sz;
    private final BlockState[] cells;

    public record Parsed(Blueprint blueprint, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return blueprint != null && errors.isEmpty();
        }
    }

    public Blueprint(String name, String summary, int sx, int sy, int sz, BlockState[] cells) {
        this.name = name;
        this.summary = summary;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = cells;
    }

    public BlockState get(int x, int y, int z) {
        return cells[(y * sz + z) * sx + x];
    }

    private void set(int x, int y, int z, BlockState s) {
        cells[(y * sz + z) * sx + x] = s;
    }

    public int volume() {
        return sx * sy * sz;
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Parses and validates a design. {@code resolver} turns "id[props]" into a state or throws
     * IllegalArgumentException with a short reason (unknown block, bad property...).
     */
    public static Parsed parse(String json, Function<String, BlockState> resolver, int maxX, int maxY, int maxZ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(stripFences(json)).getAsJsonObject();
        } catch (RuntimeException e) {
            errors.add("not valid JSON: " + e.getMessage());
            return new Parsed(null, errors, warnings);
        }
        String name = str(root, "name", "Remy's build");
        String summary = str(root, "summary", "");
        if (!root.has("palette") || !root.get("palette").isJsonObject()) {
            errors.add("missing \"palette\" object");
        }
        if (!root.has("layers") || !root.get("layers").isJsonArray()) {
            errors.add("missing \"layers\" array");
        }
        if (!errors.isEmpty()) {
            return new Parsed(null, errors, warnings);
        }

        Map<Character, BlockState> palette = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("palette").entrySet()) {
            if (e.getKey().length() != 1) {
                errors.add("palette key \"" + e.getKey() + "\" must be a single character");
                continue;
            }
            char c = e.getKey().charAt(0);
            if (c == ' ') {
                errors.add("palette key ' ' is reserved for \"leave as is\"");
                continue;
            }
            String value = e.getValue().isJsonPrimitive() ? e.getValue().getAsString().trim() : "";
            if (value.isEmpty() || value.equals("keep")) {
                palette.put(c, null);
                continue;
            }
            if (value.equals("air")) {
                value = "minecraft:air";
            }
            try {
                palette.put(c, resolver.apply(value));
            } catch (IllegalArgumentException ex) {
                errors.add("palette '" + c + "' = \"" + value + "\": " + ex.getMessage());
            }
        }

        JsonArray layers = root.getAsJsonArray("layers");
        int sy = layers.size();
        int sz = 0, sx = 0;
        List<List<String>> grid = new ArrayList<>();
        for (int y = 0; y < sy; y++) {
            List<String> rows = new ArrayList<>();
            JsonElement layer = layers.get(y);
            if (layer.isJsonArray()) {
                for (JsonElement row : layer.getAsJsonArray()) {
                    rows.add(row.isJsonPrimitive() ? row.getAsString() : "");
                }
            } else if (layer.isJsonPrimitive()) {
                rows.addAll(List.of(layer.getAsString().split("\n", -1)));
            }
            sz = Math.max(sz, rows.size());
            for (String r : rows) {
                sx = Math.max(sx, r.length());
            }
            grid.add(rows);
        }
        if (sy == 0 || sx == 0 || sz == 0) {
            errors.add("the design is empty");
        }
        if (sx > maxX || sy > maxY || sz > maxZ) {
            errors.add("size " + sx + "x" + sy + "x" + sz + " (x,y,z) exceeds the limit " + maxX + "x" + maxY + "x" + maxZ);
        }
        if (!errors.isEmpty()) {
            return new Parsed(null, errors, warnings);
        }

        BlockState[] cells = new BlockState[sx * sy * sz];
        Map<Character, Integer> unknownChars = new LinkedHashMap<>();
        int ragged = 0;
        for (int y = 0; y < sy; y++) {
            List<String> rows = grid.get(y);
            if (rows.size() != sz) {
                ragged++;
            }
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                if (row.length() != sx) {
                    ragged++;
                }
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == ' ') {
                        continue;
                    }
                    if (!palette.containsKey(c)) {
                        unknownChars.merge(c, 1, Integer::sum);
                        continue;
                    }
                    cells[(y * sz + z) * sx + x] = palette.get(c);
                }
            }
        }
        if (!unknownChars.isEmpty()) {
            errors.add("characters used in layers but missing from the palette: " + unknownChars.keySet());
        }
        if (ragged > 0) {
            warnings.add(ragged + " rows/layers had a different length and were padded with ' ' (leave as is)");
        }
        if (!errors.isEmpty()) {
            return new Parsed(null, errors, warnings);
        }
        Blueprint bp = new Blueprint(name, summary, sx, sy, sz, cells);
        bp.completeMultiBlocks(warnings);
        if (bp.blockCount() == 0) {
            errors.add("the design places no blocks");
        }
        return new Parsed(errors.isEmpty() ? bp : null, errors, warnings);
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? t : t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        int first = t.indexOf('{');
        int last = t.lastIndexOf('}');
        return first >= 0 && last > first ? t.substring(first, last + 1) : t;
    }

    /** Adds the missing halves of doors, tall plants and beds so they don't pop off. */
    void completeMultiBlocks(List<String> warnings) {
        int added = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState s = get(x, y, z);
                    if (s == null) {
                        continue;
                    }
                    if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                        if (y + 1 < sy) {
                            BlockState above = get(x, y + 1, z);
                            if (above == null || above.isAir() || above.getBlock() != s.getBlock()) {
                                set(x, y + 1, z, s.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
                                added++;
                            }
                        }
                    }
                    if (s.getBlock() instanceof BedBlock && s.get(BedBlock.PART) == BedPart.FOOT) {
                        Direction f = s.get(BedBlock.FACING);
                        int hx = x + f.getOffsetX(), hz = z + f.getOffsetZ();
                        if (hx >= 0 && hx < sx && hz >= 0 && hz < sz) {
                            BlockState head = get(hx, y, hz);
                            if (head == null || head.isAir() || head.getBlock() != s.getBlock()) {
                                set(hx, y, hz, s.with(BedBlock.PART, BedPart.HEAD));
                                added++;
                            }
                        }
                    }
                }
            }
        }
        if (added > 0) {
            warnings.add("added " + added + " missing door/bed/tall-plant halves");
        }
    }

    // ---------------------------------------------------------------- geometry

    /**
     * Rotates the design. CLOCKWISE_90 turns the south-facing front to face west, and so on;
     * block states (stairs, doors, logs...) are rotated with it.
     */
    public Blueprint rotate(BlockRotation r) {
        if (r == BlockRotation.NONE) {
            return this;
        }
        int nx = (r == BlockRotation.CLOCKWISE_180) ? sx : sz;
        int nz = (r == BlockRotation.CLOCKWISE_180) ? sz : sx;
        BlockState[] out = new BlockState[nx * sy * nz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState s = get(x, y, z);
                    if (s == null) {
                        continue;
                    }
                    int tx, tz;
                    switch (r) {
                        case CLOCKWISE_90 -> { tx = sz - 1 - z; tz = x; }
                        case COUNTERCLOCKWISE_90 -> { tx = z; tz = sx - 1 - x; }
                        default -> { tx = sx - 1 - x; tz = sz - 1 - z; }
                    }
                    out[(y * nz + tz) * nx + tx] = s.rotate(r);
                }
            }
        }
        return new Blueprint(name, summary, nx, sy, nz, out);
    }

    /** Rotation that makes the south-facing front face the given direction. */
    public static BlockRotation rotationToFace(Direction front) {
        return switch (front) {
            case WEST -> BlockRotation.CLOCKWISE_90;
            case NORTH -> BlockRotation.CLOCKWISE_180;
            case EAST -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    // ---------------------------------------------------------------- materials

    public int blockCount() {
        int n = 0;
        for (BlockState s : cells) {
            if (s != null && !s.isAir()) {
                n++;
            }
        }
        return n;
    }

    /** Items needed to place this design, counting doors/beds/tall plants once and double slabs twice. */
    public Map<Item, Integer> materials() {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (BlockState s : cells) {
            if (s == null || s.isAir()) {
                continue;
            }
            int n = itemCount(s);
            if (n == 0) {
                continue;
            }
            Item item = s.getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            out.merge(item, n, Integer::sum);
        }
        return out;
    }

    static int itemCount(BlockState s) {
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return 0;
        }
        if (s.getBlock() instanceof BedBlock && s.get(BedBlock.PART) == BedPart.HEAD) {
            return 0;
        }
        if (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        return 1;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" (").append(sx).append("x").append(sy).append("x").append(sz)
                .append(", ").append(blockCount()).append(" blocks)");
        List<Map.Entry<Item, Integer>> mats = new ArrayList<>(materials().entrySet());
        mats.sort((a, b) -> b.getValue() - a.getValue());
        sb.append(": ");
        for (int i = 0; i < Math.min(6, mats.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(mats.get(i).getValue()).append(" ").append(Registries.ITEM.getId(mats.get(i).getKey()).getPath());
        }
        if (mats.size() > 6) {
            sb.append(" + ").append(mats.size() - 6).append(" more");
        }
        return sb.toString();
    }

    /** True for blocks that hang on others and should be placed last (torches, doors, carpets...). */
    public static boolean attachable(BlockState s) {
        Block b = s.getBlock();
        String shape = BlockCatalog.shape(b, s);
        return switch (shape) {
            case "torch", "lantern", "door", "trapdoor", "carpet", "button", "pressure_plate", "sign",
                 "ladder", "plant", "pot", "bed", "chain" -> true;
            default -> !s.isOpaqueFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN)
                    && s.getBlock() == Blocks.AIR;
        };
    }
}
