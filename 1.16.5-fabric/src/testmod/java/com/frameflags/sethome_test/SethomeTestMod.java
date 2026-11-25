package com.frameflags.sethome_test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Test-only entrypoint. Never touches product classes directly at runtime. */
public final class SethomeTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // The probe observes the joined player from the tick loop; this avoids
        // relying on a net-handler accessor that yarn 1.16.5 does not map.
        ServerTickEvents.END_SERVER_TICK.register(server ->
                SethomeProbe.onTick(server, server.getTicks()));
    }
}
