package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Brittle: armor, tools and weapons lose durability 50% faster.
 *
 * Every tick we compare each equipped item's damage with last tick. For every point of durability the
 * item just lost, there is a 50% chance it loses one extra point. The extra damage goes through the
 * normal hurtAndBreak path, so breaking, sounds, Unbreaking and advancement triggers all behave normally.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Brittle {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private static final class Tracked {
        final ItemStack[] stacks = new ItemStack[SLOTS.length];
        final int[] damage = new int[SLOTS.length];
    }

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();

    private Brittle() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CrucibleManager.isActive(1)) return;
        if (player.isCreative() || player.isSpectator()) return;

        Tracked t = TRACKED.computeIfAbsent(player.getUUID(), id -> new Tracked());
        for (int i = 0; i < SLOTS.length; i++) {
            EquipmentSlot slot = SLOTS[i];
            ItemStack stack = player.getItemBySlot(slot);

            if (stack.isEmpty() || !stack.isDamageableItem()) {
                t.stacks[i] = null;
                continue;
            }

            int damage = stack.getDamageValue();
            // Same stack object as last tick and it lost durability: roll the bonus damage.
            if (t.stacks[i] == stack && damage > t.damage[i]) {
                int extra = 0;
                for (int n = damage - t.damage[i]; n > 0; n--) {
                    if (player.getRandom().nextBoolean()) extra++;
                }
                if (extra > 0) {
                    stack.hurtAndBreak(extra, player, slot);
                    if (stack.isEmpty()) { // it broke
                        t.stacks[i] = null;
                        continue;
                    }
                    damage = stack.getDamageValue();
                }
            }
            t.stacks[i] = stack;
            t.damage[i] = damage;
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TRACKED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKED.clear();
    }
}
