package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
public final class PowergridFreelookReadyProbe {
    private PowergridFreelookReadyProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception { PowergridEndpointReadyScenario.run(mc, log, true); }
}
