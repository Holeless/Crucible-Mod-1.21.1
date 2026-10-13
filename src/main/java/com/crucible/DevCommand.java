package com.crucible;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DEV BUILD ONLY. /dev toggles cheats for the host of a singleplayer world by opping / deopping them.
 *
 * Worlds are created with cheats off, so this is the only way to get commands while testing. It refuses to
 * work on dedicated servers and for anyone but the singleplayer host. Everyone we opped is deopped again when
 * the server stops, so the op never leaks into another world. Delete this file for a release build.
 */
@EventBusSubscriber(modid = Crucible.MOD_ID)
public final class DevCommand {
    private static final Map<UUID, GameProfile> OPPED = new HashMap<>();

    private DevCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        // No permission requirement on purpose: it has to work while cheats are off.
        event.getDispatcher().register(Commands.literal("dev").executes(ctx -> toggle(ctx.getSource())));
    }

    private static int toggle(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("/dev must be run by a player."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        GameProfile profile = player.getGameProfile();
        if (!server.isSingleplayerOwner(profile)) {
            source.sendFailure(Component.literal("/dev only works for the host of a singleplayer world."));
            return 0;
        }

        PlayerList list = server.getPlayerList();
        if (OPPED.containsKey(player.getUUID())) {
            list.deop(profile);
            OPPED.remove(player.getUUID());
            source.sendSuccess(() -> Component.literal("Dev mode off: cheats locked."), false);
        } else {
            list.op(profile);
            OPPED.put(player.getUUID(), profile);
            source.sendSuccess(() -> Component.literal("Dev mode on: cheats unlocked."), false);
        }
        server.getCommands().sendCommands(player);
        return 1;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PlayerList list = event.getServer().getPlayerList();
        for (GameProfile profile : OPPED.values()) {
            list.deop(profile);
        }
        OPPED.clear();
    }
}
