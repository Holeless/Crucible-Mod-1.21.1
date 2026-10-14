package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.hoglin.HoglinBase;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.function.Predicate;

/**
 * Omniscience: mobs have infinite detection and follow range.
 *
 * Detection: every half second, each idle hostile mob (and every Enderman) picks the nearest valid player in its
 * dimension, no matter how far away or whether it can see them. Mobs only exist in loaded chunks, so in practice
 * this means every mob near you comes for you at once. Stealth does not apply. The one exception is the carved
 * pumpkin: Endermen still ignore a player wearing one.
 *
 * Follow range: a flat +{@link #EXTRA_RANGE} on the follow range attribute, so vanilla attack goals keep chasing
 * and pathfinding reaches that far. It is not literally infinite because the pathfinder allocates memory in
 * proportion to this number.
 *
 * Getting to you: see {@link OmniscienceCreeperGoal} (creepers blow tunnels) and {@link OmniscienceEndermanGoal}
 * (Endermen teleport, walk, or dig).
 *
 * Neutral mobs (wolves, iron golems, ...) are made hostile by {@link Feral}, which uses the same unlimited range.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Omniscience {
    private static final ResourceLocation RANGE_ID =
            ResourceLocation.fromNamespaceAndPath(Crucible.MOD_ID, "omniscience_follow_range");
    private static final double EXTRA_RANGE = 96.0;

    private Omniscience() {}

    public static boolean enabled() {
        return CrucibleManager.isActive(2);
    }

    /** A carved pumpkin on the head. This is what makes Endermen leave you alone. */
    public static boolean wearsPumpkin(Entity entity) {
        return entity instanceof Player player
                && player.getItemBySlot(EquipmentSlot.HEAD).is(Blocks.CARVED_PUMPKIN.asItem());
    }

    /** Which players this mob may pick as a target. Used with an unlimited-range player search. */
    static Predicate<Entity> playerFilter(Mob mob) {
        return e -> e instanceof Player p
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(p)
                && p.isAlive()
                && mob.canAttack(p)
                && !(mob instanceof EnderMan && wearsPumpkin(p));
    }

    /** Mobs whose AI is driven by a brain: setting a target on them does nothing useful, so they are left alone. */
    static boolean usesBrain(Mob mob) {
        return mob instanceof Warden
                || mob instanceof Breeze
                || mob instanceof HoglinBase
                || mob instanceof AbstractPiglin;
    }

    /** Mobs that fight players, hostile or neutral. Tamed pets are not included. */
    static boolean isHunter(Mob mob) {
        if (usesBrain(mob)) return false;
        if (mob instanceof TamableAnimal tame && tame.isTame()) return false;
        return mob instanceof Enemy || mob instanceof NeutralMob;
    }

    /** Mobs this effect sends after you by itself. Other neutral mobs are Feral's job. */
    private static boolean huntsPlayers(Mob mob) {
        if (usesBrain(mob)) return false;
        if (mob instanceof EnderMan) return true;
        if (!(mob instanceof Enemy) || mob instanceof NeutralMob) return false;
        if (mob instanceof Spider) return mob.getLightLevelDependentMagicValue() < 0.5F; // passive in daylight
        return true;
    }

    private static boolean has(Mob mob, Class<? extends Goal> type) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (type.isInstance(wrapped.getGoal())) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;

        syncRange(mob);

        if (mob instanceof Creeper creeper && !has(creeper, OmniscienceCreeperGoal.class)) {
            // Priority 1: beats the creeper's own melee goal (4) so it can walk to the blast spot.
            creeper.goalSelector.addGoal(1, new OmniscienceCreeperGoal(creeper));
        } else if (mob instanceof EnderMan enderman && !has(enderman, OmniscienceEndermanGoal.class)) {
            enderman.goalSelector.addGoal(1, new OmniscienceEndermanGoal(enderman));
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;

        int t = mob.tickCount + mob.getId();
        if (t % 20 == 0) syncRange(mob);
        if (t % 10 != 0) return;

        if (!enabled() || !mob.isAlive() || mob.isNoAi() || mob.getTarget() != null) return;
        if (!huntsPlayers(mob)) return;

        // A negative distance means "no limit".
        Player player = mob.level().getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), -1.0, playerFilter(mob));
        if (player != null) {
            mob.setTarget(player);
        }
    }

    private static void syncRange(Mob mob) {
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null) return;

        boolean has = range.hasModifier(RANGE_ID);
        boolean want = enabled() && isHunter(mob);
        if (want && !has) {
            range.addTransientModifier(
                    new AttributeModifier(RANGE_ID, EXTRA_RANGE, AttributeModifier.Operation.ADD_VALUE));
        } else if (!want && has) {
            range.removeModifier(RANGE_ID);
        }
    }
}
