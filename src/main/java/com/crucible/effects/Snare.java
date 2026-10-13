package com.crucible.effects;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Snare: a spider (cave spiders too, they are Spiders) that gets close to its target spins a cobweb onto the
 * player's feet.
 *
 * Reach scales with the spider's size: a normal spider webs you from about 1 block away, a cave spider from
 * about 0.75. It checks 5 times a second and each check has a 40% chance, so being next to one for a second or
 * two is almost always enough, but not guaranteed. Needs line of sight. At most one web per spider every 2
 * seconds, only into air or replaceable blocks (grass, snow), and they dissolve after 30 seconds.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Snare {
    private static final int CHECK_INTERVAL = 4;      // ticks between checks
    private static final float CHANCE = 0.4F;         // per check
    private static final int COOLDOWN = 40;
    private static final int WEB_LIFETIME = 600;

    private record Web(ResourceKey<Level> dimension, BlockPos pos, long expires) {}

    private static final Map<Mob, Long> LAST_WEB = new WeakHashMap<>();
    private static final List<Web> WEBS = new ArrayList<>();

    private Snare() {}

    /** Centre-to-centre distance: spider 2.2 blocks, cave spider 1.5. */
    private static double triggerDistance(Spider spider) {
        return 0.8 + spider.getBbWidth();
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Spider spider) || spider.level().isClientSide()) return;
        if (spider.tickCount % CHECK_INTERVAL != 0 || !spider.isAlive()) return;
        if (!CrucibleManager.isActive(1)) return;
        if (!(spider.getTarget() instanceof ServerPlayer player) || !player.isAlive()) return;
        if (player.isCreative() || player.isSpectator()) return;

        double reach = triggerDistance(spider);
        if (spider.distanceToSqr(player) > reach * reach) return;
        if (!spider.getSensing().hasLineOfSight(player)) return;

        ServerLevel level = (ServerLevel) spider.level();
        long now = level.getGameTime();
        Long last = LAST_WEB.get(spider);
        if (last != null && now - last < COOLDOWN) return;
        if (spider.getRandom().nextFloat() >= CHANCE) return;

        BlockPos pos = player.blockPosition();
        BlockState there = level.getBlockState(pos);
        boolean free = there.isAir() || (there.canBeReplaced() && there.getFluidState().isEmpty());
        if (!free) return;

        BlockState web = Blocks.COBWEB.defaultBlockState();
        level.setBlockAndUpdate(pos, web);
        level.playSound(null, pos, web.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 1.0F);

        LAST_WEB.put(spider, now);
        WEBS.add(new Web(level.dimension(), pos, now + WEB_LIFETIME));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || WEBS.isEmpty()) return;

        long now = level.getGameTime();
        WEBS.removeIf(w -> {
            if (w.dimension() != level.dimension() || now < w.expires()) return false;
            if (!level.hasChunkAt(w.pos())) return false; // wait until the chunk is loaded again
            if (level.getBlockState(w.pos()).is(Blocks.COBWEB)) level.removeBlock(w.pos(), false);
            return true;
        });
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WEBS.clear();
        LAST_WEB.clear();
    }
}
