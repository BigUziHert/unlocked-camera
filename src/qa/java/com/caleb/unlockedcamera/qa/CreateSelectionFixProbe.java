package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Post-fix selector checks under a separate marker; original evidence stays intact. */
public final class CreateSelectionFixProbe {
    private CreateSelectionFixProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        CreateSelectionProbe.runFocused(mc, log);
    }
}
