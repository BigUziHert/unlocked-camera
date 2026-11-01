package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Fresh dynamic entry for clients that already loaded the old scenario. */
public final class PhysicsStaffFreelookSessionProbe {
    private PhysicsStaffFreelookSessionProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        PhysicsStaffSession.run(mc, log, true);
    }
}
