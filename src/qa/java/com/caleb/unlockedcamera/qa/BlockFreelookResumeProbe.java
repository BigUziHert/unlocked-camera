package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Resume the unchanged broad controller after delayed GUI packets interrupt a
 * pre-use pose. Completed pairs keep their original build identity. This guard
 * only closes screens/restarts the real input preflight before a request; it
 * neither fabricates the freelook gate nor changes completed observations.
 */
public final class BlockFreelookResumeProbe {
    private static boolean registered, watching;
    private static Consumer<String> log;
    private static Field running, phase, index, mode, before, bodyYaw, bodyPitch, cameraPitch;
    private static Field freeYaw, freePitch, keyField;
    private static Method beginPose, finish, held;
    private static int lastIndex = -1, lastMode = -1, attempts, recoveries;

    private BlockFreelookResumeProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Resume guard is restricted to disposable QA copies");
        running = field(BlockFreelookProbe.class, "running");
        if (running.getBoolean(null)) throw new IllegalStateException("Freelook block sweep is already active");
        phase = field(BlockFreelookProbe.class, "phase");
        index = field(BlockFreelookProbe.class, "index");
        mode = field(BlockFreelookProbe.class, "mode");
        before = field(BlockFreelookProbe.class, "before");
        bodyYaw = field(BlockFreelookProbe.class, "poseBodyYaw");
        bodyPitch = field(BlockFreelookProbe.class, "poseBodyPitch");
        cameraPitch = field(BlockFreelookProbe.class, "poseCameraPitch");
        freeYaw = field(UnlockedCameraClient.class, "freelookYaw");
        freePitch = field(UnlockedCameraClient.class, "freelookPitch");
        keyField = field(UnlockedCameraClient.class, "FREELOOK_KEY");
        beginPose = BlockFreelookProbe.class.getDeclaredMethod("beginPose", Minecraft.class, before.getType());
        beginPose.setAccessible(true);
        finish = BlockFreelookProbe.class.getDeclaredMethod("finish", Minecraft.class, String.class);
        finish.setAccessible(true);
        held = UnlockedCameraClient.class.getDeclaredMethod("isFreelookHeld", Minecraft.class);
        held.setAccessible(true);
        log = output; lastIndex = lastMode = -1; attempts = recoveries = 0;
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, BlockFreelookResumeProbe::tick);
            registered = true;
        }
        BlockFreelookProbe.run(mc, output);
        watching = true;
        log.accept("BLOCK_FREELOOK_UI_FIX READY policy=close-delayed-screen-and-restart-real-preflight3 identity=unchanged-broad-controller maxRecoveriesPerMode=8");
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!watching) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!running.getBoolean(null)) {
                watching = false;
                log.accept("BLOCK_FREELOOK_UI_FIX DONE recoveries=" + recoveries);
                return;
            }
            int currentPhase = phase.getInt(null), currentIndex = index.getInt(null), currentMode = mode.getInt(null);
            if (currentIndex != lastIndex || currentMode != lastMode) {
                lastIndex = currentIndex; lastMode = currentMode; attempts = 0;
            }
            if (currentPhase != 1 && currentPhase != 2 && currentPhase != 4 && currentPhase != 5) return;
            if (mc.player == null) return; // The broad controller owns disconnect handling.
            boolean poseStarted = currentPhase == 4 || currentPhase == 5;
            KeyMapping key = (KeyMapping) keyField.get(null);
            boolean gate = (boolean) held.invoke(null, mc);
            boolean bodyChanged = poseStarted && (Math.abs(mc.player.getYRot() - bodyYaw.getFloat(null)) > .01f
                    || Math.abs(mc.player.getXRot() - bodyPitch.getFloat(null)) > .01f);
            boolean invalidFree = poseStarted && currentMode == 1 && (!gate || bodyChanged
                    || Math.abs(freeYaw.getFloat(null) + 90f) > .01f
                    || Math.abs(freePitch.getFloat(null) - cameraPitch.getFloat(null)) > .01f);
            boolean wrongView = poseStarted && mc.options.getCameraType() != CameraType.FIRST_PERSON;
            if (mc.screen == null && !invalidFree && !bodyChanged && !wrongView) return;
            String reason = "index=" + currentIndex + " mode=" + currentMode + " phase=" + currentPhase
                    + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                    + " key=" + key.isDown() + " actualGate=" + gate + " camera=" + mc.options.getCameraType()
                    + " body=" + mc.player.getYRot() + "," + mc.player.getXRot()
                    + " deflection=" + freeYaw.getFloat(null) + "," + freePitch.getFloat(null)
                    + " expectedBody=" + bodyYaw.getFloat(null) + "," + bodyPitch.getFloat(null);
            if (++attempts > 8) {
                log.accept("BLOCK_FREELOOK_UI_FIX ABORT repeated_input_interruption " + reason);
                finish.invoke(null, mc, "ABORT"); watching = false;
                return;
            }
            log.accept("BLOCK_FREELOOK_UI_FIX RECOVER attempt=" + attempts + " " + reason);
            if (mc.screen != null) {
                mc.player.closeContainer();
                mc.setScreen(null);
            }
            if (poseStarted) {
                Object initial = before.get(null);
                if (initial == null) throw new IllegalStateException("Missing pre-use observation during UI recovery");
                // Calls the same public production mouse-input hook and gate
                // checks, then waits three new ticks and a fresh readiness ACK.
                beginPose.invoke(null, mc, initial);
            }
            recoveries++;
        } catch (Throwable failure) {
            log.accept("BLOCK_FREELOOK_UI_FIX ERROR " + failure);
            try { if (running.getBoolean(null)) finish.invoke(null, mc, "ABORT"); }
            catch (Throwable restore) { log.accept("BLOCK_FREELOOK_UI_FIX RESTORE_ERROR " + restore); }
            watching = false;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
