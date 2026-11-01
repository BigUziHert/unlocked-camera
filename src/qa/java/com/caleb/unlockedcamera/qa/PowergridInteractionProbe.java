package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class PowergridInteractionProbe {
    private PowergridInteractionProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        PowergridInteractionScenario.run(mc, log, false);
    }
}
