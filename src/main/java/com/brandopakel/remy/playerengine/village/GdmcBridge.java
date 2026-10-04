package com.brandopakel.remy.playerengine.village;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A localhost-only, token-protected implementation of the legacy GDMC-HTTP 0.4.x API
 * (the one agentcraft was written against), served from inside the running game:
 *
 *   GET  /blocks?x&y&z         block id at a position
 *   PUT  /blocks?x&y&z         body "state" or lines "~dx ~dy ~dz state" -> one "1"/"0" per line
 *   POST /command              one command per line, run as the server (op level)
 *   GET  /buildarea            {"xFrom",...} or -1
 *   GET  /chunks?x&z&dx&dz     uncompressed NBT in the 1.16 layout agentcraft parses
 *                              (sections Y 0..15, i.e. world y 0..255)
 *
 * Safety: bound to 127.0.0.1 only, only running while a village is being generated,
 * every request must send X-Remy-Token, and requests carrying a browser Origin header
 * are refused (so web pages can't drive it).
 */
public final class GdmcBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("remy_village");
    private final MinecraftServer server;
    private final ServerWorld world;
    private final String token;
    private final int[] buildArea; // x1,y1,z1,x2,y2,z2 or null
    private HttpServer http;

    public GdmcBridge(MinecraftServer server, ServerWorld world, String token, int[] buildArea) {
        this.server = server;
        this.world = world;
        this.token = token;
        this.buildArea = buildArea;
    }

    public void start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "remy-gdmc");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/blocks", this::blocks);
        http.createContext("/command", this::command);
        http.createContext("/buildarea", this::buildarea);
        http.createContext("/chunks", this::chunks);
        http.start();
        LOGGER.info("Remy GDMC bridge listening on 127.0.0.1:{}", port);
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
            LOGGER.info("Remy GDMC bridge stopped");
        }
    }

    // ------------------------------------------------------------ helpers

    private boolean authorized(HttpExchange ex) throws IOException {
        if (ex.getRequestHeaders().containsKey("Origin")) {
            send(ex, 403, "browser origins are not allowed");
            return false;
        }
        String t = ex.getRequestHeaders().getFirst("X-Remy-Token");
        if (token != null && !token.isEmpty() && !token.equals(t)) {
            send(ex, 401, "missing or wrong X-Remy-Token");
            return false;
        }
        return true;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> q = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) q.put(part.substring(0, i), java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static int intParam(Map<String, String> q, String key, int fallback) {
        try {
            return Integer.parseInt(q.getOrDefault(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String text) throws IOException {
        send(ex, code, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    private static void send(HttpExchange ex, int code, byte[] data, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private <T> T onServer(Callable<T> work) throws Exception {
        return server.submit(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(60, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------ endpoints

    private void blocks(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) return;
            Map<String, String> q = query(ex.getRequestURI());
            int x = intParam(q, "x", 0), y = intParam(q, "y", 0), z = intParam(q, "z", 0);
            if ("GET".equals(ex.getRequestMethod())) {
                String id = onServer(() -> Registries.BLOCK.getId(world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString());
                send(ex, 200, id);
                return;
            }
            if (!"PUT".equals(ex.getRequestMethod())) {
                send(ex, 405, "method not allowed");
                return;
            }
            String text = body(ex);
            List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n")));
            String result = onServer(() -> placeLines(lines, x, y, z));
            send(ex, 200, result);
        } catch (Exception e) {
            LOGGER.warn("GDMC /blocks failed", e);
            send(ex, 500, String.valueOf(e.getMessage()));
        }
    }

    private String placeLines(List<String> lines, int ox, int oy, int oz) {
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            BlockPos pos;
            String stateText;
            String[] parts = line.split("\\s+", 4);
            if (parts.length == 4 && isCoord(parts[0]) && isCoord(parts[1]) && isCoord(parts[2])) {
                pos = new BlockPos(coord(parts[0], ox), coord(parts[1], oy), coord(parts[2], oz));
                stateText = parts[3];
            } else {
                pos = new BlockPos(ox, oy, oz);
                stateText = line;
            }
            try {
                var parsed = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), stateText, true);
                BlockState state = parsed.blockState();
                boolean changed = world.setBlockState(pos, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                NbtCompound nbt = parsed.nbt();
                if (nbt != null) {
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be != null) {
                        NbtCompound merged = be.createNbtWithIdentifyingData();
                        merged.copyFrom(nbt);
                        be.readNbt(merged);
                        be.markDirty();
                    }
                }
                out.append(changed ? "1" : "0").append('\n');
            } catch (Exception e) {
                out.append(e.getMessage()).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean isCoord(String s) {
        return s.matches("~?-?\\d*") && !s.isEmpty();
    }

    private static int coord(String s, int origin) {
        if (s.startsWith("~")) {
            String rest = s.substring(1);
            return origin + (rest.isEmpty() ? 0 : Integer.parseInt(rest));
        }
        return Integer.parseInt(s);
    }

    private void command(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, "method not allowed");
                return;
            }
            String text = body(ex);
            String result = onServer(() -> {
                ServerCommandSource source = server.getCommandSource().withWorld(world).withSilent();
                StringBuilder out = new StringBuilder();
                for (String line : text.split("\\r?\\n")) {
                    String cmd = line.trim();
                    if (cmd.isEmpty()) continue;
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    int r = server.getCommandManager().executeWithPrefix(source, cmd);
                    out.append(r).append('\n');
                }
                return out.toString();
            });
            send(ex, 200, result);
        } catch (Exception e) {
            LOGGER.warn("GDMC /command failed", e);
            send(ex, 500, String.valueOf(e.getMessage()));
        }
    }

    private void buildarea(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (buildArea == null) {
            send(ex, 200, "-1".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        String json = String.format("{\"xFrom\":%d,\"yFrom\":%d,\"zFrom\":%d,\"xTo\":%d,\"yTo\":%d,\"zTo\":%d}",
                buildArea[0], buildArea[1], buildArea[2], buildArea[3], buildArea[4], buildArea[5]);
        send(ex, 200, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void chunks(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) return;
            Map<String, String> q = query(ex.getRequestURI());
            int cx = intParam(q, "x", 0), cz = intParam(q, "z", 0);
            int dx = Math.max(1, Math.min(intParam(q, "dx", 1), 64));
            int dz = Math.max(1, Math.min(intParam(q, "dz", 1), 64));
            byte[] data = onServer(() -> {
                NbtCompound root = new NbtCompound();
                NbtList list = new NbtList();
                for (int z = cz; z < cz + dz; z++) {
                    for (int x = cx; x < cx + dx; x++) {
                        list.add(legacyChunk(world.getChunk(x, z)));
                    }
                }
                root.put("Chunks", list);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    NbtIo.write(root, out);
                }
                return bytes.toByteArray();
            });
            send(ex, 200, data, "application/octet-stream");
        } catch (Exception e) {
            LOGGER.warn("GDMC /chunks failed", e);
            send(ex, 500, String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------ 1.16 chunk layout

    /** Packs values into longs without spanning (the layout used since 1.16). */
    static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            out[i / perLong] |= (values[i] & mask) << ((i % perLong) * bits);
        }
        return out;
    }

    private NbtCompound legacyChunk(WorldChunk chunk) {
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        NbtCompound level = new NbtCompound();

        NbtCompound heightmaps = new NbtCompound();
        Heightmap.Type[] types = {Heightmap.Type.MOTION_BLOCKING, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Type.OCEAN_FLOOR, Heightmap.Type.WORLD_SURFACE};
        for (Heightmap.Type type : types) {
            int[] heights = new int[256];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int h = chunk.sampleHeightmap(type, x, z) + 1; // top block y + 1, as in 1.16
                    heights[z * 16 + x] = Math.max(0, Math.min(h, 511));
                }
            }
            heightmaps.put(type.getName(), new NbtLongArray(pack(heights, 9)));
        }
        level.put("Heightmaps", heightmaps);

        NbtList sections = new NbtList();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int sy = 0; sy < 16; sy++) {
            List<BlockState> palette = new ArrayList<>();
            Map<BlockState, Integer> index = new HashMap<>();
            int[] states = new int[4096];
            int i = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = chunk.getBlockState(pos.set(baseX + x, sy * 16 + y, baseZ + z));
                        Integer idx = index.get(s);
                        if (idx == null) {
                            idx = palette.size();
                            index.put(s, idx);
                            palette.add(s);
                        }
                        states[i++] = idx;
                    }
                }
            }
            NbtCompound section = new NbtCompound();
            section.putByte("Y", (byte) sy);
            NbtList pal = new NbtList();
            for (BlockState s : palette) {
                pal.add(NbtHelper.fromBlockState(s));
            }
            section.put("Palette", pal);
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            section.put("BlockStates", new NbtLongArray(pack(states, bits)));
            sections.add(section);
        }
        level.put("Sections", sections);
        NbtCompound wrapper = new NbtCompound();
        wrapper.put("Level", level);
        return wrapper;
    }
}
