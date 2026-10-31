package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Actual mixed-in Camera#setup regression probe; only included by -PcameraQa. */
public final class ShoulderRuntimeProbe {
    private static final String[] STATE = {
            "active", "targetDistance", "smoothedDistance", "lastFrameNanos", "collisionCap", "lastCapNanos",
            "shoulderSide", "shoulderOffset", "shoulderClearance", "lastClearanceSign", "lastShoulderNanos",
            "freelookYaw", "freelookPitch", "lastFreelookNanos"
    };

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (String name : STATE) {
            Field field = field(UnlockedCameraClient.class, name);
            saved.put(field, field.get(null));
        }
        Object crosshair = field(UnlockedCameraClient.class, "shoulderCrosshair").get(null);
        Field ready = field(crosshair.getClass(), "ready");
        Field previousAmount = field(crosshair.getClass(), "previousAmount");
        Object savedReady = ready.get(crosshair), savedAmount = previousAmount.get(crosshair);
        CameraType oldType = mc.options.getCameraType();
        boolean oldToggle = ClientConfig.TOGGLE_MOD.get();
        boolean oldUnlocked = ClientConfig.ENABLE_UNLOCKED_CAMERA.get();
        boolean oldSkipVanilla = ClientConfig.DISABLE_VANILLA_THIRD_PERSON.get();
        boolean oldEnterMessage = ClientConfig.SHOW_ENTER_MESSAGE.get();
        boolean oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        boolean oldAlways = ClientConfig.CROSSHAIR_ALWAYS.get();
        boolean oldOnShoulder = ClientConfig.CROSSHAIR_ON_SHOULDER_OFFSET.get();
        double oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get();
        double oldMaxZoom = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        double oldMinDistance = ClientConfig.MIN_ZOOM.get();
        double oldMaxDistance = ClientConfig.MAX_ZOOM.get();
        Map<BlockPos, BlockState> changedBlocks = new LinkedHashMap<>();
        try {
            ClientConfig.TOGGLE_MOD.set(true);
            ClientConfig.ENABLE_UNLOCKED_CAMERA.set(true);
            ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(true);
            ClientConfig.SHOW_ENTER_MESSAGE.set(false);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.CROSSHAIR_ALWAYS.set(false);
            ClientConfig.CROSSHAIR_ON_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(0.95);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            ClientConfig.MIN_ZOOM.set(0.5);
            ClientConfig.MAX_ZOOM.set(32.0);
            Camera camera = new Camera();
            entryChecks(mc, camera, log);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            set("active", true);
            set("shoulderSide", 1);
            set("shoulderOffset", 0.0f);
            set("shoulderClearance", 0.95f);
            set("lastClearanceSign", 0.0f);
            ready.set(crosshair, false);
            previousAmount.set(crosshair, 0.0f);
            frame(mc, camera, 6.3f);
            for (int i = 0; i < 32; i++) camera.tick();
            for (int i = 0; i < 24; i++) frame(mc, camera, 6.3f);
            require(!UnlockedCameraClient.shouldForceCrosshair(), "fade-band crosshair hidden");
            log.accept(sample("fade", mc, camera));

            frame(mc, camera, 5.5f);
            require(!UnlockedCameraClient.shouldForceCrosshair(), "early full-offset slide remains hidden");
            log.accept(sample("enter-first-frame", mc, camera));
            for (int i = 0; i < 24; i++) frame(mc, camera, 5.5f);
            float applied = appliedOffset();
            if (Math.abs(applied) < 0.855f) {
                log.accept("SHOULDER NEEDS_CLEAR_SPACE actual applied=" + applied
                        + "; move to clear air and rerun for transition assertions");
                return;
            }
            require(UnlockedCameraClient.shouldForceCrosshair(), "settled shoulder reveals crosshair");
            log.accept(sample("settled", mc, camera));

            set("shoulderSide", -1);
            set("shoulderOffset", 0.0f);
            set("lastShoulderNanos", 0L);
            camera.setup(mc.level, mc.player, true, false, 1.0f);
            require(UnlockedCameraClient.shouldForceCrosshair(), "swap center crossing keeps crosshair");
            log.accept(sample("swap-center", mc, camera));
            for (int i = 0; i < 24; i++) frame(mc, camera, 5.5f);
            require(UnlockedCameraClient.shouldForceCrosshair(), "swapped shoulder keeps crosshair");
            log.accept(sample("swap-settled", mc, camera));

            // Client-only collision fixture, removed before returning on this same
            // client-thread call. It sends no block-change packet to any server.
            Vec3 right = new Vec3(camera.getLeftVector()).scale(-1.0);
            Vec3 center = camera.getPosition().subtract(right.scale(appliedOffset()));
            BlockPos wall = BlockPos.containing(center.add(right.scale(-0.5)));
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = wall.above(dy);
                changedBlocks.put(pos, mc.level.getBlockState(pos));
                mc.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 0);
            }
            frame(mc, camera, 5.5f);
            require(UnlockedCameraClient.shouldForceCrosshair(), "wall retains established crosshair");
            log.accept(sample("client-wall", mc, camera));
            for (Map.Entry<BlockPos, BlockState> entry : changedBlocks.entrySet()) {
                mc.level.setBlock(entry.getKey(), entry.getValue(), 0);
            }
            changedBlocks.clear();

            frame(mc, camera, 6.3f);
            require(!UnlockedCameraClient.shouldForceCrosshair(), "zoom-out hides in fade band");
            log.accept(sample("zoom-out", mc, camera));
            ClientConfig.CROSSHAIR_ALWAYS.set(true);
            require(UnlockedCameraClient.shouldForceCrosshair(), "permanent option bypasses readiness");
            ClientConfig.CROSSHAIR_ALWAYS.set(false);
            set("active", false);
            camera.setup(mc.level, mc.player, true, false, 1.0f);
            require(!UnlockedCameraClient.shouldForceCrosshair(), "disabled camera hides crosshair");
            log.accept("SHOULDER PASS actual Camera#setup transitions, swap, wall, permanent override, reset");
        } finally {
            for (Map.Entry<BlockPos, BlockState> entry : changedBlocks.entrySet()) {
                mc.level.setBlock(entry.getKey(), entry.getValue(), 0);
            }
            ClientConfig.TOGGLE_MOD.set(oldToggle);
            ClientConfig.ENABLE_UNLOCKED_CAMERA.set(oldUnlocked);
            ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(oldSkipVanilla);
            ClientConfig.SHOW_ENTER_MESSAGE.set(oldEnterMessage);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
            ClientConfig.CROSSHAIR_ALWAYS.set(oldAlways);
            ClientConfig.CROSSHAIR_ON_SHOULDER_OFFSET.set(oldOnShoulder);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMaxZoom);
            ClientConfig.MIN_ZOOM.set(oldMinDistance);
            ClientConfig.MAX_ZOOM.set(oldMaxDistance);
            mc.options.setCameraType(oldType);
            for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
            ready.set(crosshair, savedReady);
            previousAmount.set(crosshair, savedAmount);
        }
    }

    /** Exercise the actual F5 dispatcher, not just the readiness helper. */
    private static void entryChecks(Minecraft mc, Camera camera, Consumer<String> log) throws Exception {
        Method cycle = UnlockedCameraClient.class.getDeclaredMethod("cyclePerspective", Minecraft.class);
        cycle.setAccessible(true);

        enterFrom(mc, cycle, CameraType.FIRST_PERSON);
        require(UnlockedCameraClient.shouldForceCrosshair(), "direct first-person entry preserves crosshair before setup");
        require((float) field(UnlockedCameraClient.class, "shoulderOffset").get(null) == 0.0f,
                "entry preserves the original smooth shoulder animation");
        camera.setup(mc.level, mc.player, true, false, 1.0f);
        require(appliedOffset() == 0.0f, "first setup has zero elapsed shoulder time");
        require(UnlockedCameraClient.shouldForceCrosshair(), "first zero-time setup retains crosshair");
        log.accept(sample("f5-first-person-zero-time", mc, camera));
        for (int i = 0; i < 12; i++) {
            frame(mc, camera, 4.0f);
            require(UnlockedCameraClient.shouldForceCrosshair(), "direct-entry slide never drops crosshair at frame " + i);
        }
        log.accept(sample("f5-first-person-slide", mc, camera));

        // Reversing the chosen zoom must still clear this entry carryover. A
        // subsequent zoom-in starts the normal framing gate from zero again.
        frame(mc, camera, 6.3f);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "entry carryover ends in fade band");
        for (int i = 0; i < 24; i++) frame(mc, camera, 6.3f);
        frame(mc, camera, 5.5f);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "zoom-in after direct entry still waits for framing");

        // Entering at the exact selected shoulder threshold also preserves
        // first-person visibility, even though the distance still starts at 4.
        ClientConfig.MIN_ZOOM.set(5.5);
        enterFrom(mc, cycle, CameraType.FIRST_PERSON);
        require(UnlockedCameraClient.shouldForceCrosshair(), "full-shoulder entry boundary is inclusive");
        camera.setup(mc.level, mc.player, true, false, 1.0f);
        require(UnlockedCameraClient.shouldForceCrosshair(), "full-shoulder boundary survives setup");

        for (double chosen : new double[] {6.3, 8.0}) {
            ClientConfig.MIN_ZOOM.set(chosen);
            enterFrom(mc, cycle, CameraType.FIRST_PERSON);
            require(Math.abs((float) field(UnlockedCameraClient.class, "targetDistance").get(null) - chosen) < 0.00001,
                    "entry uses clamped selected zoom");
            require((float) field(UnlockedCameraClient.class, "smoothedDistance").get(null) == 4.0f,
                    "entry distance still starts at vanilla 4");
            require(!UnlockedCameraClient.shouldForceCrosshair(), "fade/far entry does not inherit first-person crosshair");
            camera.setup(mc.level, mc.player, true, false, 1.0f);
            require(!UnlockedCameraClient.shouldForceCrosshair(), "fade/far entry remains hidden after setup");
            log.accept(sample("f5-first-person-selected-" + chosen, mc, camera));
        }
        ClientConfig.MIN_ZOOM.set(0.5);

        ClientConfig.CROSSHAIR_ON_SHOULDER_OFFSET.set(false);
        enterFrom(mc, cycle, CameraType.FIRST_PERSON);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "crosshair setting off suppresses direct-entry carryover");
        camera.setup(mc.level, mc.player, true, false, 1.0f);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "crosshair setting off survives setup");
        ClientConfig.CROSSHAIR_ON_SHOULDER_OFFSET.set(true);
        ClientConfig.ENABLE_SHOULDER_OFFSET.set(false);
        enterFrom(mc, cycle, CameraType.FIRST_PERSON);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "disabled shoulder does not inherit crosshair");
        camera.setup(mc.level, mc.player, true, false, 1.0f);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "disabled shoulder stays hidden after setup");
        ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);

        enterFrom(mc, cycle, CameraType.THIRD_PERSON_BACK);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "vanilla third-person entry still waits for framing");
        camera.setup(mc.level, mc.player, true, false, 1.0f);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "vanilla third-person first setup stays hidden");
        log.accept(sample("f5-vanilla-third-person-zero-time", mc, camera));

        ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(false);
        enterFrom(mc, cycle, CameraType.FIRST_PERSON);
        require(!UnlockedCameraClient.isUnlockedCameraActive(), "normal first F5 visits vanilla back view");
        cycle.invoke(null, mc);
        require(UnlockedCameraClient.isUnlockedCameraActive(), "normal second F5 enters unlocked camera");
        require(!UnlockedCameraClient.shouldForceCrosshair(), "two-step F5 must not reuse earlier first-person visibility");
        camera.setup(mc.level, mc.player, true, false, 1.0f);
        require(!UnlockedCameraClient.shouldForceCrosshair(), "two-step F5 keeps normal first-frame gate");
        ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(true);
        log.accept("SHOULDER ENTRY PASS direct F5 before/after zero-time setup, continuous slide, zoom reset, clamped/fade zoom, options, vanilla-back/two-step entry");
    }

    private static void enterFrom(Minecraft mc, Method cycle, CameraType from) throws Exception {
        set("active", false);
        mc.options.setCameraType(from);
        cycle.invoke(null, mc);
    }

    private static void frame(Minecraft mc, Camera camera, float zoom) throws Exception {
        set("targetDistance", zoom);
        set("smoothedDistance", zoom);
        set("collisionCap", zoom);
        long previous = System.nanoTime() - 50_000_000L;
        set("lastFrameNanos", previous);
        set("lastCapNanos", previous);
        set("lastShoulderNanos", previous);
        camera.setup(mc.level, mc.player, true, false, 1.0f);
    }

    private static float appliedOffset() throws Exception {
        float offset = (float) field(UnlockedCameraClient.class, "shoulderOffset").get(null);
        float clearance = (float) field(UnlockedCameraClient.class, "shoulderClearance").get(null);
        return Math.min(Math.abs(offset), clearance) * Math.signum(offset);
    }

    private static String sample(String phase, Minecraft mc, Camera camera) throws Exception {
        Vec3 right = new Vec3(camera.getLeftVector()).scale(-1.0);
        double lateral = camera.getPosition().subtract(mc.player.getEyePosition()).dot(right);
        return "SHOULDER phase=" + phase + " actualCamera=" + camera.getPosition()
                + " lateral=" + lateral + " applied=" + appliedOffset()
                + " crosshair=" + UnlockedCameraClient.shouldForceCrosshair();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(String name, Object value) throws Exception {
        field(UnlockedCameraClient.class, name).set(null, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SHOULDER FAIL " + message);
    }
}
