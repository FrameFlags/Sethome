package com.frameflags.sethome_test;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(SethomeProbeNeoForgeMod.MODID)
public final class SethomeProbeNeoForgeMod {
    public static final String MODID = "sethome_test";

    public SethomeProbeNeoForgeMod(IEventBus modEventBus) {
        SethomeProbe.bootstrap("26.1.2-neoforge");
        NeoForge.EVENT_BUS.addListener(this::onJoin);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            net.minecraft.server.MinecraftServer server = player.level().getServer();
            if (server != null) SethomeProbe.onJoin(player, server.getTickCount());
        }
    }

    private void onTick(ServerTickEvent.Post event) {
        SethomeProbe.onTick(event.getServer(), event.getServer().getTickCount());
    }
}
