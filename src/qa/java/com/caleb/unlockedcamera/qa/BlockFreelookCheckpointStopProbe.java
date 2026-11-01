package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Requests an intentional stop only at the unchanged sweep's drained, pre-use boundary. */
public final class BlockFreelookCheckpointStopProbe {
    private static boolean registered, waiting;
    private static Field running, phase, task, index, mode;
    private static Method finish, id;
    private static Consumer<String> log;
    private static long lastWait;
    private BlockFreelookCheckpointStopProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Checkpoint stop requires a disposable QA copy");
        running = field("running"); phase = field("phase"); task = field("task"); index = field("index"); mode = field("mode");
        if (waiting || !running.getBoolean(null)) throw new IllegalStateException("No active broad sweep, or stop already pending");
        finish = BlockFreelookProbe.class.getDeclaredMethod("finish", Minecraft.class, String.class); finish.setAccessible(true);
        id = BlockFreelookProbe.class.getDeclaredMethod("id"); id.setAccessible(true);
        log = output; waiting = true; lastWait = 0;
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, BlockFreelookCheckpointStopProbe::tick);
            registered = true;
        }
        log.accept("BLOCK_FREELOOK_CHECKPOINT_STOP START policy=phase1_actual_setup_future_done;no_use_ACK_pending;intentional_checkpoint;completed_rows_unchanged;recovery_verification_required");
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!waiting) return;
        try {
            if (!running.getBoolean(null)) {
                waiting = false; log.accept("BLOCK_FREELOOK_CHECKPOINT_STOP NOT_STOPPED broad_ended_before_checkpoint"); return;
            }
            int currentPhase = phase.getInt(null);
            CompletableFuture<?> pending = (CompletableFuture<?>) task.get(null);
            if (currentPhase != 1 || pending == null || !pending.isDone() || pending.isCancelled()) {
                long now = System.nanoTime();
                if (lastWait == 0 || now - lastWait > 15_000_000_000L) {
                    lastWait = now;
                    log.accept("BLOCK_FREELOOK_CHECKPOINT_STOP WAIT phase=" + currentPhase + " index=" + index.getInt(null)
                            + " mode=" + mode.getInt(null) + " futureDone=" + (pending != null && pending.isDone())
                            + " futureCanceled=" + (pending != null && pending.isCancelled()));
                }
                return;
            }
            // Completion, including exceptional completion, proves this
            // uncanceled setup callback returned. It is safe to clean up;
            // no request for this mode has been issued in phase one.
            log.accept("BLOCK_FREELOOK_CHECKPOINT_STOP STOPPED block=" + id.invoke(null) + " index=" + index.getInt(null)
                    + " mode=" + mode.getInt(null) + " setupFutureDone=true setupFutureExceptional=" + pending.isCompletedExceptionally()
                    + " intentional=true incomplete_pair_will_retry=true no_outcome_reclassification=true");
            waiting = false;
            finish.invoke(null, Minecraft.getInstance(), "ABORT");
        } catch (Throwable error) {
            waiting = false; log.accept("BLOCK_FREELOOK_CHECKPOINT_STOP ERROR " + error);
        }
    }

    private static Field field(String name) throws Exception {
        Field result = BlockFreelookProbe.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
