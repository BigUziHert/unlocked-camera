package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
public final class PowergridFreelookSessionProbe {
    private PowergridFreelookSessionProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception { PowergridEndpointSession.run(mc, log, true); }
}
