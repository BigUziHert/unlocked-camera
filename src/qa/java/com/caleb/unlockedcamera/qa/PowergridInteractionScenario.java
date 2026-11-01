package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Stable entry point; the endpoint fixture owns the implementation and listener. */
public final class PowergridInteractionScenario {
    private PowergridInteractionScenario() {}
    public static void run(Minecraft mc, Consumer<String> output, boolean useFreelook) throws Exception {
        PowergridEndpointScenario.run(mc, output, useFreelook);
    }
}
