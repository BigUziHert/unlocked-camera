package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

public final class QaLoadSettingsRestoreProbe {
    private QaLoadSettingsRestoreProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) { QaLoadSettingsProbe.restore(mc, log); }
}
