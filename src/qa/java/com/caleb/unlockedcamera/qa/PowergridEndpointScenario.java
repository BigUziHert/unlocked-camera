package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
public final class PowergridEndpointScenario {
    private PowergridEndpointScenario() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception { PowergridEndpointSession.run(mc, log, false); }
    public static void run(Minecraft mc, Consumer<String> log, boolean free) throws Exception { PowergridEndpointSession.run(mc, log, free); }
}
