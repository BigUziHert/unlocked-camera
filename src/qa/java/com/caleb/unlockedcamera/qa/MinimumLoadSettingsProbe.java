package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Runtime-only second-stage load reduction, layered over the saved 4/5 QA settings. */
public final class MinimumLoadSettingsProbe {
    private static boolean registered;
    private static volatile boolean running;
    private static volatile int phase, changedAtTick;
    private static volatile Snapshot snapshot;
    private static volatile Verification verification;
    private static CompletableFuture<?> pending;
    private static Consumer<String> log;
    private static Field simulationCache;
    private record Snapshot(MinecraftServer server, int clientRender, int clientSimulation,
                            int serverView, int serverSimulation, int simulationCache) {}
    private record Verification(int view, int simulation, int simulationCache, int elapsedTicks) {}
    @FunctionalInterface private interface ServerAction { void run() throws Exception; }
    private MinimumLoadSettingsProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        requireDisposable(mc);
        if (running || snapshot != null) throw new IllegalStateException("Minimum QA distances already active or pending; restore first");
        int render = mc.options.renderDistance().get(), simulation = mc.options.simulationDistance().get();
        if (simulation != 5) throw new IllegalStateException("Expected existing valid client simulation5; found " + simulation);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(MinimumLoadSettingsProbe::clientTick);
            NeoForge.EVENT_BUS.addListener(MinimumLoadSettingsProbe::serverTick);
            registered = true;
        }
        simulationCache = IntegratedServer.class.getDeclaredField("previousSimulationDistance"); simulationCache.setAccessible(true);
        log = output; running = true; phase = 0; verification = null;
        MinecraftServer server = mc.getSingleplayerServer();
        log.accept("MINIMUM_LOAD_SETTINGS START desiredClientRender=2 desiredServerView=2 desiredServerSimulation=2 clientSimulationUnchanged=" + simulation);
        // Snapshot actual server values before changing the client render option;
        // otherwise IntegratedServer could copy the new render value first.
        pending = serverTask(server, () -> {
            int cached = simulationCache.getInt(server);
            snapshot = new Snapshot(server, render, simulation, server.getPlayerList().getViewDistance(),
                    server.getPlayerList().getSimulationDistance(), cached);
            if (cached != simulation) throw new IllegalStateException("IntegratedServer simulation cache has not settled: " + cached + " vs " + simulation);
        });
    }

    public static void restore(Minecraft mc, Consumer<String> output) throws Exception {
        requireDisposable(mc);
        if (running) throw new IllegalStateException("Minimum load-setting operation is still pending");
        if (snapshot == null) { output.accept("MINIMUM_LOAD_SETTINGS RESTORED already_original"); return; }
        if (snapshot.server != mc.getSingleplayerServer()) throw new IllegalStateException("Saved QA server changed");
        if (mc.options.simulationDistance().get() != snapshot.clientSimulation)
            throw new IllegalStateException("Client simulation changed outside this temporary override");
        log = output; running = true; phase = 3; verification = null;
        mc.options.renderDistance().set(snapshot.clientRender); mc.options.broadcastOptions();
        log.accept("MINIMUM_LOAD_SETTINGS RESTORE_START clientRender=" + snapshot.clientRender
                + " serverView=" + snapshot.serverView + " serverSimulation=" + snapshot.serverSimulation
                + " then=QaLoadSettingsRestoreProbe");
        pending = serverTask(snapshot.server, () -> {
            snapshot.server.getPlayerList().setViewDistance(snapshot.serverView);
            snapshot.server.getPlayerList().setSimulationDistance(snapshot.serverSimulation);
            changedAtTick = snapshot.server.getTickCount();
        });
    }

    private static void clientTick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (pending != null) { if (!pending.isDone()) return; pending.join(); pending = null; }
            if (phase == 0) {
                if (mc.options.simulationDistance().get() != snapshot.clientSimulation)
                    throw new IllegalStateException("Client simulation changed while snapshotting");
                log.accept("MINIMUM_LOAD_SETTINGS SNAPSHOT clientRender=" + snapshot.clientRender + " clientSimulation=" + snapshot.clientSimulation
                        + " serverView=" + snapshot.serverView + " serverSimulation=" + snapshot.serverSimulation + " integratedSimulationCache=" + snapshot.simulationCache);
                mc.options.renderDistance().set(2); mc.options.broadcastOptions();
                phase = 1;
                pending = serverTask(snapshot.server, () -> {
                    if (simulationCache.getInt(snapshot.server) != snapshot.clientSimulation)
                        throw new IllegalStateException("IntegratedServer simulation cache changed before applying override");
                    snapshot.server.getPlayerList().setViewDistance(2);
                    snapshot.server.getPlayerList().setSimulationDistance(2);
                    changedAtTick = snapshot.server.getTickCount();
                });
                return;
            }
            if (phase == 1) { phase = 2; return; }
            if (phase == 3) { phase = 4; return; }
            if (verification == null) return;
            boolean restore = phase == 4;
            int render = restore ? snapshot.clientRender : 2;
            int view = restore ? snapshot.serverView : 2;
            int simulation = restore ? snapshot.serverSimulation : 2;
            boolean okay = mc.options.renderDistance().get() == render && mc.options.simulationDistance().get() == snapshot.clientSimulation
                    && verification.view == view && verification.simulation == simulation
                    && verification.simulationCache == snapshot.clientSimulation;
            log.accept("MINIMUM_LOAD_SETTINGS " + (okay ? restore ? "RESTORED" : "APPLIED" : restore ? "NOT_RESTORED" : "APPLY_ERROR")
                    + " clientRender=" + mc.options.renderDistance().get() + " clientSimulation=" + mc.options.simulationDistance().get()
                    + " serverView=" + verification.view + " serverSimulation=" + verification.simulation
                    + " integratedSimulationCache=" + verification.simulationCache + " persistedServerTicks=" + verification.elapsedTicks
                    + " scope=runtime_disposable_copy;no_options_save;queued_reads_and_current_DFU_work_may_continue");
            running = false; if (okay && restore) snapshot = null;
        } catch (Throwable error) {
            running = false;
            log.accept("MINIMUM_LOAD_SETTINGS " + (phase >= 3 ? "NOT_RESTORED" : "APPLY_ERROR") + " error=" + error);
        }
    }

    private static void serverTick(ServerTickEvent.Post event) {
        Snapshot saved = snapshot;
        if (!running || saved == null || event.getServer() != saved.server || (phase != 2 && phase != 4) || verification != null) return;
        int elapsed = event.getServer().getTickCount() - changedAtTick;
        if (elapsed < 3) return;
        try {
            // IntegratedServer reapplies simulation only if its client option differs
            // from previousSimulationDistance. Keeping both at5 lets server2 persist.
            // Three real ticks verify the override survives that integrated-server loop.
            verification = new Verification(event.getServer().getPlayerList().getViewDistance(),
                    event.getServer().getPlayerList().getSimulationDistance(), simulationCache.getInt(event.getServer()), elapsed);
        } catch (Exception error) { verification = new Verification(-1, -1, -1, elapsed); }
    }

    private static CompletableFuture<Void> serverTask(MinecraftServer server, ServerAction action) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            Throwable failure = null;
            try { action.run(); } catch (Throwable error) { failure = error; }
            finally { if (failure == null) done.complete(null); else done.completeExceptionally(failure); }
        });
        return done;
    }
    private static void requireDisposable(Minecraft mc) {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game")) && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Disposable QA copies only");
        if (mc.getSingleplayerServer() == null) throw new IllegalStateException("Integrated server required");
    }
}
