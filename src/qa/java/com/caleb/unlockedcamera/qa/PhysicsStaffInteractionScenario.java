package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Stable entry point; the session owns all mutable fixture state. */
public final class PhysicsStaffInteractionScenario {
    private PhysicsStaffInteractionScenario() {}
    public static void run(Minecraft mc, Consumer<String> log, boolean freelook) throws Exception {
        PhysicsStaffSession.run(mc, log, freelook);
    }
}
