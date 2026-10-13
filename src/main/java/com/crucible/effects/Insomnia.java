package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/** Insomnia: players cannot sleep. */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Insomnia {
    private Insomnia() {}

    @SubscribeEvent
    public static void onCanSleep(CanPlayerSleepEvent event) {
        if (!CrucibleManager.isActive(1)) return;

        event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        if (event.getEntity() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.literal("You cannot sleep."), true);
        }
    }
}
