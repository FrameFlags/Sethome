package com.frameflags.sethome_test;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

/** Test-only mod: drives the real product through the server command dispatcher. */
@Mod("sethome_test")
public final class SethomeTestMod {
    private MinecraftServer server;
    private HomeProbe probe;
    private boolean productChecked;

    public SethomeTestMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        try {
            if (!productChecked) {
                productChecked = true;
                try {
                    // Kept reflective in THIS class so the probe mod still loads when
                    // the product jar is absent (negative control).
                    Class.forName("com.frameflags.sethome.Sethome");
                } catch (Throwable missing) {
                    ProbeResult.write(server,
                            "SETHOME_TEST_RESULT: FAIL reason=no-product detail=missing_product_jar "
                                    + "product=false limit_rejected set=fail teleport=fail list=fail "
                                    + "delete=fail bare=fail reserved=fail limit=fail countdown=skipped no-spam=fail",
                            "product=false limit_rejected");
                    return;
                }
                probe = new HomeProbe(server);
            }
            if (probe != null && !probe.isDone()) probe.onTick();
        } catch (Throwable error) {
            if (probe == null) {
                ProbeResult.write(server,
                        "SETHOME_TEST_RESULT: FAIL reason=probe-init detail=" + shortDetail(error)
                                + " product=true limit_rejected set=fail teleport=fail list=fail "
                                + "delete=fail bare=fail reserved=fail limit=fail countdown=skipped no-spam=fail",
                        "product=true limit_rejected");
            } else {
                probe.fail("exception", shortDetail(error));
            }
        }
    }

    private static String shortDetail(Throwable error) {
        String text = String.valueOf(error.getMessage());
        if (text == null || text.isEmpty()) text = error.getClass().getSimpleName();
        return text.replaceAll("\\s+", "_").replace(':', '_');
    }
}
