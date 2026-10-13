package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Vigilance: hostile mob detection range is doubled.
 *
 * Every half second, each idle hostile mob that normally hunts players looks for the nearest visible
 * player within twice its base detection range, and targets them. Stealth modifiers (sneaking, mob
 * heads, invisibility) and line of sight apply exactly as in vanilla.
 *
 * Mobs that are neutral until provoked (endermen, zombified piglins, silverfish, piglins, ...) are not
 * touched, and spiders stay passive in daylight like vanilla.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Vigilance {
    private Vigilance() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if ((mob.tickCount + mob.getId()) % 10 != 0) return;
        if (!CrucibleManager.isActive(1)) return;
        if (mob.getTarget() != null || mob.isNoAi() || !mob.isAlive()) return;
        if (!huntsPlayers(mob)) return;
        if (!mob.getAttributes().hasAttribute(Attributes.FOLLOW_RANGE)) return;

        double range = mob.getAttributeBaseValue(Attributes.FOLLOW_RANGE) * 2.0;
        Player player = mob.level().getNearestPlayer(TargetingConditions.forCombat().range(range), mob);
        if (player != null) {
            mob.setTarget(player);
        }
    }

    private static boolean huntsPlayers(Mob mob) {
        if (mob instanceof NeutralMob) return false;
        if (mob instanceof Spider) return mob.getLightLevelDependentMagicValue() < 0.5F; // passive in daylight
        return mob instanceof Zombie
                || mob instanceof AbstractSkeleton
                || mob instanceof Creeper
                || mob instanceof Raider      // pillagers, vindicators, evokers, ravagers, witches
                || mob instanceof Slime
                || mob instanceof Blaze
                || mob instanceof Endermite;
    }
}
