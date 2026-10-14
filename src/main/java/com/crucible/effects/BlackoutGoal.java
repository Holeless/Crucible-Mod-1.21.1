package com.crucible.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Walks an idle hostile mob to the nearest light source and breaks it, with crack animation.
 *
 * Only runs while the mob has no target, so fighting always comes first. Gives up on a light it cannot reach
 * and will not pick that same one again right away.
 */
public class BlackoutGoal extends Goal {
    private static final int SEARCH_INTERVAL = 40;  // ticks between searches
    private static final int BREAK_TICKS = 20;      // time to break a block once in reach
    private static final int MAX_TICKS = 300;       // give up after 15 seconds
    private static final int MAX_FAILED_PATHS = 3;
    private static final double SPEED = 1.0D;

    private final PathfinderMob mob;
    private BlockPos target;
    private BlockPos failed;
    private int searchDelay;
    private int ticks;
    private int breaking;
    private int repath;
    private int stuck;

    public BlackoutGoal(PathfinderMob mob) {
        this.mob = mob;
        this.searchDelay = mob.getRandom().nextInt(SEARCH_INTERVAL); // stagger so mobs do not all scan at once
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!Blackout.enabled() || !mob.isAlive() || mob.isPassenger()) return false;
        if (mob.getTarget() != null) return false;
        if (--searchDelay > 0) return false;
        searchDelay = SEARCH_INTERVAL;

        Level level = mob.level();
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;
        // Cheap pre-check: no block light at all here means no light source anywhere close.
        if (level.getBrightness(LightLayer.BLOCK, mob.blockPosition()) < 1) return false;

        target = Blackout.findNearest(mob, failed);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return Blackout.enabled()
                && mob.isAlive()
                && !mob.isPassenger()
                && mob.getTarget() == null
                && target != null
                && ticks < MAX_TICKS
                && stuck < MAX_FAILED_PATHS
                && Blackout.isLightSource(mob.level(), target);
    }

    @Override
    public void start() {
        ticks = 0;
        breaking = 0;
        repath = 0;
        stuck = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (target != null) {
            mob.level().destroyBlockProgress(mob.getId(), target, -1);
            if (ticks >= MAX_TICKS || stuck >= MAX_FAILED_PATHS) failed = target;
        }
        target = null;
        breaking = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) return;
        ticks++;

        Level level = mob.level();
        Vec3 center = Vec3.atCenterOf(target);
        mob.getLookControl().setLookAt(center.x, center.y, center.z);

        double reach = 1.5 + mob.getBbWidth();
        double distSqr = Math.min(mob.distanceToSqr(center), mob.getEyePosition().distanceToSqr(center));

        if (distSqr <= reach * reach) {
            mob.getNavigation().stop();
            stuck = 0;
            breaking++;
            if (breaking % 8 == 1) mob.swing(InteractionHand.MAIN_HAND);
            level.destroyBlockProgress(mob.getId(), target, Math.min(9, breaking * 10 / BREAK_TICKS));

            if (breaking >= BREAK_TICKS) {
                level.destroyBlock(target, true, mob);
            }
            return;
        }

        if (breaking > 0) {
            breaking = 0;
            level.destroyBlockProgress(mob.getId(), target, -1);
        }
        if (--repath <= 0) {
            repath = 10;
            if (!mob.getNavigation().moveTo(center.x, center.y, center.z, SPEED)) stuck++;
        }
    }
}
