package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class BucketHandSmokeProbe {
    private BucketHandSmokeProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        BucketQaModeRunner.run(mc, log, "runHandSmoke", "BUCKET_HAND_SMOKE");
    }
}
