package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class ManualOffsetRecorderStop {
    public static void run(Minecraft mc, Consumer<String> log) { ManualOffsetRecorder.stop(mc, log); }
}
