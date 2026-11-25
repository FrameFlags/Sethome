package com.frameflags.sethome;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(SethomeNeoForgeMod.MODID)
public final class SethomeNeoForgeMod {
    public static final String MODID = "sethome";

    public SethomeNeoForgeMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
    }

    private void onServerStarting(ServerStartingEvent event) {
        Sethome.init(FMLPaths.GAMEDIR.get());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SethomeCommands.register(event.getDispatcher());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        Sethome.onServerTick(event.getServer(), event.getServer().getTickCount());
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Sethome.cancel(event.getEntity().getUUID());
    }
}
