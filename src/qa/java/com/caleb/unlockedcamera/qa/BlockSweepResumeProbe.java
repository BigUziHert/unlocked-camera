package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Resumes the unchanged broad probe while releasing hold interactions between cases. */
public final class BlockSweepResumeProbe {
    private static final Vec3 PLAYER = new Vec3(1742.5, 150, 190.5);
    private static boolean registered, active, recover;
    private static int recoveries;
    private static Consumer<String> log;
    private static Field phase, task, pendingTeleport;
    private static Class<?> holdManager;
    private static CompletableFuture<Void> gate, recoveryTask;
    private static CompletableFuture<Boolean> ready;
    private BlockSweepResumeProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (active || BlockInteractionProbe.isRunning()) throw new IllegalStateException("Broad block probe already running");
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Restricted to disposable QA instances");
        if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("QA world unavailable");
        log = output;
        // The current review controller is already loaded. Preserve its completed
        // pairs under the known fixture contract before a later review is reloaded.
        Field loadedField = BlockInteractionReviewProbe.class.getDeclaredField("loaded"); loadedField.setAccessible(true);
        Class<?> review = (Class<?>) loadedField.get(null);
        if (review != null) {
            Field running = review.getDeclaredField("running"); running.setAccessible(true);
            if (running.getBoolean(null)) throw new IllegalStateException("Wait for the review RESTORED line");
            review.getDeclaredMethod("releaseHold");
            if (Files.exists(Path.of("qa", "block-interaction-recheck.tsv"))) {
                Files.writeString(Path.of("qa", "block-interaction-recheck-fixture-version.txt"), fixtureIdentity());
                log.accept("BLOCK_RECHECK PROFILE_ADOPT version=" + BlockInteractionRecheck.FIXTURE_VERSION
                        + " existing loaded hold-release controller; completed pairs only are reusable");
            }
        }
        phase = broadField("phase"); task = broadField("serverTask");
        pendingTeleport = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
        pendingTeleport.setAccessible(true);
        holdManager = Class.forName("dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager");
        if ((boolean) holdManager.getMethod("isActive").invoke(null)) holdManager.getMethod("stop").invoke(null);
        mc.player.setDeltaMovement(Vec3.ZERO);
        if (!registered) {
            // Run before the ordinary QA Post listener examines its preparation future.
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, BlockSweepResumeProbe::tick);
            registered = true;
        }
        recover = false; gate = recoveryTask = null; ready = null; recoveries = 0;
        BlockInteractionProbe.start(mc, log, "all"); active = true;
        log.accept("BLOCK_SWEEP_RESUME START unchangedBroadClass=true holdCleanup=true teleportReadyGate=true");
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!BlockInteractionProbe.isRunning()) {
                active = false; log.accept("BLOCK_SWEEP_RESUME STOP recoveries=" + recoveries); return;
            }
            int current = phase.getInt(null);
            if ((current == 1 || current == 2) && (boolean) holdManager.getMethod("isActive").invoke(null)) {
                holdManager.getMethod("stop").invoke(null);
                mc.player.setDeltaMovement(Vec3.ZERO); mc.player.resetFallDistance();
                recover = true;
                log.accept("BLOCK_SWEEP_RESUME RELEASE phase=" + current + " client=" + mc.player.position());
            }
            if (recover && current == 2 && gate == null) {
                @SuppressWarnings("unchecked") CompletableFuture<Void> original = (CompletableFuture<Void>) task.get(null);
                gate = new CompletableFuture<>(); task.set(null, gate);
                var server = mc.getSingleplayerServer(); var playerId = mc.player.getUUID();
                recoveryTask = original.thenRunAsync(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                    p.stopRiding(); p.setDeltaMovement(Vec3.ZERO); p.resetFallDistance();
                    p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
                }, server);
                recoveries++;
            }
            if (gate != null) {
                if (!recoveryTask.isDone()) return;
                recoveryTask.join();
                if (ready == null) {
                    var server = mc.getSingleplayerServer(); var playerId = mc.player.getUUID();
                    ready = CompletableFuture.supplyAsync(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                        try { return pendingTeleport.get(p.connection) == null && p.position().distanceToSqr(PLAYER) < .01; }
                        catch (IllegalAccessException error) { throw new RuntimeException(error); }
                    }, server);
                    return;
                }
                if (!ready.isDone()) return;
                if (!ready.join()) { ready = null; return; }
                // Existing broad tick may continue only after the ordered teleport
                // confirmation has actually reached the server.
                gate.complete(null); gate = recoveryTask = null; ready = null; recover = false;
                log.accept("BLOCK_SWEEP_RESUME READY recoveries=" + recoveries);
            }
        } catch (Throwable error) {
            log.accept("BLOCK_SWEEP_RESUME ERROR " + error);
            if (gate != null) gate.completeExceptionally(error);
            active = false;
        }
    }

    private static Field broadField(String name) throws Exception {
        Field field = BlockInteractionProbe.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    /** Fixture contract plus actual camera bytes and loaded mod versions. */
    public static String fixtureIdentity() throws Exception {
        return BlockReviewIdentity.fingerprint();
    }
}
