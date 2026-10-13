package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Marksman: skeletons have a higher chance to spawn with a randomly enchanted bow.
 *
 * Each skeleton is rolled once, the first time it enters the world. A skeleton holding a plain bow has a
 * 60% chance to have it enchanted with 1-3 random, compatible, non-treasure, non-curse bow enchantments
 * at random levels. Skeletons that vanilla already enchanted are left alone.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Marksman {
    private static final float CHANCE = 0.6F;
    private static final String ROLLED_KEY = "crucible_marksman_rolled";

    private Marksman() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!CrucibleManager.isActive(1)) return;
        if (!(event.getEntity() instanceof AbstractSkeleton skeleton)) return;

        CompoundTag data = skeleton.getPersistentData();
        if (data.getBoolean(ROLLED_KEY)) return;
        data.putBoolean(ROLLED_KEY, true);

        ItemStack bow = skeleton.getMainHandItem();
        if (!bow.is(Items.BOW) || bow.isEnchanted()) return;
        if (skeleton.getRandom().nextFloat() >= CHANCE) return;

        enchant(skeleton, bow);
    }

    private static void enchant(AbstractSkeleton skeleton, ItemStack bow) {
        RandomSource random = skeleton.getRandom();
        Registry<Enchantment> registry = skeleton.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);

        List<Holder.Reference<Enchantment>> pool = registry.holders()
                .filter(h -> h.is(EnchantmentTags.NON_TREASURE)
                        && !h.is(EnchantmentTags.CURSE)
                        && h.value().isSupportedItem(bow))
                .collect(Collectors.toCollection(ArrayList::new));

        List<Holder<Enchantment>> chosen = new ArrayList<>();
        int picks = 1 + random.nextInt(3);
        while (picks-- > 0 && !pool.isEmpty()) {
            Holder.Reference<Enchantment> pick = pool.remove(random.nextInt(pool.size()));

            boolean compatible = true;
            for (Holder<Enchantment> other : chosen) {
                if (!Enchantment.areCompatible(other, pick)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) continue;

            chosen.add(pick);
            bow.enchant(pick, 1 + random.nextInt(pick.value().getMaxLevel()));
        }
    }
}
