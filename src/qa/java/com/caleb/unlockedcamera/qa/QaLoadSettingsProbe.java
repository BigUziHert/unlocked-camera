package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Runtime-only chunk-load reduction for the two disposable compatibility clients. */
public final class QaLoadSettingsProbe {
    private static Integer render, simulation;
    private QaLoadSettingsProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Disposable QA copies only");
        if (mc.getSingleplayerServer() == null) throw new IllegalStateException("Integrated server required");
        if (render != null) throw new IllegalStateException("QA distances already set; restore first");
        render = mc.options.renderDistance().get(); simulation = mc.options.simulationDistance().get();
        mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5);
        mc.options.broadcastOptions();
        log.accept("QA_LOAD_SETTINGS APPLIED render=" + render + "->" + mc.options.renderDistance().get()
                + " simulation=" + simulation + "->" + mc.options.simulationDistance().get()
                + " scope=runtime_disposable_copy;fixtures_within_21blocks;queued_chunk_loads_may_continue");
    }
    public static void restore(Minecraft mc, Consumer<String> log) {
        if (render == null) { log.accept("QA_LOAD_SETTINGS RESTORED already_original"); return; }
        mc.options.renderDistance().set(render); mc.options.simulationDistance().set(simulation);
        mc.options.broadcastOptions();
        log.accept("QA_LOAD_SETTINGS RESTORED render=" + render + " simulation=" + simulation);
        render = simulation = null;
    }
}
