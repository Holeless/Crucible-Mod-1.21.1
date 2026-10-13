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
 * Snare: a spider that gets right up next to its target spins a cobweb onto the player's feet.
 *
 * Webs only go into empty air, at most one per spider every 3 seconds, and they dissolve after 30 seconds
 * so the world does not fill up with them.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class Snare {
    /** Center-to-center distance (blocks) at which a spider webs you. Spiders are 1.4 wide, so this is "touching". */
    private static final double TRIGGER_DISTANCE = 1.5;
    private static final int COOLDOWN = 60;
    private static final int WEB_LIFETIME = 600;

    private record Web(ResourceKey<Level> dimension, BlockPos pos, long expires) {}

    private static final Map<Mob, Long> LAST_WEB = new WeakHashMap<>();
    private static final List<Web> WEBS = new ArrayList<>();

    private Snare() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Spider spider) || spider.level().isClientSide()) return;
        if (spider.tickCount % 5 != 0) return;
        if (!CrucibleManager.isActive(1)) return;
        if (!(spider.getTarget() instanceof ServerPlayer player) || !player.isAlive()) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (spider.distanceToSqr(player) > TRIGGER_DISTANCE * TRIGGER_DISTANCE) return;

        ServerLevel level = (ServerLevel) spider.level();
        long now = level.getGameTime();
        Long last = LAST_WEB.get(spider);
        if (last != null && now - last < COOLDOWN) return;

        BlockPos pos = player.blockPosition();
        if (!level.getBlockState(pos).isAir()) return;

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
