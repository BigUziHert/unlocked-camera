package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
/** Corrected production's exact-outline hold, separate from original audit rows. */
public final class PowergridRegressionProbe {
    private PowergridRegressionProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        PowergridEndpointReadyScenario.runRegression(mc, log, false);
    }
}
