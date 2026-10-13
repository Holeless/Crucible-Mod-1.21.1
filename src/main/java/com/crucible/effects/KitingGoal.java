package com.crucible.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Kiting for ranged mobs (bow skeletons, witches). While they have a player target they:
 *
 *  - back away whenever you get within {@link #RETREAT_DIST} blocks, and keep shooting while they do it;
 *  - after each shot, look for a spot nearby where a block or another mob is between them and you, and
 *    wait there until their next attack is ready;
 *  - when the attack is ready and they cannot see you, walk back out until they can, then shoot.
 *
 * This replaces the vanilla attack goal while it runs, so the shooting is done here.
 */
public class KitingGoal extends Goal {
    private static final double RETREAT_DIST = 7.0;
    private static final double TARGET_RANGE = 28.0;   // only kite when the target is this close
    private static final double MIN_COVER_DIST = 5.0;  // cover spots must be at least this far from you
    private static final int HIDE_RADIUS = 8;
    private static final int SAMPLES = 14;
    private static final int REPATH = 10;
    private static final int DRAW_TICKS = 20;
    private static final double FLEE_SPEED = 1.2D;
    private static final double HIDE_SPEED = 1.15D;

    private final PathfinderMob mob;
    private final boolean bow;
    private final double shootRange;
    private int cooldown;
    private int repath;

    public KitingGoal(PathfinderMob mob) {
        this.mob = mob;
        this.bow = mob instanceof AbstractSkeleton;
        this.shootRange = bow ? 15.0 : 12.0;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!Kiting.enabled()) return false;
        if (!(mob instanceof RangedAttackMob)) return false;
        LivingEntity target = mob.getTarget();
        if (!(target instanceof Player) || !target.isAlive()) return false;
        if (bow && !mob.isHolding(Items.BOW)) return false; // melee skeletons are left to vanilla
        return mob.distanceToSqr(target) < TARGET_RANGE * TARGET_RANGE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repath = 0;
        mob.setAggressive(true);
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        mob.getNavigation().stop();
        if (bow) mob.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        double dist = mob.distanceTo(target);
        boolean sees = mob.getSensing().hasLineOfSight(target);
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (cooldown > 0) cooldown--;
        if (repath > 0) repath--;

        if (bow) tickBow(target, sees, dist);
        else tickThrow(target, sees, dist);

        if (dist < RETREAT_DIST) {
            if (repath <= 0) {
                repath = REPATH;
                backAway(target);
            }
        } else if (cooldown > 0 && !mob.isUsingItem()) {
            // Between attacks: get out of sight.
            if (repath <= 0) {
                repath = REPATH;
                hide(target);
            }
        } else if (!sees || dist > shootRange) {
            // Ready to attack but no shot: step back out into the open.
            if (repath <= 0) {
                repath = REPATH;
                mob.getNavigation().moveTo(target, 1.0D);
            }
        } else {
            mob.getNavigation().stop(); // clear shot, stand and aim
        }
    }

    private void tickBow(LivingEntity target, boolean sees, double dist) {
        if (mob.isUsingItem()) {
            if (!sees) {
                mob.stopUsingItem();
                return;
            }
            int used = mob.getTicksUsingItem();
            if (used >= DRAW_TICKS) {
                mob.stopUsingItem();
                ((RangedAttackMob) mob).performRangedAttack(target, BowItem.getPowerForTime(used));
                cooldown = mob.level().getDifficulty() == Difficulty.HARD ? 20 : 40;
            }
        } else if (cooldown <= 0 && sees && dist <= shootRange) {
            mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(mob, Items.BOW));
        }
    }

    private void tickThrow(LivingEntity target, boolean sees, double dist) {
        if (cooldown <= 0 && sees && dist <= shootRange) {
            ((RangedAttackMob) mob).performRangedAttack(target, 1.0F); // witch skips this while drinking
            cooldown = 60;
        }
    }

    private void backAway(LivingEntity target) {
        Vec3 away = LandRandomPos.getPosAway(mob, 12, 5, target.position());
        if (away != null) mob.getNavigation().moveTo(away.x, away.y, away.z, FLEE_SPEED);
    }

    private void hide(LivingEntity target) {
        List<Mob> blockers = mob.level().getEntitiesOfClass(
                Mob.class,
                mob.getBoundingBox().minmax(target.getBoundingBox()).inflate(4.0),
                m -> m != mob && m != target && m.isAlive());

        if (isHidden(mob.getEyePosition(), target, blockers)) {
            mob.getNavigation().stop(); // already behind something, wait here
            return;
        }

        RandomSource random = mob.getRandom();
        BlockPos origin = mob.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            BlockPos p = origin.offset(
                    random.nextInt(HIDE_RADIUS * 2 + 1) - HIDE_RADIUS,
                    random.nextInt(7) - 3,
                    random.nextInt(HIDE_RADIUS * 2 + 1) - HIDE_RADIUS);
            if (!standable(p)) continue;

            Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + mob.getEyeHeight(), p.getZ() + 0.5);
            if (eye.distanceToSqr(target.position()) < MIN_COVER_DIST * MIN_COVER_DIST) continue;
            if (!isHidden(eye, target, blockers)) continue;

            double d = p.distSqr(origin);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }

        if (best != null) {
            mob.getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, HIDE_SPEED);
        } else {
            backAway(target); // no cover around, just keep distance
        }
    }

    /** True if a block or another mob is between {@code from} and the target's eyes. */
    private boolean isHidden(Vec3 from, LivingEntity target, List<Mob> blockers) {
        Vec3 to = target.getEyePosition();
        BlockHitResult hit = mob.level().clip(
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() != HitResult.Type.MISS) return true;

        AABB segment = new AABB(from, to).inflate(1.0);
        for (Mob other : blockers) {
            if (other.getBoundingBox().intersects(segment) && other.getBoundingBox().clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean standable(BlockPos p) {
        Level level = mob.level();
        BlockPos above = p.above();
        BlockPos below = p.below();
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(above).getCollisionShape(level, above).isEmpty()
                && level.getFluidState(p).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }
}
