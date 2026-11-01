package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Real first-person freelook counterpart of the fresh staff timing recheck. */
public final class PhysicsStaffFreelookRecheckProbe {
    private PhysicsStaffFreelookRecheckProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        PhysicsStaffRecheckProbe.runFreelook(mc, log);
    }
}
