package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Optional QA compile gate; it never claims a case was run while waiting. */
public final class OffsetMeleeProbe {
    private static boolean registered, waiting;
    private static Consumer<String> output;
    private static Path hold;
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        hold = mc.gameDirectory.toPath().resolve("qa/melee-offset-hold.txt");
        if (Files.exists(hold)) {
            if (waiting) throw new IllegalStateException("Offset melee is already waiting");
            waiting = true; output = log;
            if (!registered) { NeoForge.EVENT_BUS.addListener(OffsetMeleeProbe::tick); registered = true; }
            log.accept("MELEE_OFFSET_MODE HOLD waiting_for_fixture_compile;no_action_sent"); return;
        }
        WeaponModeRunner.start(mc, log, true);
    }
    private static void tick(ClientTickEvent.Post event) {
        if (!waiting || Files.exists(hold)) return;
        waiting = false;
        try { WeaponModeRunner.start(Minecraft.getInstance(), output, true); }
        catch (Throwable error) { output.accept("MELEE_OFFSET_SUITE ABORT startup " + error); }
    }
}
