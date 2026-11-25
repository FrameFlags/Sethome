package com.frameflags.sethome_test;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(SethomeProbeForgeMod.MODID)
public final class SethomeProbeForgeMod {
    public static final String MODID = "sethome_test";

    public SethomeProbeForgeMod() {
        SethomeProbe.bootstrap("1.20.1-forge");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SethomeProbe.onJoin(player, player.level().getServer().getTickCount());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        SethomeProbe.onTick(event.getServer(), event.getServer().getTickCount());
    }
}
