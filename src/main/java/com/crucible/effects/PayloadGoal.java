package com.crucible.effects;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * A phantom that is hunting a player looks for a creeper nearby, flies down, picks it up, carries it over the
 * player and drops it. The phantom's own circle/swoop goals pause while this runs and resume afterwards.
 *
 * Phantoms steer by {@code moveTargetPoint} (made public by the access transformer), not by the usual wanted
 * position, so that is what this goal sets every tick.
 */
public class PayloadGoal extends Goal {
    private static final double SEARCH_RADIUS = 20.0;   // blocks around the phantom to look for a creeper
    private static final double MAX_PLAYER_DIST = 48.0; // only when its target is this close
    private static final float CHANCE = 0.5F;           // per search
    private static final int SEARCH_INTERVAL = 40;      // ticks between searches
    private static final int COOLDOWN = 600;            // ticks before the same phantom does it again
    private static final int PICKUP_TIMEOUT = 200;
    private static final int CARRY_TIMEOUT = 600;
    private static final double GRAB_DIST_SQR = 2.5 * 2.5;
    private static final double DROP_HEIGHT = 12.0;     // flies this far above the player
    private static final double MIN_DROP_HEIGHT = 6.0;  // lets go once at least this high and overhead
    private static final double DROP_RADIUS_SQR = 3.0 * 3.0;

    private enum Phase { PICKUP, CARRY }

    private final Phantom phantom;
    private Creeper creeper;
    private Player victim;
    private Phase phase = Phase.PICKUP;
    private int ticks;
    private int searchDelay;
    private long readyAt;
    private boolean done;

    public PayloadGoal(Phantom phantom) {
        this.phantom = phantom;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!Payload.enabled() || !phantom.isAlive() || phantom.isVehicle()) return false;
        if (phantom.level().getGameTime() < readyAt) return false;
        if (--searchDelay > 0) return false;
        searchDelay = SEARCH_INTERVAL;

        LivingEntity target = phantom.getTarget();
        if (!(target instanceof Player player) || !player.isAlive() || player.isSpectator()) return false;
        if (phantom.distanceToSqr(player) > MAX_PLAYER_DIST * MAX_PLAYER_DIST) return false;
        if (phantom.getRandom().nextFloat() >= CHANCE) return false;

        List<Creeper> found = phantom.level().getEntitiesOfClass(
                Creeper.class,
                phantom.getBoundingBox().inflate(SEARCH_RADIUS),
                c -> c.isAlive()
                        && !c.isPassenger()
                        && !c.isVehicle()
                        && !c.isIgnited()
                        && !c.getPersistentData().getBoolean(Payload.CARRIED)
                        && phantom.hasLineOfSight(c));
        if (found.isEmpty()) return false;

        Creeper nearest = null;
        double best = Double.MAX_VALUE;
        for (Creeper c : found) {
            double d = phantom.distanceToSqr(c);
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        creeper = nearest;
        victim = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (done || !Payload.enabled() || !phantom.isAlive()) return false;
        if (creeper == null || !creeper.isAlive()) return false;
        if (victim == null || !victim.isAlive() || victim.level() != phantom.level()) return false;
        return switch (phase) {
            case PICKUP -> ticks < PICKUP_TIMEOUT && !creeper.isPassenger();
            case CARRY -> ticks < CARRY_TIMEOUT && creeper.getVehicle() == phantom;
        };
    }

    @Override
    public void start() {
        phase = Phase.PICKUP;
        ticks = 0;
        done = false;
    }

    @Override
    public void stop() {
        // Whatever ended this (timeout, player died, phantom hurt), do not keep carrying.
        if (creeper != null && creeper.getVehicle() == phantom) release();
        readyAt = phantom.level().getGameTime() + COOLDOWN;
        creeper = null;
        victim = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        if (creeper == null || victim == null) return;

        if (phase == Phase.PICKUP) {
            // Hover just above the creeper's head.
            phantom.moveTargetPoint = creeper.position().add(0.0, creeper.getBbHeight() + 0.4, 0.0);

            if (phantom.distanceToSqr(creeper) < GRAB_DIST_SQR) {
                if (creeper.startRiding(phantom, true)) {
                    creeper.getPersistentData().putBoolean(Payload.CARRIED, true);
                    phase = Phase.CARRY;
                    ticks = 0;
                } else {
                    done = true; // somebody else got it first
                }
            }
            return;
        }

        // CARRY: climb and fly to a point above the player.
        phantom.moveTargetPoint = new Vec3(victim.getX(), victim.getY() + DROP_HEIGHT, victim.getZ());

        double dx = phantom.getX() - victim.getX();
        double dz = phantom.getZ() - victim.getZ();
        boolean overhead = dx * dx + dz * dz < DROP_RADIUS_SQR;
        if (overhead && phantom.getY() - victim.getY() >= MIN_DROP_HEIGHT) {
            release();
            done = true;
        }
    }

    private void release() {
        creeper.stopRiding();
        if (victim != null && victim.isAlive()) creeper.setTarget(victim);
    }
}
