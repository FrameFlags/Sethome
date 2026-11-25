package com.frameflags.sethome;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class SethomeFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Sethome.init(FabricLoader.getInstance().getGameDir());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SethomeCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> Sethome.init(FabricLoader.getInstance().getGameDir()));
        ServerTickEvents.END_SERVER_TICK.register(server -> Sethome.onServerTick(server, server.getTickCount()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                Sethome.cancel(handler.getPlayer().getUUID()));
    }
}
