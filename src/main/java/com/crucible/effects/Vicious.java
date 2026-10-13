package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.List;

/**
 * Vicious: passive mobs (animals, villagers, fish, ...) are neutral. Hurt or kill one and every other
 * mob of the same type nearby turns on you, like a wolf pack.
 *
 * Tamed pets, golems and mobs that are already hostile or neutral are left alone.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Vicious {
    private static final double ATTACK_DAMAGE = 3.0;
    private static final double ALERT_RADIUS = 16.0;
    private static final double ALERT_HEIGHT = 10.0;

    private Vicious() {}

    /** Mod-bus: most passive mobs have no attack attributes, and attacking without them would crash. */
    public static void onAttributes(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            if (!event.has(type, Attributes.ATTACK_DAMAGE)) event.add(type, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE);
            if (!event.has(type, Attributes.ATTACK_KNOCKBACK)) event.add(type, Attributes.ATTACK_KNOCKBACK);
        }
    }

    public static boolean isPassive(Mob mob) {
        if (mob instanceof Enemy || mob instanceof NeutralMob || mob instanceof AbstractGolem) return false;
        if (mob instanceof TamableAnimal tame && tame.isTame()) return false;
        if (mob instanceof AbstractHorse horse && horse.isTamed()) return false;
        return mob instanceof Animal
                || mob instanceof AgeableMob          // villagers, wandering traders, ...
                || mob instanceof WaterAnimal         // fish, squid, dolphins, tadpoles
                || mob instanceof AmbientCreature     // bats
                || mob instanceof Allay;
    }

    public static boolean canFight(Mob mob) {
        return mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                && mob.getAttributes().hasAttribute(Attributes.ATTACK_KNOCKBACK);
    }

    /** Gives every non-hostile mob the chase-and-bite goal. It only activates when it has a target. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (mob instanceof Enemy || mob instanceof NeutralMob || mob instanceof AbstractGolem) return;

        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ViciousGoal) return;
        }
        mob.goalSelector.addGoal(0, new ViciousGoal(mob));
    }

    /** Any damage from a player (a punch, an arrow, the killing blow) alerts the whole pack. */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (!CrucibleManager.isActive(1)) return;
        if (!(event.getEntity() instanceof Mob victim) || victim.level().isClientSide()) return;
        if (!isPassive(victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (player.isCreative() || player.isSpectator()) return;

        List<Mob> pack = victim.level().getEntitiesOfClass(
                Mob.class,
                victim.getBoundingBox().inflate(ALERT_RADIUS, ALERT_HEIGHT, ALERT_RADIUS),
                m -> m.getType() == victim.getType() && m.isAlive() && !m.isBaby() && isPassive(m));
        for (Mob mob : pack) {
            mob.setTarget(player);
        }
    }
}
