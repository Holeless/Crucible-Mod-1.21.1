package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Extrication: hostile mobs can break wooden doors, and smash the boat or minecart you are riding,
 * and smash any boat or minecart they are trapped in themselves.
 *
 * Doors: every hostile ground mob gets vanilla's {@link BreakDoorGoal} on any difficulty, and is allowed to
 * path through closed doors. Only wooden doors break, as in vanilla.
 *
 * Vehicles: see {@link ExtricationVehicleGoal}.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Extrication {
    private Extrication() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(1);
    }

    /** Mobs that should walk up and hit a vehicle (ranged mobs keep shooting, creepers keep exploding). */
    public static boolean isMelee(Mob mob) {
        return !(mob instanceof RangedAttackMob) && !(mob instanceof Creeper) && !(mob instanceof Blaze);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!enabled()) return;
        if (!(event.getEntity() instanceof PathfinderMob mob) || !(mob instanceof Enemy)) return;

        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof DoorGoal || goal instanceof ExtricationVehicleGoal) return;
        }

        // BreakDoorGoal throws on non-ground navigation, so check first.
        if (mob.getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true); // lets pathfinding route through closed doors
            mob.goalSelector.addGoal(1, new DoorGoal(mob));
        }
        // Every hostile gets this so it can break out of a vehicle; only melee mobs also go after yours.
        mob.goalSelector.addGoal(0, new ExtricationVehicleGoal(mob, isMelee(mob)));
    }

    /** Vanilla door breaking, on every difficulty. */
    public static class DoorGoal extends BreakDoorGoal {
        public DoorGoal(Mob mob) {
            super(mob, difficulty -> true);
        }

        @Override
        public boolean canUse() {
            return enabled() && super.canUse();
        }
    }
}
