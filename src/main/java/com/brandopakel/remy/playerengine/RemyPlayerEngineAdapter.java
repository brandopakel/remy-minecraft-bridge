package com.brandopakel.remy.playerengine;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.pathing.goals.GoalNear;
import com.player2.playerengine.automaton.api.utils.input.Input;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RemyPlayerEngineAdapter implements ModInitializer {
    public static final String MOD_ID = "remy_playerengine_adapter";
    public static final String VERSION = "0.3.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier REMY_ID = id("remy_npc");
    private static final int NO_PROGRESS_LIMIT_TICKS = 80;
    private static final int NAVIGATION_SAMPLE_INTERVAL_TICKS = 10;
    private static final int SPAWN_SEARCH_RADIUS = 5;

    public static final EntityType<RemyEntity> REMY = FabricEntityTypeBuilder
            .<RemyEntity>createLiving()
            .spawnGroup(SpawnGroup.MISC)
            .entityFactory(RemyEntity::new)
            .defaultAttributes(RemyPlayerEngineAdapter::createRemyAttributes)
            .dimensions(EntityDimensions.changing(EntityType.PLAYER.getWidth(), EntityType.PLAYER.getHeight()))
            .trackRangeBlocks(64)
            .trackedUpdateRate(1)
            .forceTrackedVelocityUpdates(true)
            .build();

    private static boolean followActive;
    private static UUID followOwner;
    private static int followRefreshTicks;
    private static String activeMode = "idle";
    private static UUID activeOwner;
    private static BlockPos activeTarget;
    private static Vec3d lastObservedPosition;
    private static int activeTicks;
    private static int noProgressTicks;

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    private static DefaultAttributeContainer.Builder createRemyAttributes() {
        return ZombieEntity.createZombieAttributes()
                .add(EntityAttributes.GENERIC_ATTACK_SPEED, 4.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_ARMOR, 0.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.23)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    public void onInitialize() {
        Registry.register(Registries.ENTITY_TYPE, REMY_ID, REMY);
        FabricDefaultAttributeRegistry.register(REMY, createRemyAttributes());
        CommandRegistrationCallback.EVENT.register(RemyPlayerEngineAdapter::registerCommands);
        ServerTickEvents.END_SERVER_TICK.register(RemyPlayerEngineAdapter::serverTick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> com.brandopakel.remy.playerengine.village.VillageManager.onServerStopping());
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            MinecraftServer server = sender.getServer();
            if (server != null) {
                String text = message.getSignedContent();
                server.execute(() -> RemyBrainHost.onOwnerChat(server, sender, text));
            }
        });
        LOGGER.info("Remy PlayerEngine adapter initialized (v{})", VERSION);
    }

    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("remyengine")
                .requires(source -> source.getPlayer() != null)
                .then(CommandManager.literal("spawn").executes(context -> spawn(context.getSource())))
                .then(CommandManager.literal("relocate").executes(context -> relocate(context.getSource())))
                .then(CommandManager.literal("come").executes(context -> come(context.getSource())))
                .then(CommandManager.literal("follow").executes(context -> follow(context.getSource())))
                .then(CommandManager.literal("stop").executes(context -> stop(context.getSource())))
                .then(CommandManager.literal("status").executes(context -> status(context.getSource())))
                .then(CommandManager.literal("commands").executes(context -> listCommands(context.getSource())))
                .then(CommandManager.literal("ask")
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(context -> ask(context.getSource(), StringArgumentType.getString(context, "message")))))
                .then(CommandManager.literal("brain").executes(context -> brainStatus(context.getSource())))
                .then(CommandManager.literal("village")
                        .executes(context -> village(context.getSource(), 0))
                        .then(CommandManager.literal("stop").executes(context -> {
                            com.brandopakel.remy.playerengine.village.VillageManager.stop(requirePlayer(context.getSource()));
                            return 1;
                        }))
                        .then(CommandManager.literal("setup").executes(context -> {
                            ServerPlayerEntity p = requirePlayer(context.getSource());
                            com.brandopakel.remy.playerengine.village.VillageManager.setup(p.getServer(), p);
                            return 1;
                        }))
                        .then(CommandManager.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(24, 100))
                                .executes(context -> village(context.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius")))))
                .then(CommandManager.literal("do")
                        .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                .executes(context -> doCommand(context.getSource(), StringArgumentType.getString(context, "command")))))
                .then(CommandManager.literal("say")
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(context -> say(context.getSource(), StringArgumentType.getString(context, "message"))))));
    }

    private static int spawn(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        Optional<RemyEntity> existing = findOwnedRemy(owner);
        if (existing.isPresent()) {
            source.sendFeedback(() -> Text.literal("Remy already exists at " + shortPos(existing.get().getBlockPos())), false);
            return 1;
        }

        Optional<RemyEntity> spawned = createRemyNear(owner);
        if (spawned.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No clear supported space found near you for Remy"), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Spawned Remy at " + shortPos(spawned.get().getBlockPos())), false);
        return 1;
    }

    private static int relocate(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        Optional<BlockPos> target = findSafeStandPos(owner);
        if (target.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No clear supported space found near you for Remy"), false);
            return 0;
        }

        RemyEntity remy = findOwnedRemy(owner).orElseGet(() -> createRemyNear(owner).orElseThrow());
        stopRemy(remy);
        placeRemy(remy, target.get(), owner.getYaw());
        clearActiveGoal();
        source.sendFeedback(() -> Text.literal("Relocated Remy to " + shortPos(remy.getBlockPos())), false);
        return 1;
    }

    private static int come(ServerCommandSource source) {
        chatCome(requirePlayer(source));
        return 1;
    }

    public static void chatCome(ServerPlayerEntity owner) {
        RemyEntity remy = findOrSpawn(owner);
        BlockPos p = owner.getBlockPos();
        RemyBrainHost.run(remy, owner, "goto " + p.getX() + " " + p.getY() + " " + p.getZ());
    }

    private static int legacyCome(ServerPlayerEntity owner, RemyEntity remy) {
        ServerCommandSource source = owner.getCommandSource();
        followActive = false;
        followOwner = null;
        startNavigation("come", owner, remy, owner.getBlockPos());
        source.sendFeedback(() -> Text.literal("Remy is coming to " + shortPos(owner.getBlockPos())), false);
        return 1;
    }

    private static int follow(ServerCommandSource source) {
        chatFollow(requirePlayer(source));
        return 1;
    }

    public static void chatFollow(ServerPlayerEntity owner) {
        RemyEntity remy = findOrSpawn(owner);
        RemyBrainHost.run(remy, owner, "follow " + owner.getGameProfile().getName());
    }

    private static int legacyFollow(ServerPlayerEntity owner, RemyEntity remy) {
        ServerCommandSource source = owner.getCommandSource();
        followActive = true;
        followOwner = owner.getUuid();
        followRefreshTicks = 0;
        startNavigation("follow", owner, remy, owner.getBlockPos());
        source.sendFeedback(() -> Text.literal("Remy follow enabled"), false);
        return 1;
    }

    private static int stop(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        chatStop(owner);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        Optional<RemyEntity> remy = findOwnedRemy(owner);
        if (remy.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Remy is not spawned"), false);
            return 0;
        }

        RemyEntity entity = remy.get();
        IBaritone baritone = entity.getBaritone();
        String message = "Remy at " + shortPos(entity.getBlockPos())
                + ", follow=" + followActive
                + ", mode=" + activeMode
                + ", pathing=" + baritone.getPathingBehavior().isPathing()
                + ", goal=" + baritone.getPathingBehavior().getGoal()
                + ", noProgressTicks=" + noProgressTicks
                + ", clearance=" + standStatus(owner.getServerWorld(), entity.getBlockPos());
        source.sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    public static void chatStop(ServerPlayerEntity owner) {
        findOwnedRemy(owner).ifPresent(remy -> {
            RemyBrainHost.stop(remy);
            stopRemy(remy);
        });
        clearActiveGoal();
        owner.sendMessage(Text.literal("Remy stopped"), false);
    }

    public static void stopLegacyNavigation(RemyEntity remy) {
        stopRemy(remy);
        clearActiveGoal();
    }

    public static Optional<RemyEntity> findRemyFor(ServerPlayerEntity owner) {
        return findOwnedRemy(owner);
    }

    /** Compact plain-text state for the brain: positions, health, held items, nearby mobs. */
    public static String describeFor(ServerPlayerEntity owner) {
        StringBuilder sb = new StringBuilder();
        sb.append("owner at ").append(shortPos(owner.getBlockPos()))
                .append(" in ").append(owner.getServerWorld().getRegistryKey().getValue());
        Optional<RemyEntity> remy = findOwnedRemy(owner);
        if (remy.isEmpty()) {
            sb.append("; remy not spawned");
            return sb.toString();
        }
        RemyEntity r = remy.get();
        sb.append("; remy at ").append(shortPos(r.getBlockPos()))
                .append(" health ").append((int) r.getHealth()).append("/").append((int) r.getMaxHealth())
                .append(" holding ").append(Registries.ITEM.getId(r.getMainHandStack().getItem()));
        List<String> hostiles = new ArrayList<>();
        for (Entity e : owner.getServerWorld().getOtherEntities(r, r.getBoundingBox().expand(16),
                e -> e instanceof net.minecraft.entity.mob.Monster && !(e instanceof RemyEntity))) {
            hostiles.add(Registries.ENTITY_TYPE.getId(e.getType()).toString());
            if (hostiles.size() >= 8) {
                break;
            }
        }
        sb.append("; hostiles nearby: ").append(hostiles.isEmpty() ? "none" : String.join(", ", hostiles));
        return sb.toString();
    }

    private static int listCommands(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        RemyEntity remy = findOrSpawn(owner);
        List<String> names = RemyBrainHost.commandNames(remy, owner);
        source.sendFeedback(() -> Text.literal("Remy can: " + String.join(", ", names)), false);
        return 1;
    }

    private static int ask(ServerCommandSource source, String message) {
        ServerPlayerEntity owner = requirePlayer(source);
        RemyBrainHost.onOwnerChat(owner.getServer(), owner, "remy " + message);
        return 1;
    }

    private static int village(ServerCommandSource source, int radius) {
        ServerPlayerEntity owner = requirePlayer(source);
        com.brandopakel.remy.playerengine.village.VillageManager.start(owner.getServer(), owner, radius);
        return 1;
    }

    private static int brainStatus(ServerCommandSource source) {
        com.brandopakel.remy.playerengine.brain.RemyBrain.reloadConfig();
        var cfg = com.brandopakel.remy.playerengine.brain.RemyBrain.config();
        StringBuilder sb = new StringBuilder("Remy brain (config/remy/brain.json reloaded): useModel=" + cfg.useModel);
        for (var p : cfg.providers) {
            boolean keyOk = p.apiKeyEnv == null || p.apiKeyEnv.isBlank()
                    || (p.apiKey != null && !p.apiKey.isBlank())
                    || (System.getenv(p.apiKeyEnv) != null && !System.getenv(p.apiKeyEnv).isBlank());
            sb.append("\n - ").append(p.name).append(" ").append(p.model)
                    .append(p.enabled ? "" : " (disabled)")
                    .append(keyOk ? "" : " (no API key: set " + p.apiKeyEnv + " or apiKey)");
        }
        sb.append("\nRules always work without a model.");
        String msg = sb.toString();
        source.sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    private static int doCommand(ServerCommandSource source, String command) {
        ServerPlayerEntity owner = requirePlayer(source);
        RemyEntity remy = findOrSpawn(owner);
        RemyBrainHost.run(remy, owner, command);
        return 1;
    }

    private static int say(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal("Remy: " + message), false);
        return 1;
    }

    private static void serverTick(MinecraftServer server) {
        if (activeOwner == null) {
            return;
        }

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(activeOwner);
        if (owner == null) {
            stopAllRemys(server);
            clearActiveGoal();
            return;
        }

        Optional<RemyEntity> remy = findOwnedRemy(owner);
        if (remy.isEmpty()) {
            clearActiveGoal();
            return;
        }

        if (followActive && followRefreshTicks++ % 20 == 0 && remy.get().squaredDistanceTo(owner) > 9.0) {
            activeTarget = owner.getBlockPos();
            sendGoalNear(remy.get(), activeTarget, 2);
        }

        monitorNavigation(owner, remy.get());
    }

    private static RemyEntity findOrSpawn(ServerPlayerEntity owner) {
        return findOwnedRemy(owner).orElseGet(() -> createRemyNear(owner).orElseThrow());
    }

    private static Optional<RemyEntity> findOwnedRemy(ServerPlayerEntity owner) {
        return owner.getServerWorld()
                .getEntitiesByType(TypeFilter.instanceOf(RemyEntity.class), remy -> true)
                .stream()
                .map(remy -> (RemyEntity) remy)
                .filter(remy -> owner.getUuid().equals(remy.getOwnerUuid()))
                .min(Comparator.comparingDouble(remy -> remy.squaredDistanceTo(owner)));
    }

    private static void sendGoalNear(RemyEntity remy, BlockPos pos, int range) {
        IBaritone baritone = remy.getBaritone();
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
    }

    private static void startNavigation(String mode, ServerPlayerEntity owner, RemyEntity remy, BlockPos target) {
        activeMode = mode;
        activeOwner = owner.getUuid();
        activeTarget = target;
        lastObservedPosition = remy.getPos();
        activeTicks = 0;
        noProgressTicks = 0;
        sendGoalNear(remy, target, 2);
    }

    private static void monitorNavigation(ServerPlayerEntity owner, RemyEntity remy) {
        activeTicks++;
        if (activeTicks % NAVIGATION_SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }

        Vec3d current = remy.getPos();
        double movedSquared = lastObservedPosition == null ? Double.MAX_VALUE : current.squaredDistanceTo(lastObservedPosition);
        if (movedSquared < 0.0004) {
            noProgressTicks += NAVIGATION_SAMPLE_INTERVAL_TICKS;
        } else {
            noProgressTicks = 0;
        }
        lastObservedPosition = current;

        if ("come".equals(activeMode) && activeTarget != null && squaredBlockDistance(remy.getBlockPos(), activeTarget) <= 6.25) {
            stopRemy(remy);
            owner.sendMessage(Text.literal("Remy arrived near " + shortPos(activeTarget)), false);
            clearActiveGoal();
            return;
        }

        if (noProgressTicks >= NO_PROGRESS_LIMIT_TICKS) {
            String status = standStatus(owner.getServerWorld(), remy.getBlockPos());
            stopRemy(remy);
            owner.sendMessage(Text.literal("Remy stopped: no movement progress at "
                    + shortPos(remy.getBlockPos()) + " (" + status + ")"), false);
            clearActiveGoal();
        }
    }

    private static Optional<RemyEntity> createRemyNear(ServerPlayerEntity owner) {
        Optional<BlockPos> spawnPos = findSafeStandPos(owner);
        if (spawnPos.isEmpty()) {
            return Optional.empty();
        }

        ServerWorld world = owner.getServerWorld();
        RemyEntity remy = new RemyEntity(REMY, world);
        remy.setOwner(owner.getUuid(), owner.getGameProfile().getName());
        placeRemy(remy, spawnPos.get(), owner.getYaw());
        world.spawnEntity(remy);
        return Optional.of(remy);
    }

    private static void placeRemy(RemyEntity remy, BlockPos pos, float yaw) {
        remy.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        remy.setVelocity(Vec3d.ZERO);
    }

    private static Optional<BlockPos> findSafeStandPos(ServerPlayerEntity owner) {
        ServerWorld world = owner.getServerWorld();
        BlockPos origin = owner.getBlockPos();

        for (int radius = 1; radius <= SPAWN_SEARCH_RADIUS; radius++) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = origin.add(dx, dy, dz);
                        if (isSafeStandPos(world, candidate)) {
                            return Optional.of(candidate);
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    private static boolean isSafeStandPos(ServerWorld world, BlockPos feet) {
        return hasSupport(world, feet.down())
                && isOpen(world, feet)
                && isOpen(world, feet.up())
                && world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.up()).isEmpty();
    }

    private static boolean hasSupport(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isOpen(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static String standStatus(ServerWorld world, BlockPos feet) {
        if (!hasSupport(world, feet.down())) {
            return "no_floor";
        }
        if (!isOpen(world, feet)) {
            return "blocked_feet";
        }
        if (!isOpen(world, feet.up())) {
            return "blocked_head";
        }
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) {
            return "fluid";
        }
        return "clear";
    }

    private static double squaredBlockDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void clearActiveGoal() {
        followActive = false;
        followOwner = null;
        followRefreshTicks = 0;
        activeMode = "idle";
        activeOwner = null;
        activeTarget = null;
        lastObservedPosition = null;
        activeTicks = 0;
        noProgressTicks = 0;
    }

    private static void stopAllRemys(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            world.getEntitiesByType(TypeFilter.instanceOf(RemyEntity.class), remy -> true)
                    .forEach(RemyPlayerEngineAdapter::stopRemy);
        }
    }

    private static void stopRemy(RemyEntity remy) {
        IBaritone baritone = remy.getBaritone();
        baritone.getFollowProcess().cancel();
        baritone.getCustomGoalProcess().setGoal(null);
        baritone.getPathingBehavior().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();
        for (Input input : Input.values()) {
            baritone.getInputOverrideHandler().setInputForceState(input, false);
        }
    }

    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("Remy commands require a player source");
        }
        return player;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
