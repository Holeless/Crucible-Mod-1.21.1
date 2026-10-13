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
 * Breaks boats and minecarts, in two situations:
 *
 *  1. The mob is sitting in one (trapped). It hits the vehicle until it breaks, which frees it. Any hostile does this.
 *  2. {@code melee} mobs only: the mob's target is riding one. It goes for the vehicle instead, which drops the
 *     rider out. Gives up for a few seconds if it cannot path to it.
 */
public class ExtricationVehicleGoal extends Goal {
    private static final int ATTACK_COOLDOWN = 20;
    private static final int MAX_FAILED_PATHS = 3;
    private static final long RETRY_DELAY = 100L;
    private static final float FALLBACK_DAMAGE = 3.0F;

    private final PathfinderMob mob;
    private final boolean melee;
    private Entity vehicle;
    private int cooldown;
    private int repath;
    private int failedPaths;
    private long retryAt;

    public ExtricationVehicleGoal(PathfinderMob mob, boolean melee) {
        this.mob = mob;
        this.melee = melee;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private static Entity breakable(Entity v) {
        return (v instanceof Boat || v instanceof AbstractMinecart) && v.isAlive() ? v : null;
    }

    /** The vehicle this mob is in, else (melee only) the one its target is in. */
    private Entity findVehicle() {
        Entity ridden = breakable(mob.getVehicle());
        if (ridden != null) return ridden;
        if (!melee) return null;

        LivingEntity target = mob.getTarget();
        if (!(target instanceof Player) || !target.isAlive()) return null;
        return breakable(target.getVehicle());
    }

    @Override
    public boolean canUse() {
        if (!Extrication.enabled()) return false;
        if (mob.level().getGameTime() < retryAt) return false;
        vehicle = findVehicle();
        return vehicle != null;
    }

    @Override
    public boolean canContinueToUse() {
        return Extrication.enabled()
                && failedPaths < MAX_FAILED_PATHS
                && vehicle != null
                && vehicle.isAlive()
                && findVehicle() == vehicle;
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
        if (cooldown > 0) cooldown--;

        boolean inside = mob.getVehicle() == vehicle;
        if (!inside) mob.getLookControl().setLookAt(vehicle, 30.0F, 30.0F);

        double reach = mob.getBbWidth() * 2.0 * mob.getBbWidth() * 2.0 + vehicle.getBbWidth() + 1.0;
        if (inside || mob.distanceToSqr(vehicle) <= reach) {
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
