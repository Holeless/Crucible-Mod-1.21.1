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
 * Omniscience, Endermen: gets an Enderman to its target by whatever works.
 *
 * Runs only when the Enderman has a player target and is either far away or has no complete walking route to them.
 *
 *  1. Teleport: jumps to a free spot 3-8 blocks from the player (every 2 seconds, up to twice in a row).
 *  2. Walk: if it is far but a route exists and teleporting is not working, it just pathfinds.
 *  3. Dig: if there is no route and teleporting failed (no free space near the player), it tunnels straight toward
 *     them. It mines the blocks in front of it (3 high, so it fits) one at a time, holds the block it picked up,
 *     then sets it down somewhere beside or behind itself, out of the tunnel, and carries on. Blocks it cannot
 *     break (bedrock, hardness above {@link #MAX_HARDNESS}, containers) stop the dig. Needs mobGriefing.
 */
public class OmniscienceEndermanGoal extends Goal {
    private static final double FAR_SQR = 24.0 * 24.0;
    private static final int CHECK_INTERVAL = 20;
    private static final int TELEPORT_COOLDOWN = 40;
    private static final int MAX_TELEPORT_FAILS = 2;
    private static final int TELEPORT_ATTEMPTS = 8;
    private static final float MAX_HARDNESS = 10.0F;
    private static final int PLACE_DELAY = 8;
    private static final int PLACE_TRIES = 16;

    private final EnderMan mob;
    private boolean blocked;
    private int nextCheck;
    private int teleportCooldown;
    private int teleportFails;
    private int repath;

    private BlockPos breaking;
    private int breakTicks;
    private boolean digging;
    private int placeDelay;
    private Direction digDir = Direction.NORTH;

    public OmniscienceEndermanGoal(EnderMan mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Player targetPlayer() {
        return mob.getTarget() instanceof Player p && p.isAlive() && p.level() == mob.level() ? p : null;
    }

    /** Re-checks the walking route every second or so (teleporting resets the timer). */
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
        return blocked || mob.distanceToSqr(player) > FAR_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        if (!Omniscience.enabled() || !mob.isAlive() || mob.isPassenger()) return false;
        Player player = targetPlayer();
        if (player == null) return false;
        refresh(player);
        return digging || breaking != null || blocked || mob.distanceToSqr(player) > FAR_SQR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repath = 0;
        breakTicks = 0;
    }

    @Override
    public void stop() {
        clearCrack();
        mob.getNavigation().stop();
        if (digging) placeCarried(); // never leave it stuck holding a block
    }

    @Override
    public void tick() {
        Player player = targetPlayer();
        if (player == null) return;
        mob.getLookControl().setLookAt(player, 30.0F, 30.0F);

        if (teleportCooldown > 0) teleportCooldown--;
        if (mob.tickCount % 200 == 0) teleportFails = 0;

        if (digging) {
            if (--placeDelay <= 0) placeCarried();
            return;
        }

        boolean far = mob.distanceToSqr(player) > FAR_SQR;

        if ((far || blocked) && teleportCooldown <= 0 && teleportFails < MAX_TELEPORT_FAILS) {
            teleportCooldown = TELEPORT_COOLDOWN;
            if (teleportNear(player)) {
                teleportFails = 0;
                nextCheck = mob.tickCount; // look at the new situation right away
                return;
            }
            teleportFails++;
        }

        if (blocked) {
            if (teleportFails >= MAX_TELEPORT_FAILS) dig(player);
        } else {
            walkToward(player);
        }
    }

    private void walkToward(Player player) {
        if (--repath > 0) return;
        repath = 10;
        Path path = mob.getNavigation().createPath(player, 0);
        if (path != null) mob.getNavigation().moveTo(path, 1.0D);
    }

    private boolean teleportNear(Player player) {
        Level level = mob.level();
        double ox = mob.getX();
        double oy = mob.getY();
        double oz = mob.getZ();
        RandomSource random = mob.getRandom();

        for (int i = 0; i < TELEPORT_ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 3.0 + random.nextDouble() * 5.0;
            double x = player.getX() + Math.cos(angle) * radius;
            double z = player.getZ() + Math.sin(angle) * radius;
            double y = player.getY() + (i % 3); // randomTeleport slides down to the ground from here
            if (mob.randomTeleport(x, y, z, true)) {
                level.playSound(null, ox, oy, oz, SoundEvents.ENDERMAN_TELEPORT, mob.getSoundSource(), 1.0F, 1.0F);
                mob.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
                mob.getNavigation().stop();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Digging
    // ---------------------------------------------------------------------------------------------------------

    private void dig(Player player) {
        Level level = mob.level();
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return;

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
            clearCrack();
            teleportFails = 0; // cannot dig here, go back to trying to teleport
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

    /** Sets the carried block down beside or behind the Enderman. If there is no room, it drops as an item. */
    private void placeCarried() {
        Level level = mob.level();
        BlockState carried = mob.getCarriedBlock();
        if (carried != null) {
            BlockPos spot = findPlaceSpot(level);
            if (spot != null) {
                level.setBlock(spot, carried, 3);
                level.playSound(null, spot, carried.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
            } else {
                Block.popResource(level, mob.blockPosition(), new ItemStack(carried.getBlock()));
            }
        }
        mob.setCarriedBlock(null);
        digging = false;
    }

    private BlockPos findPlaceSpot(Level level) {
        BlockPos origin = mob.blockPosition();
        RandomSource random = mob.getRandom();
        for (int i = 0; i < PLACE_TRIES; i++) {
            int dx = random.nextInt(9) - 4;
            int dz = random.nextInt(9) - 4;
            int dy = random.nextInt(3) - 1;
            if (dx * digDir.getStepX() + dz * digDir.getStepZ() > 0) continue; // never ahead, that is the way forward

            BlockPos pos = origin.offset(dx, dy, dz);
            if (pos.equals(origin) || pos.equals(origin.above())) continue;
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
