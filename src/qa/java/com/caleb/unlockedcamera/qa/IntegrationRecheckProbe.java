package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Fresh serialized QA driver after the historical generic scene used stale state. */
public final class IntegrationRecheckProbe {
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        WeaponModeRunner.start(mc, log, "standardRecheck");
    }
}
