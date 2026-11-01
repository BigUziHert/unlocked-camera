package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class BucketRayPolicyRecheckProbe {
    private BucketRayPolicyRecheckProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Class<?> type = BucketQaModeRunner.fresh("com.caleb.unlockedcamera.qa.BucketRayPolicyProbe");
        Consumer<String> output = line -> log.accept(line.startsWith("BUCKET_RAY_POLICY ")
                ? "BUCKET_RAY_POLICY_RECHECK " + line.substring("BUCKET_RAY_POLICY ".length()) : line);
        type.getMethod("run", Minecraft.class, Consumer.class).invoke(null, mc, output);
    }
}
