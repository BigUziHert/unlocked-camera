package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
/** All registered module operations in three modes after compatibility changes. */
public final class AeroworksModuleRegressionProbe {
    private AeroworksModuleRegressionProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        AeroworksModuleProbe.runRegression(mc, log);
    }
}
