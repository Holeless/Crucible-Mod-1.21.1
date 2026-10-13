package com.crucible;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Tracks which crucible is active for the currently running world.
 *
 * The Create World screen writes {@link #pendingLevel}. When a brand-new world starts, that value is
 * saved into the world. Every later load reads it back from the world's save data.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class CrucibleManager {
    /** Set by the Create World screen (client). Only consumed when a brand-new world starts. */
    public static volatile int pendingLevel = 1;

    private static volatile int activeLevel = 0;

    private CrucibleManager() {}

    public static int activeLevel() {
        return activeLevel;
    }

    /** True if the running world was created with exactly this crucible (1-3). */
    public static boolean isActive(int crucible) {
        return activeLevel == crucible;
    }

    /** Earliest point we can know the crucible: before spawn chunks (and their mobs) load. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            init(level);
        }
    }

    /** Safety net, does nothing if the level load already handled it. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        init(event.getServer().overworld());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        activeLevel = 0;
    }

    private static void init(ServerLevel overworld) {
        DimensionDataStorage storage = overworld.getDataStorage();

        CrucibleData data = storage.get(CrucibleData.FACTORY, CrucibleData.NAME);
        if (data == null) {
            // No saved crucible: if the world has never ticked it is brand new, otherwise it is an old world.
            boolean newWorld = overworld.getGameTime() == 0L;
            int chosen = newWorld ? Math.max(1, Math.min(3, pendingLevel)) : 0;
            data = new CrucibleData(chosen);
            storage.set(CrucibleData.NAME, data);
            data.setDirty();
        }
        if (activeLevel != data.getLevel()) {
            activeLevel = data.getLevel();
            Crucible.LOGGER.info("Crucible level for this world: {}", activeLevel);
        }
    }
}
