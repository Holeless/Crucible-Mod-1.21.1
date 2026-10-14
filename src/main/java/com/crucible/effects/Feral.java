package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.function.Predicate;

/**
 * Feral: neutral mobs are fully hostile.
 *
 * Every half second, each idle neutral mob (wild wolves, bees, polar bears, Endermen, zombified piglins, iron golems,
 * and spiders, which are only neutral in daylight) picks the nearest valid player and targets them, exactly as if
 * it were a zombie. With {@link Omniscience} active the search has no distance limit; otherwise it uses the mob's own
 * follow range.
 *
 * Not affected: tamed pets, passive animals (including ones {@code Vicious} made neutral), and golems you built
 * yourself (vanilla never lets those attack players). Endermen still ignore a player wearing a carved pumpkin, unless
 * that player hit them first.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Feral {
    private Feral() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(2);
    }

    static boolean isFeral(Mob mob) {
        if (mob instanceof TamableAnimal tame && tame.isTame()) return false;
        return mob instanceof NeutralMob || mob instanceof Spider;
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if ((mob.tickCount + mob.getId()) % 10 != 0) return;
        if (!enabled() || !mob.isAlive() || mob.isNoAi() || !isFeral(mob)) return;

        LivingEntity current = mob.getTarget();
        if (current != null) {
            // Someone put a pumpkin on: a calm Enderman forgets them. One that was hit stays angry.
            if (mob instanceof EnderMan enderman
                    && Omniscience.wearsPumpkin(current)
                    && enderman.getLastHurtByMob() != current) {
                enderman.stopBeingAngry();
            }
            return;
        }

        Predicate<Entity> filter = Omniscience.playerFilter(mob);
        double range = -1.0; // no limit
        if (!Omniscience.enabled()) {
            range = mob.getAttributes().hasAttribute(Attributes.FOLLOW_RANGE)
                    ? mob.getAttributeValue(Attributes.FOLLOW_RANGE)
                    : 16.0;
        }

        Player player = mob.level().getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), range, filter);
        if (player != null) {
            mob.setTarget(player);
        }
    }
}
