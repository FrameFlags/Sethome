package com.frameflags.sethome_test;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/** Test-only server-side mod that drives the in-game Sethome probe. */
@Mod(modid = SethomeProbeMod.MODID, name = SethomeProbeMod.NAME, version = SethomeProbeMod.VERSION,
        acceptableRemoteVersions = "*")
public final class SethomeProbeMod {
    public static final String MODID = "sethome_test";
    public static final String NAME = "Sethome in-game probe (test only)";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new SethomeProbe());
        SethomeProbe.bootstrap(event.getModConfigurationDirectory().getParentFile().toPath());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        SethomeProbe.serverStarting(server);
    }
}
