package com.frameflags.sethome_test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class SethomeProbeFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        SethomeProbe.bootstrap("1.21.1-fabric");
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SethomeProbe.onJoin(handler.getPlayer(), server.getTickCount()));
        ServerTickEvents.END_SERVER_TICK.register(server ->
                SethomeProbe.onTick(server, server.getTickCount()));
    }
}
