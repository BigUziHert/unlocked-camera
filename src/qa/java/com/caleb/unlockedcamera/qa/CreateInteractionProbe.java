package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class CreateInteractionProbe {
    private CreateInteractionProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        CreateInteractionScenario.run(mc, log, false);
    }
}
