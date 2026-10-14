package com.crucible.effects;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * Omniscience, creepers: an underground creeper that cannot walk to its target walks as close as it can, and when it
 * is stuck there it lights its fuse, so the blast opens a way through.
 *
 * "Cannot walk to" means the pathfinder has no complete route to the player. Creepers on the surface are left alone.
 * A creeper counts as stuck when it has barely moved for {@link #STUCK_TICKS} ticks while still some way from the
 * player (closer than that, vanilla's own swell logic takes over). Each blast can open the route for the next creeper.
 */
public class OmniscienceCreeperGoal extends Goal {
    private static final int CHECK_INTERVAL = 20;
    private static final int STUCK_TICKS = 25;
    private static final double MAX_DIST_SQR = 96.0 * 96.0;
    private static final double MIN_BLAST_DIST_SQR = 4.0 * 4.0;
    private static final double STILL_SQR = 0.0004;

    private final Creeper creeper;
    private int nextCheck;
    private int repath;
    private int stuck;
    private double lastX;
    private double lastZ;

    public OmniscienceCreeperGoal(Creeper creeper) {
        this.creeper = creeper;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private Player targetPlayer() {
        return creeper.getTarget() instanceof Player p && p.isAlive() && p.level() == creeper.level() ? p : null;
    }

    private boolean canReach(Player player) {
        Path path = creeper.getNavigation().createPath(player, 0);
        return path != null && path.canReach();
    }

    @Override
    public boolean canUse() {
        if (!Omniscience.enabled() || !creeper.isAlive() || creeper.isIgnited()) return false;
        Player player = targetPlayer();
        if (player == null) return false;
        if (creeper.tickCount < nextCheck) return false;
        nextCheck = creeper.tickCount + CHECK_INTERVAL;

        if (!creeper.onGround() || creeper.level().canSeeSky(creeper.blockPosition())) return false; // underground only
        if (creeper.distanceToSqr(player) > MAX_DIST_SQR) return false;
        return !canReach(player);
    }

    @Override
    public boolean canContinueToUse() {
        if (!Omniscience.enabled() || !creeper.isAlive()) return false;
        Player player = targetPlayer();
        if (player == null) return false;
        if (creeper.isIgnited()) return true;
        if (creeper.tickCount >= nextCheck) {
            nextCheck = creeper.tickCount + CHECK_INTERVAL;
            if (canReach(player)) return false; // a way opened up: vanilla takes over
        }
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        stuck = 0;
        repath = 0;
        lastX = creeper.getX();
        lastZ = creeper.getZ();
    }

    @Override
    public void stop() {
        creeper.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = creeper.getTarget();
        if (target == null) return;
        creeper.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--repath <= 0) {
            repath = 10;
            // A partial path leads to the reachable spot nearest the player: the end of the tunnel.
            Path path = creeper.getNavigation().createPath(target, 0);
            if (path != null) {
                creeper.getNavigation().moveTo(path, 1.0D);
            } else {
                creeper.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
            }
        }

        double dx = creeper.getX() - lastX;
        double dz = creeper.getZ() - lastZ;
        lastX = creeper.getX();
        lastZ = creeper.getZ();

        if (!creeper.isIgnited()) {
            boolean still = dx * dx + dz * dz < STILL_SQR && creeper.distanceToSqr(target) > MIN_BLAST_DIST_SQR;
            stuck = still ? stuck + 1 : 0;
            if (stuck >= STUCK_TICKS) {
                creeper.ignite();
            }
        }
    }
}
