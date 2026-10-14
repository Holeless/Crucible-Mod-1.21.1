package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Blackout: hostile mobs break light sources.
 *
 * Idle hostile ground mobs look for the nearest light source (torch, lantern, glowstone, ...) and walk up
 * and break it. See {@link BlackoutGoal}. Mobs that are chasing a target ignore lights until they lose it.
 *
 * A block counts as a light source if it emits light level {@link #MIN_LIGHT} or more, is not fire, is not
 * waterlogged, is breakable by hand within {@link #MAX_HARDNESS}, and has no inventory (lit furnaces,
 * beacons and the like are left alone, campfires are the exception). Respects the mobGriefing game rule.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Blackout {
    static final int MIN_LIGHT = 8;
    static final float MAX_HARDNESS = 3.5F;
    static final int SEARCH_RADIUS = 8;
    static final int SEARCH_HEIGHT = 4;

    private Blackout() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(1);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof PathfinderMob mob) || !(mob instanceof Enemy)) return;
        if (mob instanceof Creeper) return;

        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof BlackoutGoal) return;
        }
        // Priority 4: beats idle wandering, loses to attack goals and RelentlessGoal.
        if (mob.getNavigation() instanceof GroundPathNavigation) {
            mob.goalSelector.addGoal(4, new BlackoutGoal(mob));
        }
    }

    static boolean isLightSource(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getLightEmission(level, pos) < MIN_LIGHT) return false;
        if (state.is(BlockTags.FIRE)) return false;
        if (!state.getFluidState().isEmpty()) return false;

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || hardness > MAX_HARDNESS) return false;

        return !(state.getBlock() instanceof EntityBlock) || state.is(BlockTags.CAMPFIRES);
    }

    /** Nearest light source around the mob, skipping {@code ignore}. Null if there is none. */
    static BlockPos findNearest(Mob mob, BlockPos ignore) {
        Level level = mob.level();
        BlockPos origin = mob.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    double d = p.distSqr(origin);
                    if (d >= bestDist) continue;
                    if (ignore != null && p.equals(ignore)) continue;
                    if (!level.hasChunkAt(p)) continue;
                    if (!isLightSource(level, p)) continue;
                    bestDist = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }
}
