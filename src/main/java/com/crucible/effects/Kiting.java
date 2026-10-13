package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Witch;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Kiting: bow skeletons and witches backpedal away from you while attacking, and duck behind blocks and
 * other mobs between attacks. See {@link KitingGoal} for the behavior.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Kiting {
    private Kiting() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(1);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof PathfinderMob mob)) return;
        if (!(mob instanceof AbstractSkeleton) && !(mob instanceof Witch)) return;

        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof KitingGoal) return;
        }
        // Priority 1 beats the vanilla bow goal (4) and the witch's potion goal (2), so this goal takes over
        // movement and does the shooting itself while it has a player target.
        mob.goalSelector.addGoal(1, new KitingGoal(mob));
    }
}
