package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Swarm: +100% hostile spawn rate and +100% spawner speed.
 *
 * Natural spawning: right after vanilla's own spawn pass each tick, we run a second hostile-only pass
 * over the same chunks near players. That is twice the spawn attempts per tick. All vanilla spawn rules
 * still apply (light, biome, distance from player, mob cap).
 *
 * Spawners: each spawner near a player gets one extra tick per tick, so its countdown runs twice as fast.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Swarm {
    private static final int MOB_SPAWN_RADIUS = 8;   // chunks, same as vanilla's spawn range
    private static final int SPAWNER_RADIUS = 3;     // chunks, covers the spawner's 16-block player range

    private Swarm() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!CrucibleManager.isActive(1)) return;
        if (level.players().isEmpty()) return;

        extraMobSpawning(level);
        extraSpawnerTicks(level);
    }

    private static void extraMobSpawning(ServerLevel level) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) return;
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return;

        NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
        if (state == null) return;

        // friendlies = false, enemies = true, persistent = false -> hostile category only
        forEachNearbyChunk(level, MOB_SPAWN_RADIUS,
                chunk -> NaturalSpawner.spawnForChunk(level, chunk, state, false, true, false));
    }

    private static void extraSpawnerTicks(ServerLevel level) {
        List<SpawnerBlockEntity> spawners = new ArrayList<>();
        forEachNearbyChunk(level, SPAWNER_RADIUS, chunk -> {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof SpawnerBlockEntity spawner) spawners.add(spawner);
            }
        });
        for (SpawnerBlockEntity spawner : spawners) {
            spawner.getSpawner().serverTick(level, spawner.getBlockPos());
        }
    }

    /** Visits each loaded, entity-ticking chunk within {@code radius} chunks of any player exactly once. */
    private static void forEachNearbyChunk(ServerLevel level, int radius, Consumer<LevelChunk> action) {
        ServerChunkCache cache = level.getChunkSource();
        LongSet seen = new LongOpenHashSet();
        int r2 = radius * radius;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            ChunkPos center = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > r2) continue;
                    ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                    if (!seen.add(pos.toLong())) continue;

                    LevelChunk chunk = cache.getChunkNow(pos.x, pos.z);
                    if (chunk == null) continue;
                    if (!level.getWorldBorder().isWithinBounds(pos)) continue;
                    if (!level.isPositionEntityTicking(pos.getWorldPosition())) continue;
                    action.accept(chunk);
                }
            }
        }
    }
}
