package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class PhysicsStaffScenario {
    private PhysicsStaffScenario() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        PhysicsStaffInteractionScenario.run(mc, log, false);
    }
}
