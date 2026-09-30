package com.brandopakel.remy.playerengine;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.pathing.goals.GoalNear;
import com.player2.playerengine.automaton.api.utils.input.Input;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public final class RemyPlayerEngineAdapter implements ModInitializer {
    public static final String MOD_ID = "remy_playerengine_adapter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier REMY_ID = id("remy_npc");

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
        LOGGER.info("Remy PlayerEngine adapter initialized");
    }

    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("remyengine")
                .requires(source -> source.getPlayer() != null)
                .then(CommandManager.literal("spawn").executes(context -> spawn(context.getSource())))
                .then(CommandManager.literal("come").executes(context -> come(context.getSource())))
                .then(CommandManager.literal("follow").executes(context -> follow(context.getSource())))
                .then(CommandManager.literal("stop").executes(context -> stop(context.getSource())))
                .then(CommandManager.literal("status").executes(context -> status(context.getSource())))
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

        ServerWorld world = owner.getServerWorld();
        BlockPos spawnPos = owner.getBlockPos().add(1, 0, 1);
        RemyEntity remy = new RemyEntity(REMY, world);
        remy.setOwner(owner.getUuid(), owner.getGameProfile().getName());
        remy.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, owner.getYaw(), 0.0f);
        world.spawnEntity(remy);
        source.sendFeedback(() -> Text.literal("Spawned Remy at " + shortPos(remy.getBlockPos())), false);
        return 1;
    }

    private static int come(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        RemyEntity remy = findOrSpawn(owner);
        followActive = false;
        followOwner = null;
        sendGoalNear(remy, owner.getBlockPos(), 2);
        source.sendFeedback(() -> Text.literal("Remy is coming to " + shortPos(owner.getBlockPos())), false);
        return 1;
    }

    private static int follow(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        RemyEntity remy = findOrSpawn(owner);
        followActive = true;
        followOwner = owner.getUuid();
        followRefreshTicks = 0;
        sendGoalNear(remy, owner.getBlockPos(), 2);
        source.sendFeedback(() -> Text.literal("Remy follow enabled"), false);
        return 1;
    }

    private static int stop(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        findOwnedRemy(owner).ifPresent(RemyPlayerEngineAdapter::stopRemy);
        followActive = false;
        followOwner = null;
        source.sendFeedback(() -> Text.literal("Remy stopped"), false);
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
                + ", pathing=" + baritone.getPathingBehavior().isPathing()
                + ", goal=" + baritone.getPathingBehavior().getGoal();
        source.sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int say(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal("Remy: " + message), false);
        return 1;
    }

    private static void serverTick(MinecraftServer server) {
        if (!followActive || followOwner == null) {
            return;
        }

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(followOwner);
        if (owner == null) {
            stopAllRemys(server);
            followActive = false;
            followOwner = null;
            return;
        }

        if (followRefreshTicks++ % 20 != 0) {
            return;
        }

        findOwnedRemy(owner).ifPresent(remy -> {
            if (remy.squaredDistanceTo(owner) > 9.0) {
                sendGoalNear(remy, owner.getBlockPos(), 2);
            }
        });
    }

    private static RemyEntity findOrSpawn(ServerPlayerEntity owner) {
        return findOwnedRemy(owner).orElseGet(() -> {
            spawn(owner.getCommandSource());
            return findOwnedRemy(owner).orElseThrow();
        });
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
