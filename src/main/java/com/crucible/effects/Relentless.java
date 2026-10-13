package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Relentless: hostile mob follow range is doubled, and mobs keep tracking your last known position
 * after losing sight of you.
 *
 * Follow range: a +100% modifier on the follow range attribute. That is how far a mob keeps chasing a
 * target and how far its pathfinding reaches.
 *
 * Last known position: while a mob has a player target and can see them, we remember where they were.
 * If the mob then loses its target (you broke line of sight), {@link RelentlessGoal} walks it to that spot.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Relentless {
    private static final ResourceLocation RANGE_ID =
            ResourceLocation.fromNamespaceAndPath(Crucible.MOD_ID, "relentless_follow_range");

    private static final Map<Mob, Vec3> LAST_KNOWN = new WeakHashMap<>();

    private Relentless() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(1);
    }

    public static Vec3 lastKnown(Mob mob) {
        return LAST_KNOWN.get(mob);
    }

    public static void clear(Mob mob) {
        LAST_KNOWN.remove(mob);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) return;

        syncRange(mob);

        if (mob instanceof PathfinderMob pathfinder) {
            for (WrappedGoal wrapped : pathfinder.goalSelector.getAvailableGoals()) {
                if (wrapped.getGoal() instanceof RelentlessGoal) return;
            }
            pathfinder.goalSelector.addGoal(3, new RelentlessGoal(pathfinder));
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) return;
        if (mob.level().isClientSide()) return;

        int t = mob.tickCount;
        if (t % 20 == 0) syncRange(mob);

        if (t % 5 == 0 && enabled()) {
            LivingEntity target = mob.getTarget();
            if (target instanceof Player && target.isAlive() && mob.getSensing().hasLineOfSight(target)) {
                LAST_KNOWN.put(mob, target.position());
            }
        }
    }

    /** Runs last so it sees the target after other handlers (Unity) have had their say. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!enabled()) return;
        if (!(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) return;

        LivingEntity old = mob.getTarget();
        LivingEntity now = event.getNewAboutToBeSetTarget();

        if (now instanceof Player) {
            LAST_KNOWN.put(mob, now.position());
        } else if (now == null && old != null
                && (!old.isAlive() || old.level() != mob.level() || !mob.canAttack(old))) {
            // Target died, left the dimension or became untargetable: nothing to hunt.
            LAST_KNOWN.remove(mob);
        }
    }

    private static void syncRange(Mob mob) {
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null) return;

        boolean has = range.hasModifier(RANGE_ID);
        if (enabled() && !has) {
            range.addTransientModifier(
                    new AttributeModifier(RANGE_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        } else if (!enabled() && has) {
            range.removeModifier(RANGE_ID);
        }
    }
}
