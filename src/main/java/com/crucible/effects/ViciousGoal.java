package com.crucible.effects;

import com.crucible.CrucibleManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Makes an angered passive mob chase and bite its target. Highest priority, so it overrides panic/flee goals.
 * The target itself is set by {@link Vicious} when the pack is alerted.
 *
 * Horses use a shorter leash, give up sooner and chase at normal speed, so you can get away from them.
 */
public class ViciousGoal extends Goal {
    private static final int ATTACK_COOLDOWN = 20;

    private final Mob mob;
    private final double maxDistanceSqr;
    private final int giveUpTicks;     // ticks without line of sight before it gives up
    private final double chaseSpeed;
    private int cooldown;
    private int unseen;
    private int repath;

    public ViciousGoal(Mob mob) {
        this.mob = mob;
        boolean horse = mob instanceof AbstractHorse;
        this.maxDistanceSqr = horse ? 12.0 * 12.0 : 32.0 * 32.0;
        this.giveUpTicks = horse ? 60 : 200;
        this.chaseSpeed = horse ? 1.0D : 1.4D;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!CrucibleManager.isActive(1)) return false;
        LivingEntity target = mob.getTarget();
        return target != null
                && target.isAlive()
                && mob.canAttack(target)
                && Vicious.isPassive(mob)
                && Vicious.canFight(mob);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() && unseen < giveUpTicks && mob.distanceToSqr(mob.getTarget()) < maxDistanceSqr;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        unseen = 0;
        cooldown = 0;
        repath = 0;
        mob.setAggressive(true);
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        boolean sees = mob.getSensing().hasLineOfSight(target);
        unseen = sees ? 0 : unseen + 1;

        // Brain-driven mobs (villagers, goats, ...) would otherwise panic or walk away.
        mob.getBrain().eraseMemory(MemoryModuleType.HURT_BY);
        mob.getBrain().eraseMemory(MemoryModuleType.HURT_BY_ENTITY);
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        chase(target);

        if (cooldown > 0) cooldown--;
        double reach = Math.max(2.5, mob.getBbWidth() * 2.0 * mob.getBbWidth() * 2.0 + target.getBbWidth());
        if (cooldown <= 0 && sees && mob.distanceToSqr(target) <= reach) {
            cooldown = ATTACK_COOLDOWN;
            mob.swing(InteractionHand.MAIN_HAND);
            mob.doHurtTarget(target);
        }
    }

    private void chase(LivingEntity target) {
        Vec3 toTarget = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(mob.position());

        if (mob instanceof Squid squid) {
            // Squids do not use pathfinding, they swim along a movement vector.
            if (toTarget.lengthSqr() > 0.01) {
                Vec3 n = toTarget.normalize().scale(0.2);
                squid.setMovementVector((float) n.x, (float) n.y, (float) n.z);
            }
            return;
        }
        if (mob instanceof Bat bat) {
            // Bats fly by velocity and do not use pathfinding.
            bat.setResting(false);
            if (toTarget.lengthSqr() > 0.01) {
                bat.setDeltaMovement(bat.getDeltaMovement().add(toTarget.normalize().scale(0.08)));
            }
            return;
        }

        if (--repath <= 0) {
            repath = 4;
            mob.getNavigation().moveTo(target, chaseSpeed);
        }
    }
}
