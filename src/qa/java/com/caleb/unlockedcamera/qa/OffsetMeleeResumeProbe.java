package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Fresh QA core; reuses prior latest PASS evidence and retries every other registry item. */
public final class OffsetMeleeResumeProbe {
    private OffsetMeleeResumeProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        WeaponModeRunner.start(mc, log, "offsetMeleeResume");
    }
}
