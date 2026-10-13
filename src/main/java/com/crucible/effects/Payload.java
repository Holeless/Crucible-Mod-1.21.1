package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Payload: phantoms can pick up creepers and drop them onto you.
 *
 * A phantom hunting a player grabs a nearby creeper, carries it over the player and lets go (see
 * {@link PayloadGoal}). This class handles the creeper's side of it: a creeper that was carried takes no fall
 * damage, and when it lands near a player its fuse is lit.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Payload {
    /** Set on a creeper when a phantom picks it up. */
    static final String CARRIED = "crucible_payload";
    /** Game time at which the creeper stopped being carried. */
    static final String FREED = "crucible_payload_freed";

    private static final int LAND_DELAY = 3;      // ticks after release before a landing counts
    private static final int GIVE_UP = 600;       // ticks after release before the tag is forgotten
    private static final double IGNITE_RANGE = 24.0;

    private Payload() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(1);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Phantom phantom)) return;

        for (WrappedGoal wrapped : phantom.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof PayloadGoal) return;
        }
        // Priority 0 beats the phantom's own movement goals, which share the MOVE flag.
        phantom.goalSelector.addGoal(0, new PayloadGoal(phantom));
    }

    /** A carried creeper would usually die from the drop, which would defeat the point. */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) return;
        if (creeper.getPersistentData().getBoolean(CARRIED)) {
            event.setDamageMultiplier(0.0F);
        }
    }

    @SubscribeEvent
    public static void onCreeperTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Creeper creeper) || creeper.level().isClientSide()) return;

        CompoundTag data = creeper.getPersistentData();
        if (!data.getBoolean(CARRIED)) return;

        if (creeper.isPassenger()) {
            data.remove(FREED);
            return;
        }

        long now = creeper.level().getGameTime();
        if (!data.contains(FREED)) {
            data.putLong(FREED, now);
            return;
        }

        long since = now - data.getLong(FREED);
        if (since > GIVE_UP) {
            data.remove(CARRIED);
            data.remove(FREED);
        } else if (since >= LAND_DELAY && creeper.onGround()) {
            data.remove(CARRIED);
            data.remove(FREED);
            Player near = creeper.level().getNearestPlayer(creeper, IGNITE_RANGE);
            if (near != null && !near.isSpectator()) {
                creeper.ignite(); // lit on landing, vanilla fuse
            }
        }
    }
}
