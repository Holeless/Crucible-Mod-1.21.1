package com.crucible.effects;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Walks a hostile mob to where it last saw its target, after it has lost the target. */
public class RelentlessGoal extends Goal {
    private static final int MAX_TICKS = 400;       // give up after 20 seconds
    private static final double ARRIVE_DIST_SQR = 4.0;

    private final PathfinderMob mob;
    private int ticks;
    private int stuck;

    public RelentlessGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return Relentless.enabled()
                && mob.getTarget() == null
                && Relentless.lastKnown(mob) != null;
    }

    @Override
    public boolean canContinueToUse() {
        Vec3 dest = Relentless.lastKnown(mob);
        return Relentless.enabled()
                && mob.getTarget() == null
                && dest != null
                && ticks < MAX_TICKS
                && stuck < 3
                && mob.position().distanceToSqr(dest) > ARRIVE_DIST_SQR;
    }

    @Override
    public void start() {
        ticks = 0;
        stuck = 0;
        moveToLastKnown();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        // Arrived, gave up, or found a target again: either way this trail is done.
        Relentless.clear(mob);
    }

    @Override
    public void tick() {
        ticks++;
        if (mob.getNavigation().isDone() && ticks % 10 == 0) {
            if (!moveToLastKnown()) stuck++;
        }
    }

    private boolean moveToLastKnown() {
        Vec3 dest = Relentless.lastKnown(mob);
        return dest != null && mob.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.1D);
    }
}
