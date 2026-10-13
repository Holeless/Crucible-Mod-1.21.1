package com.crucible.effects;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

import java.util.EnumSet;

/**
 * When a hostile mob's target is riding a boat or minecart, the mob goes for the vehicle instead and
 * hits it until it breaks, which drops the rider out. Gives up for a few seconds if it cannot path to it.
 */
public class ExtricationVehicleGoal extends Goal {
    private static final int ATTACK_COOLDOWN = 20;
    private static final int MAX_FAILED_PATHS = 3;
    private static final long RETRY_DELAY = 100L;
    private static final float FALLBACK_DAMAGE = 3.0F;

    private final PathfinderMob mob;
    private Entity vehicle;
    private int cooldown;
    private int repath;
    private int failedPaths;
    private long retryAt;

    public ExtricationVehicleGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private static Entity vehicleOf(LivingEntity target) {
        if (target == null) return null;
        Entity v = target.getVehicle();
        return (v instanceof Boat || v instanceof AbstractMinecart) && v.isAlive() ? v : null;
    }

    @Override
    public boolean canUse() {
        if (!Extrication.enabled()) return false;
        if (mob.level().getGameTime() < retryAt) return false;
        LivingEntity target = mob.getTarget();
        if (!(target instanceof Player) || !target.isAlive()) return false;
        vehicle = vehicleOf(target);
        return vehicle != null;
    }

    @Override
    public boolean canContinueToUse() {
        return Extrication.enabled()
                && failedPaths < MAX_FAILED_PATHS
                && vehicle != null
                && vehicle.isAlive()
                && vehicleOf(mob.getTarget()) == vehicle;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        cooldown = 0;
        repath = 0;
        failedPaths = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (failedPaths >= MAX_FAILED_PATHS) retryAt = mob.level().getGameTime() + RETRY_DELAY;
        vehicle = null;
    }

    @Override
    public void tick() {
        if (vehicle == null) return;
        mob.getLookControl().setLookAt(vehicle, 30.0F, 30.0F);
        if (cooldown > 0) cooldown--;

        double reach = mob.getBbWidth() * 2.0 * mob.getBbWidth() * 2.0 + vehicle.getBbWidth() + 1.0;
        if (mob.distanceToSqr(vehicle) <= reach) {
            mob.getNavigation().stop();
            failedPaths = 0;
            if (cooldown <= 0) {
                cooldown = ATTACK_COOLDOWN;
                mob.swing(InteractionHand.MAIN_HAND);
                vehicle.hurt(mob.damageSources().mobAttack(mob), damage());
            }
        } else if (--repath <= 0) {
            repath = 10;
            if (mob.getNavigation().moveTo(vehicle, 1.2D)) failedPaths = 0;
            else failedPaths++;
        }
    }

    /** Some mobs have no attack attribute, and reading a missing one throws. */
    private float damage() {
        return mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                ? (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE)
                : FALLBACK_DAMAGE;
    }
}
