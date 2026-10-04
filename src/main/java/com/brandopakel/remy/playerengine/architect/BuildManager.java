package com.brandopakel.remy.playerengine.architect;

import com.brandopakel.remy.playerengine.RemyEntity;
import com.brandopakel.remy.playerengine.RemyBrainHost;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.automaton.api.process.IBuilderProcess;
import com.player2.playerengine.automaton.api.schematic.AbstractSchematic;
import com.player2.playerengine.tasks.base.Task;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puts blueprints into the world, two ways:
 *
 * - instant: places the whole design within a few ticks (a preview you can undo, or a
 *   creative-style build). Supports blocks first, attached blocks (torches, doors) last.
 * - survival: Remy gathers/crafts the materials with PlayerEngine's own resource tasks and
 *   places every block with Automatone's builder, in batches that fit his inventory.
 */
public final class BuildManager {
    private static final int BLOCKS_PER_TICK = 1500;
    private static final int FRONT_GAP = 2;

    /** Designs waiting for "build it" / "preview", per owner. */
    private static final Map<UUID, Blueprint> PENDING = new HashMap<>();
    /** Undo stack per owner: previous states of the last instant build. */
    private static final Map<UUID, List<Placed>> UNDO = new HashMap<>();
    private static final Deque<Job> QUEUE = new ArrayDeque<>();

    record Placed(BlockPos pos, BlockState previous) {
    }

    record Job(ServerWorld world, UUID owner, Deque<Map.Entry<BlockPos, BlockState>> steps, List<Placed> undo, String name, boolean undoing) {
    }

    /** Where a design goes: rotated so its front faces the owner, centred in front of them. */
    public record Placement(Blueprint rotated, BlockPos origin) {
    }

    private BuildManager() {
    }

    public static void setPending(ServerPlayerEntity owner, Blueprint bp) {
        PENDING.put(owner.getUuid(), bp);
    }

    public static Blueprint pending(ServerPlayerEntity owner) {
        return PENDING.get(owner.getUuid());
    }

    public static Placement place(ServerPlayerEntity owner, Blueprint bp) {
        Direction facing = owner.getHorizontalFacing();
        Blueprint r = bp.rotate(Blueprint.rotationToFace(facing.getOpposite()));
        BlockPos p = owner.getBlockPos();
        int depthAlong = facing.getAxis() == Direction.Axis.Z ? r.sz : r.sx;
        int width = facing.getAxis() == Direction.Axis.Z ? r.sx : r.sz;
        // Near edge FRONT_GAP blocks ahead of the owner, centred on their line of sight.
        BlockPos nearCenter = p.offset(facing, FRONT_GAP);
        BlockPos farCenter = p.offset(facing, FRONT_GAP + depthAlong - 1);
        Direction side = facing.rotateYClockwise();
        BlockPos a = nearCenter.offset(side, -(width / 2));
        BlockPos b = farCenter.offset(side, width - 1 - width / 2);
        int minX = Math.min(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        return new Placement(r, new BlockPos(minX, p.getY() - 1, minZ));
    }

    // ---------------------------------------------------------------- instant

    public static void buildInstant(ServerPlayerEntity owner, Blueprint bp) {
        Placement pl = place(owner, bp);
        Blueprint r = pl.rotated();
        ServerWorld world = owner.getServerWorld();
        List<Map.Entry<BlockPos, BlockState>> base = new ArrayList<>();
        List<Map.Entry<BlockPos, BlockState>> attached = new ArrayList<>();
        for (int y = 0; y < r.sy; y++) {
            for (int z = 0; z < r.sz; z++) {
                for (int x = 0; x < r.sx; x++) {
                    BlockState s = r.get(x, y, z);
                    if (s == null) continue;
                    BlockPos pos = pl.origin().add(x, y, z);
                    (Blueprint.attachable(s) ? attached : base).add(Map.entry(pos, s));
                }
            }
        }
        // Foundation: fill gaps under the bottom layer so the build doesn't float on slopes.
        List<Map.Entry<BlockPos, BlockState>> footing = new ArrayList<>();
        for (int z = 0; z < r.sz; z++) {
            for (int x = 0; x < r.sx; x++) {
                BlockState s = r.get(x, 0, z);
                if (s == null || s.isAir() || Blueprint.attachable(s)) continue;
                for (int d = 1; d <= 8; d++) {
                    BlockPos below = pl.origin().add(x, -d, z);
                    BlockState cur = world.getBlockState(below);
                    if (!cur.isAir() && !cur.isReplaceable() && cur.getFluidState().isEmpty()) break;
                    footing.add(Map.entry(below, s));
                }
            }
        }
        Deque<Map.Entry<BlockPos, BlockState>> steps = new ArrayDeque<>();
        steps.addAll(footing);
        steps.addAll(base);
        steps.addAll(attached);
        List<Placed> undo = new ArrayList<>();
        UNDO.put(owner.getUuid(), undo);
        QUEUE.add(new Job(world, owner.getUuid(), steps, undo, r.name, false));
        owner.sendMessage(Text.literal("<Remy> Placing " + r.describe() + " at " + short3(pl.origin())
                + ". Say \"remy undo\" to take it back."), false);
    }

    public static boolean undo(ServerPlayerEntity owner) {
        List<Placed> undo = UNDO.remove(owner.getUuid());
        if (undo == null || undo.isEmpty()) {
            return false;
        }
        Deque<Map.Entry<BlockPos, BlockState>> steps = new ArrayDeque<>();
        for (int i = undo.size() - 1; i >= 0; i--) {
            steps.add(Map.entry(undo.get(i).pos(), undo.get(i).previous()));
        }
        QUEUE.add(new Job(owner.getServerWorld(), owner.getUuid(), steps, null, "undo", true));
        return true;
    }

    /** Called every server tick. */
    public static void tick(MinecraftServer server) {
        Job job = QUEUE.peek();
        if (job == null) return;
        int n = 0;
        while (n < BLOCKS_PER_TICK && !job.steps().isEmpty()) {
            Map.Entry<BlockPos, BlockState> step = job.steps().poll();
            BlockPos pos = step.getKey();
            if (job.undo() != null) {
                job.undo().add(new Placed(pos, job.world().getBlockState(pos)));
            }
            int flags = job.undoing() ? Block.NOTIFY_LISTENERS | Block.FORCE_STATE : Block.NOTIFY_ALL;
            job.world().setBlockState(pos, step.getValue(), flags);
            n++;
        }
        if (job.steps().isEmpty()) {
            QUEUE.poll();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(job.owner());
            if (p != null) {
                p.sendMessage(Text.literal(job.undoing() ? "<Remy> Undone." : "<Remy> Finished placing " + job.name() + "."), false);
            }
        }
    }

    // ---------------------------------------------------------------- survival

    public static void buildSurvival(ServerPlayerEntity owner, RemyEntity remy, Blueprint bp) {
        Placement pl = place(owner, bp);
        PlayerEngineController controller = RemyBrainHost.controllerFor(remy, owner);
        RemyBuildTask task = new RemyBuildTask(remy, pl.rotated(), pl.origin(), owner.getUuid());
        owner.sendMessage(Text.literal("<Remy> I'll build " + pl.rotated().describe() + " at " + short3(pl.origin())
                + ". I'll gather what I'm missing first; say \"remy stop\" to cancel."), false);
        controller.runUserTask(task, () -> {
            ServerPlayerEntity p = owner.getServer().getPlayerManager().getPlayer(owner.getUuid());
            if (p != null) {
                p.sendMessage(Text.literal(task.finishedMessage()), false);
            }
        });
    }

    /** Automatone schematic backed by a blueprint; ' ' cells are outside the schematic. */
    static final class BlueprintSchematic extends AbstractSchematic {
        private final Blueprint bp;

        BlueprintSchematic(Blueprint bp) {
            super(bp.sx, bp.sy, bp.sz);
            this.bp = bp;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState current) {
            return bp.get(x, y, z) != null;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            BlockState s = bp.get(x, y, z);
            return s == null ? current : s;
        }
    }

    /** Properties that change by themselves after placement and shouldn't count as mismatches. */
    private static final Set<Property<?>> VOLATILE = Set.of(
            Properties.WATERLOGGED, Properties.POWERED, Properties.OPEN, Properties.OCCUPIED, Properties.LIT,
            Properties.NORTH, Properties.SOUTH, Properties.EAST, Properties.WEST, Properties.UP, Properties.DOWN,
            Properties.STAIR_SHAPE, Properties.DISTANCE_1_7, Properties.PERSISTENT, Properties.ATTACHED,
            Properties.NORTH_WALL_SHAPE, Properties.SOUTH_WALL_SHAPE, Properties.EAST_WALL_SHAPE, Properties.WEST_WALL_SHAPE,
            Properties.IN_WALL);

    static boolean matches(BlockState want, BlockState have) {
        if (want.isAir()) {
            return have.isAir() || have.isReplaceable();
        }
        if (want.getBlock() != have.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            if (VOLATILE.contains(p)) continue;
            if (!want.get(p).equals(have.get(p))) return false;
        }
        return true;
    }

    /**
     * One PlayerEngine user task for the whole build: count what's still missing, fetch a batch of
     * materials with TaskCatalogue resource tasks, then let Automatone's builder place blocks;
     * repeat until done or until a cycle makes no progress.
     */
    public static final class RemyBuildTask extends Task {
        private static final int INVENTORY_STACK_BUDGET = 24;
        private final RemyEntity remy;
        private final Blueprint bp;
        private final BlockPos origin;
        private final UUID owner;
        private boolean done;
        private boolean building;
        private int lastRemaining = -1;
        private int stalledCycles;
        private String outcome = "";
        private Item gathering;

        RemyBuildTask(RemyEntity remy, Blueprint bp, BlockPos origin, UUID owner) {
            this.remy = remy;
            this.bp = bp;
            this.origin = origin;
            this.owner = owner;
        }

        String finishedMessage() {
            return "<Remy> " + (outcome.isEmpty() ? "Build finished: " + bp.name + "!" : outcome);
        }

        @Override
        protected void onStart() {
            building = false;
        }

        @Override
        protected Task onTick() {
            if (done) return null;
            IBuilderProcess builder = remy.getBaritone().getBuilderProcess();
            if (building && builder.isActive() && !builder.isPaused()) {
                setDebugState("placing blocks");
                return null;
            }
            if (building) {
                // Builder stopped or paused: re-check the world.
                building = false;
                builder.onLostControl();
            }
            Map<Item, Integer> missing = remainingMaterials();
            int remaining = remainingCells();
            if (remaining == 0) {
                done = true;
                return null;
            }
            if (remaining == lastRemaining) {
                stalledCycles++;
            } else {
                stalledCycles = 0;
                lastRemaining = remaining;
            }
            if (stalledCycles >= 3) {
                done = true;
                outcome = "I got stuck with " + remaining + " blocks left (missing or unreachable). Ask me to try again, or give me: "
                        + describe(missing);
                return null;
            }
            // Gather a batch of whatever we don't carry yet.
            Map<Item, Integer> toFetch = batch(missing);
            for (Map.Entry<Item, Integer> e : toFetch.entrySet()) {
                int have = count(e.getKey());
                if (have < e.getValue()) {
                    if (!TaskCatalogue.taskExists(e.getKey())) {
                        continue; // PlayerEngine can't obtain it; the builder will skip those cells
                    }
                    gathering = e.getKey();
                    setDebugState("gathering " + Registries.ITEM.getId(e.getKey()).getPath());
                    try {
                        return TaskCatalogue.getItemTask(e.getKey(), e.getValue());
                    } catch (RuntimeException ex) {
                        // fall through to building with what we have
                    }
                }
            }
            gathering = null;
            builder.build("remy-" + bp.name, new BlueprintSchematic(bp), origin);
            building = true;
            setDebugState("placing blocks");
            return null;
        }

        private Map<Item, Integer> batch(Map<Item, Integer> missing) {
            Map<Item, Integer> out = new LinkedHashMap<>();
            int stacks = 0;
            for (Map.Entry<Item, Integer> e : missing.entrySet()) {
                int max = Math.max(1, e.getKey().getMaxCount());
                int want = e.getValue();
                int need = (int) Math.ceil(want / (double) max);
                if (stacks + need > INVENTORY_STACK_BUDGET) {
                    want = Math.max(0, (INVENTORY_STACK_BUDGET - stacks) * max);
                    need = INVENTORY_STACK_BUDGET - stacks;
                }
                if (want <= 0) break;
                out.put(e.getKey(), want);
                stacks += need;
            }
            return out;
        }

        /** Items still needed for cells that don't match yet (ignoring what's in the inventory). */
        private Map<Item, Integer> remainingMaterials() {
            Map<Item, Integer> out = new LinkedHashMap<>();
            ServerWorld world = (ServerWorld) remy.getWorld();
            for (int y = 0; y < bp.sy; y++) {
                for (int z = 0; z < bp.sz; z++) {
                    for (int x = 0; x < bp.sx; x++) {
                        BlockState want = bp.get(x, y, z);
                        if (want == null || want.isAir()) continue;
                        if (matches(want, world.getBlockState(origin.add(x, y, z)))) continue;
                        int n = Blueprint.itemCount(want);
                        Item item = want.getBlock().asItem();
                        if (n > 0 && item != net.minecraft.item.Items.AIR) out.merge(item, n, Integer::sum);
                    }
                }
            }
            return out;
        }

        private int remainingCells() {
            int n = 0;
            ServerWorld world = (ServerWorld) remy.getWorld();
            for (int y = 0; y < bp.sy; y++) {
                for (int z = 0; z < bp.sz; z++) {
                    for (int x = 0; x < bp.sx; x++) {
                        BlockState want = bp.get(x, y, z);
                        if (want != null && !matches(want, world.getBlockState(origin.add(x, y, z)))) n++;
                    }
                }
            }
            return n;
        }

        private int count(Item item) {
            int n = 0;
            for (ItemStack s : remy.getLivingInventory().main) if (s.isOf(item)) n += s.getCount();
            for (ItemStack s : remy.getLivingInventory().offHand) if (s.isOf(item)) n += s.getCount();
            return n;
        }

        private static String describe(Map<Item, Integer> m) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : m.entrySet()) {
                parts.add(e.getValue() + " " + Registries.ITEM.getId(e.getKey()).getPath());
                if (parts.size() >= 6) break;
            }
            return parts.isEmpty() ? "nothing" : String.join(", ", parts);
        }

        @Override
        public boolean isFinished() {
            return done;
        }

        @Override
        protected void onStop(Task interruptTask) {
            IBuilderProcess builder = remy.getBaritone().getBuilderProcess();
            if (builder.isActive()) builder.onLostControl();
            building = false;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RemyBuildTask t && t.bp == bp && t.origin.equals(origin);
        }

        @Override
        protected String toDebugString() {
            return "Building " + bp.name + (gathering != null ? " (gathering " + Registries.ITEM.getId(gathering).getPath() + ")" : "");
        }
    }

    static String short3(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
