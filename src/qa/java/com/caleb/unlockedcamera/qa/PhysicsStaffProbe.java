package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Stable entry point for the real staff interaction scenario. */
public final class PhysicsStaffProbe {
    private PhysicsStaffProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        PhysicsStaffScenario.run(mc, log);
    }
}
