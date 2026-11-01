package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class BucketOpenWaterProbe {
    private BucketOpenWaterProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        BucketQaModeRunner.run(mc, log, "runOpenWaterSmoke", "BUCKET_OPEN_WATER");
    }
}
