package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
/** Stable source entry; older running clients retain their original loaded implementation. */
public final class PowergridEndpointSession {
    private PowergridEndpointSession() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception { PowergridEndpointReadyScenario.run(mc, log, false); }
    public static void run(Minecraft mc, Consumer<String> log, boolean free) throws Exception { PowergridEndpointReadyScenario.run(mc, log, free); }
}
