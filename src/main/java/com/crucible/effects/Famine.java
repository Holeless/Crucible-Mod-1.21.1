package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Famine: hunger drains faster.
 *
 * Hunger is driven by "exhaustion". Each tick we measure how much exhaustion the player just gained
 * (sprinting, jumping, mining, healing, ...) and add an extra share on top.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Famine {
    /** 0.5 = hunger drains 50% faster. Raise it for harsher famine. */
    private static final float EXTRA = 0.5F;

    private static final Map<UUID, Float> LAST = new HashMap<>();

    private Famine() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CrucibleManager.isActive(1)) return;

        UUID id = player.getUUID();
        if (player.isCreative() || player.isSpectator()) {
            LAST.remove(id);
            return;
        }

        FoodData food = player.getFoodData();
        float now = food.getExhaustionLevel();
        Float last = LAST.get(id);
        if (last != null) {
            float gained = now - last;
            if (gained < 0) gained += 4.0F; // vanilla subtracts 4 whenever exhaustion passes 4
            if (gained > 0) {
                food.addExhaustion(gained * EXTRA);
                now = food.getExhaustionLevel();
            }
        }
        LAST.put(id, now);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST.clear();
    }
}
