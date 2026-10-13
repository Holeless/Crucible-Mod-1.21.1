package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * Unity: hostile mobs never get aggravated at each other.
 *
 * Any attempt by a hostile mob to target another hostile mob (retaliation, infighting, hunting) is
 * turned into "keep whatever you were already targeting".
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Unity {
    private Unity() {}

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!CrucibleManager.isActive(1)) return;
        if (!(event.getEntity() instanceof Enemy) || !(event.getEntity() instanceof Mob mob)) return;
        if (!(event.getNewAboutToBeSetTarget() instanceof Enemy)) return;

        event.setNewAboutToBeSetTarget(mob.getTarget());
    }
}
