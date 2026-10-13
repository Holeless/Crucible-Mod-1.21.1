package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Brittle: every damageable item (tools, weapons, armor, shields, ...) has 2/3 of its normal durability.
 *
 * Any damageable stack in a player's inventory (or on their cursor) that still has its original max
 * durability gets its max durability set to 2/3 of the original. Existing wear is scaled to match, so a
 * half-used tool stays half-used. Items you have not picked up yet (chests, the ground) keep their
 * normal durability until you do.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Brittle {
    private Brittle() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CrucibleManager.isActive(1)) return;

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            weaken(inv.getItem(i));
        }
        weaken(player.containerMenu.getCarried());
    }

    private static void weaken(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return;

        Integer baseObj = stack.getItem().components().get(DataComponents.MAX_DAMAGE);
        if (baseObj == null) return;

        int base = baseObj;
        int target = Math.max(1, base * 2 / 3);
        if (target >= base) return;
        if (stack.getMaxDamage() != base) return; // already weakened

        int damage = stack.getDamageValue();
        stack.set(DataComponents.MAX_DAMAGE, target);
        stack.setDamageValue(Math.min(target - 1, (int) ((long) damage * target / base)));
    }
}
