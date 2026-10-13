package com.crucible;

import com.crucible.effects.Vicious;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Crucible.MOD_ID)
public class Crucible {
    public static final String MOD_ID = "crucible";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Crucible(IEventBus modEventBus, ModContainer modContainer) {
        // Mod-bus event: gives passive mobs the attack attributes they normally lack.
        modEventBus.addListener(Vicious::onAttributes);
        LOGGER.info("Crucible loaded");
    }
}
