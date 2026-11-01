package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
/** Undo the2/2server override before the original QA8/8 restoration. */
public final class MinimumLoadSettingsRestoreProbe {
    private MinimumLoadSettingsRestoreProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception { MinimumLoadSettingsProbe.restore(mc, log); }
}
