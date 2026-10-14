package com.crucible.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Omniscience, Endermen: an Enderman that has no complete walking route to its target tunnels to them.
 * It never teleports to the player.
 *
 * Runs only when the Enderman has a player target and no complete route to them (they are in a tree, behind a wall,
 * underground, across a gap...). If a route exists, vanilla's own goals walk it there.
 *
 *  1. For the first {@link #DIG_DELAY} ticks it just walks as close as it can.
 *  2. Then it tunnels straight toward the player. It mines the blocks in front of it (3 high, so it fits) one at a
 *     time. The tunnel slopes up or down to follow the player's height.
 *  3. Each mined block is carried out of the tunnel: the Enderman teleports to the open area where the tunnel
 *     started, sets the block down there, and teleports straight back to the tunnel face. The tunnel stays clear, so
 *     other mobs can follow it all the way out. It only teleports like this while it can see the player
 *     ({@link #DUMP_NEEDS_SIGHT}); without sight the block is just dropped as an item, which also keeps the tunnel clear.
 *
 * Blocks it cannot break (bedrock, hardness above {@link #MAX_HARDNESS}, containers) stop the dig. Needs mobGriefing.
 */
public class OmniscienceEndermanGoal extends Goal {
    // Tuning knobs.
    private static final int DIG_DELAY = 300;               // ticks (15 s) of plain walking before it starts digging
    private static final boolean DUMP_NEEDS_SIGHT = true;   // false: it also teleports to dump blocks when it cannot see you
    private static final double DUMP_MIN = 3.0;             // dump spot is this far from the tunnel entrance, at least...
    private static final double DUMP_MAX = 8.0;             // ...and at most

    private static final int CHECK_INTERVAL = 20;
    private static final float MAX_HARDNESS = 10.0F;
    private static final int PLACE_DELAY = 8;
    private static final int PLACE_TRIES = 16;
    private static final int TELEPORT_ATTEMPTS = 8;

    private final EnderMan mob;
    private boolean blocked;
    private int nextCheck;
    private int huntTicks;
    private int repath;

    private BlockPos breaking;
    private int breakTicks;
    private boolean digging;        // true from picking a block up until it has been dumped
    private int placeDelay;
    private Direction digDir = Direction.NORTH;
    private Vec3 tunnelOrigin;      // where this tunnel started: the open area blocks are dumped in

    public OmniscienceEndermanGoal(EnderMan mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Player targetPlayer() {
        return mob.getTarget() instanceof Player p && p.isAlive() && p.level() == mob.level() ? p : null;
    }

    /** Re-checks the walking route every second or so. */
    private void refresh(Player player) {
        if (mob.tickCount < nextCheck) return;
        nextCheck = mob.tickCount + CHECK_INTERVAL;
        if (!mob.onGround()) {
            blocked = false;
            return;
        }
        Path path = mob.getNavigation().createPath(player, 0);
        blocked = path == null || !path.canReach();
    }

    @Override
    public boolean canUse() {
        if (!Omniscience.enabled() || !mob.isAlive() || mob.isPassenger()) return false;
        Player player = targetPlayer();
        if (player == null) return false;
        refresh(player);
        return blocked;
    }

    @Override
    public boolean canContinueToUse() {
        if (!Omniscience.enabled() || !mob.isAlive() || mob.isPassenger()) return false;
        Player player = targetPlayer();
        if (player == null) return false;
        refresh(player);
        return digging || breaking != null || blocked;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repath = 0;
        breakTicks = 0;
        huntTicks = 0;
        tunnelOrigin = null;
    }

    @Override
    public void stop() {
        clearCrack();
        mob.getNavigation().stop();
        if (digging) dumpCarried(null); // never leave it stuck holding a block
    }

    @Override
    public void tick() {
        Player player = targetPlayer();
        if (player == null) return;
        mob.getLookControl().setLookAt(player, 30.0F, 30.0F);

        if (digging) {
            if (--placeDelay <= 0) dumpCarried(player);
            return;
        }

        if (++huntTicks < DIG_DELAY) {
            walkToward(player); // a partial path leads to the reachable spot nearest the player
            return;
        }

        if (blocked) dig(player);
        else walkToward(player);
    }

    private void walkToward(Player player) {
        if (--repath > 0) return;
        repath = 10;
        Path path = mob.getNavigation().createPath(player, 0);
        if (path != null) mob.getNavigation().moveTo(path, 1.0D);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Digging
    // ---------------------------------------------------------------------------------------------------------

    private void dig(Player player) {
        Level level = mob.level();
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return;

        if (tunnelOrigin == null) tunnelOrigin = mob.position();

        // Keep the heading while a block is half mined so it does not flip between axes.
        if (breaking == null) {
            double dx = player.getX() - mob.getX();
            double dz = player.getZ() - mob.getZ();
            digDir = Math.abs(dx) > Math.abs(dz)
                    ? (dx > 0 ? Direction.EAST : Direction.WEST)
                    : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        // Tunnel slopes one block per step toward the player's height.
        double dy = player.getY() - mob.getY();
        int slope = dy >= 2.0 ? 1 : dy <= -2.0 ? -1 : 0;

        BlockPos front = mob.blockPosition().relative(digDir);
        BlockPos obstacle = null;
        for (int k = 0; k < 3 && obstacle == null; k++) { // the Enderman is nearly 3 blocks tall
            BlockPos pos = front.above(slope + k);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) obstacle = pos;
        }

        if (obstacle == null) {
            clearCrack();
            mob.getMoveControl().setWantedPosition(front.getX() + 0.5, front.getY() + slope, front.getZ() + 0.5, 1.0D);
            return;
        }

        BlockState state = level.getBlockState(obstacle);
        if (!mineable(level, obstacle, state)) {
            clearCrack(); // cannot dig through this
            return;
        }

        if (!obstacle.equals(breaking)) {
            clearCrack();
            breaking = obstacle;
            breakTicks = 0;
        }
        breakTicks++;
        mob.getNavigation().stop();
        Vec3 center = Vec3.atCenterOf(obstacle);
        mob.getLookControl().setLookAt(center.x, center.y, center.z);
        if (breakTicks % 6 == 1) mob.swing(InteractionHand.MAIN_HAND);

        int need = Math.max(6, Math.min(40, Math.round(state.getDestroySpeed(level, obstacle) * 5.0F)));
        level.destroyBlockProgress(mob.getId(), obstacle, Math.min(9, breakTicks * 10 / need));
        if (breakTicks >= need) pickUp(level, obstacle, state);
    }

    private void pickUp(Level level, BlockPos pos, BlockState state) {
        level.destroyBlockProgress(mob.getId(), pos, -1);
        level.levelEvent(2001, pos, Block.getId(state));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        mob.setCarriedBlock(state);
        digging = true;
        placeDelay = PLACE_DELAY;
        breaking = null;
        breakTicks = 0;
    }

    /**
     * Gets the carried block out of the tunnel. With sight of the player it teleports to the tunnel entrance, sets the
     * block down, and teleports back to where it was. Otherwise the block drops as an item. Either way the tunnel
     * stays clear. {@code player} is null when the goal is being shut down.
     */
    private void dumpCarried(Player player) {
        Level level = mob.level();
        BlockState carried = mob.getCarriedBlock();
        digging = false;
        if (carried == null) return;

        boolean mayTeleport = player != null
                && tunnelOrigin != null
                && (!DUMP_NEEDS_SIGHT || mob.hasLineOfSight(player));
        Vec3 back = mob.position();

        if (mayTeleport && teleportAround(tunnelOrigin, DUMP_MIN, DUMP_MAX)) {
            BlockPos spot = findPlaceSpot(level);
            if (spot != null) {
                level.setBlock(spot, carried, 3);
                level.playSound(null, spot, carried.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
            } else {
                Block.popResource(level, mob.blockPosition(), new ItemStack(carried.getBlock()));
            }
            mob.setCarriedBlock(null);
            if (!teleportTo(back)) tunnelOrigin = null; // could not get back: any new tunnel starts from here
            return;
        }

        Block.popResource(level, mob.blockPosition(), new ItemStack(carried.getBlock()));
        mob.setCarriedBlock(null);
    }

    /** Teleports to a free standing spot {@code min}-{@code max} blocks from {@code center}. */
    private boolean teleportAround(Vec3 center, double min, double max) {
        RandomSource random = mob.getRandom();
        for (int i = 0; i < TELEPORT_ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = min + random.nextDouble() * (max - min);
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            double y = center.y + (i % 3); // randomTeleport slides down to the ground from here
            if (teleportTo(new Vec3(x, y, z))) return true;
        }
        return false;
    }

    private boolean teleportTo(Vec3 pos) {
        Level level = mob.level();
        double ox = mob.getX();
        double oy = mob.getY();
        double oz = mob.getZ();
        if (!mob.randomTeleport(pos.x, pos.y, pos.z, true)) return false;
        level.playSound(null, ox, oy, oz, SoundEvents.ENDERMAN_TELEPORT, mob.getSoundSource(), 1.0F, 1.0F);
        mob.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        mob.getNavigation().stop();
        return true;
    }

    /** A free spot next to the Enderman's current position to set a block down, away from the tunnel mouth. */
    private BlockPos findPlaceSpot(Level level) {
        BlockPos origin = mob.blockPosition();
        BlockPos mouth = tunnelOrigin == null ? null : BlockPos.containing(tunnelOrigin);
        RandomSource random = mob.getRandom();
        for (int i = 0; i < PLACE_TRIES; i++) {
            BlockPos pos = origin.offset(random.nextInt(9) - 4, random.nextInt(3) - 1, random.nextInt(9) - 4);
            if (pos.equals(origin) || pos.equals(origin.above())) continue;
            if (mouth != null && pos.distSqr(mouth) < 9.0) continue; // keep the entrance open
            if (!level.getBlockState(pos).isAir()) continue;
            BlockPos below = pos.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty()) continue;
            return pos;
        }
        return null;
    }

    private void clearCrack() {
        if (breaking != null) mob.level().destroyBlockProgress(mob.getId(), breaking, -1);
        breaking = null;
        breakTicks = 0;
    }

    private static boolean mineable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0.0F && hardness <= MAX_HARDNESS && !(state.getBlock() instanceof EntityBlock);
    }
}
