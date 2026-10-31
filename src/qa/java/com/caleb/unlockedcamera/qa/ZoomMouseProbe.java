package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.SmoothDouble;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Runtime-only regression probe. Invoke on the client thread in an isolated test world. */
public final class ZoomMouseProbe {
    private static final double[][] INPUTS = {
            {12, -7, 1.0 / 60}, {0, 0, 1.0 / 60}, {-5, 3, 1.0 / 30},
            {4, 2, 1.0 / 144}, {0, 0, 1.0 / 60}, {-9, -2, 1.0 / 30},
            {1, 4, 1.0 / 60}, {0, 0, 1.0 / 60}
    };
    private static final boolean[] BOOLEAN_VALUES = {false, true};
    private static final double TOLERANCE = 0.00001;

    public record Result(int cases, int failures, double maximumError) {}

    private ZoomMouseProbe() {}

    /**
     * Compares the real, transformed MouseHandler pipeline with normal look,
     * freelook, and transitions between them. No sensitivity formula is copied
     * into the oracle: normal LocalPlayer.turn is the reference for every frame.
     * Just Zoom's own options and key are used, so its actual mixins execute.
     */
    public static Result run(Minecraft mc, Consumer<String> log) throws ReflectiveOperationException {
        if (!mc.isSameThread() || mc.player == null || mc.screen != null
                || mc.getCameraEntity() != mc.player) {
            throw new IllegalStateException("Zoom probe needs the client thread, local player, and no open screen");
        }

        Method turn = MouseHandler.class.getDeclaredMethod("turnPlayer", double.class);
        turn.setAccessible(true);
        Field dx = field(MouseHandler.class, "accumulatedDX");
        Field dy = field(MouseHandler.class, "accumulatedDY");
        Field yaw = field(UnlockedCameraClient.class, "freelookYaw");
        Field pitch = field(UnlockedCameraClient.class, "freelookPitch");
        KeyMapping freelook = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        SmoothDouble smoothX = (SmoothDouble) field(MouseHandler.class, "smoothTurnX").get(mc.mouseHandler);
        SmoothDouble smoothY = (SmoothDouble) field(MouseHandler.class, "smoothTurnY").get(mc.mouseHandler);

        Class<?> zoomHandler = Class.forName("de.keksuccino.justzoom.ZoomHandler");
        Field zoomModifier = zoomHandler.getField("zoomModifier");
        KeyMapping zoomKey = (KeyMapping) Class.forName("de.keksuccino.justzoom.KeyMappings")
                .getField("KEY_TOGGLE_ZOOM").get(null);
        Object zoomOptions = Class.forName("de.keksuccino.justzoom.JustZoom")
                .getMethod("getOptions").invoke(null);
        Object normalize = zoomOptions.getClass().getField("normalizeMouseSensitivityOnZoom").get(zoomOptions);
        Object zoomSmooth = zoomOptions.getClass().getField("smoothCameraOnZoom").get(zoomOptions);
        Method getOption = normalize.getClass().getMethod("getValue");
        Method setOption = normalize.getClass().getMethod("setValue", Object.class);

        CameraType previousPerspective = mc.options.getCameraType();
        double previousSensitivity = mc.options.sensitivity().get();
        boolean previousInvert = mc.options.invertYMouse().get();
        boolean previousCinematic = mc.options.smoothCamera;
        boolean previousFreelookKey = freelook.isDown();
        boolean previousZoomKey = zoomKey.isDown();
        boolean previousEnabled = ClientConfig.TOGGLE_MOD.get();
        boolean previousFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        double previousLimit = ClientConfig.FREELOOK_YAW_LIMIT.get();
        float previousYaw = mc.player.getYRot();
        float previousPitch = mc.player.getXRot();
        float previousYawOld = mc.player.yRotO;
        float previousPitchOld = mc.player.xRotO;
        float previousFreelookYaw = yaw.getFloat(null);
        float previousFreelookPitch = pitch.getFloat(null);
        double previousDx = dx.getDouble(mc.mouseHandler);
        double previousDy = dy.getDouble(mc.mouseHandler);
        float previousZoomModifier = zoomModifier.getFloat(null);
        Object previousNormalize = getOption.invoke(normalize);
        Object previousZoomSmooth = getOption.invoke(zoomSmooth);
        Map<Field, Object> previousSmoothX = snapshot(smoothX);
        Map<Field, Object> previousSmoothY = snapshot(smoothY);
        int cases = 0;
        int failures = 0;
        double maximumError = 0;

        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            ClientConfig.TOGGLE_MOD.set(true);
            ClientConfig.ENABLE_FREELOOK.set(true);
            ClientConfig.FREELOOK_YAW_LIMIT.set(90.0);
            for (double sensitivity : new double[] {0.1, 0.5, 1.0}) {
                mc.options.sensitivity().set(sensitivity);
                for (boolean zooming : BOOLEAN_VALUES) {
                    zoomKey.setDown(zooming);
                    for (float ratio : new float[] {1.0f, 0.5f, 0.2f, 0.05f}) {
                        zoomModifier.setFloat(null, ratio);
                        for (boolean normalizeValue : BOOLEAN_VALUES) {
                            setOption.invoke(normalize, normalizeValue);
                            for (boolean zoomSmoothValue : BOOLEAN_VALUES) {
                                setOption.invoke(zoomSmooth, zoomSmoothValue);
                                for (boolean cinematic : BOOLEAN_VALUES) {
                                    mc.options.smoothCamera = cinematic;
                                    for (boolean inverted : BOOLEAN_VALUES) {
                                        mc.options.invertYMouse().set(inverted);
                                        String scenario = "sensitivity=" + sensitivity + ",zoom=" + zooming
                                                + ",ratio=" + ratio + ",normalize=" + normalizeValue
                                                + ",zoomSmooth=" + zoomSmoothValue + ",cinematic=" + cinematic
                                                + ",inverted=" + inverted;
                                        float[][] reference = sequence(mc, turn, dx, dy, yaw, pitch, freelook,
                                                smoothX, smoothY, 0);
                                        for (int mode = 1; mode <= 2; mode++) {
                                            cases++;
                                            float[][] actual = sequence(mc, turn, dx, dy, yaw, pitch, freelook,
                                                    smoothX, smoothY, mode);
                                            double error = maximumError(reference, actual);
                                            maximumError = Math.max(maximumError, error);
                                            if (!Double.isFinite(error) || error > TOLERANCE) {
                                                failures++;
                                                log.accept("ZOOM_MOUSE FAIL " + scenario + ",mode=" + mode
                                                        + ",error=" + error);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            mc.options.setCameraType(previousPerspective);
            mc.options.sensitivity().set(previousSensitivity);
            mc.options.invertYMouse().set(previousInvert);
            mc.options.smoothCamera = previousCinematic;
            freelook.setDown(previousFreelookKey);
            zoomKey.setDown(previousZoomKey);
            ClientConfig.TOGGLE_MOD.set(previousEnabled);
            ClientConfig.ENABLE_FREELOOK.set(previousFreelookEnabled);
            ClientConfig.FREELOOK_YAW_LIMIT.set(previousLimit);
            mc.player.setYRot(previousYaw);
            mc.player.setXRot(previousPitch);
            mc.player.yRotO = previousYawOld;
            mc.player.xRotO = previousPitchOld;
            yaw.setFloat(null, previousFreelookYaw);
            pitch.setFloat(null, previousFreelookPitch);
            dx.setDouble(mc.mouseHandler, previousDx);
            dy.setDouble(mc.mouseHandler, previousDy);
            zoomModifier.setFloat(null, previousZoomModifier);
            setOption.invoke(normalize, previousNormalize);
            setOption.invoke(zoomSmooth, previousZoomSmooth);
            restore(smoothX, previousSmoothX);
            restore(smoothY, previousSmoothY);
        }

        Result result = new Result(cases, failures, maximumError);
        log.accept("ZOOM_MOUSE " + result);
        return result;
    }

    private static float[][] sequence(Minecraft mc, Method turn, Field dx, Field dy, Field yaw, Field pitch,
                                      KeyMapping freelook, SmoothDouble smoothX, SmoothDouble smoothY, int mode)
            throws ReflectiveOperationException {
        smoothX.reset();
        smoothY.reset();
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        mc.player.yRotO = 0;
        mc.player.xRotO = 0;
        yaw.setFloat(null, 0);
        pitch.setFloat(null, 0);
        float[][] result = new float[INPUTS.length][2];
        for (int frame = 0; frame < INPUTS.length; frame++) {
            boolean held = mode == 1 || mode == 2 && frame % 3 != 0;
            freelook.setDown(held);
            dx.setDouble(mc.mouseHandler, INPUTS[frame][0]);
            dy.setDouble(mc.mouseHandler, INPUTS[frame][1]);
            float bodyYaw = mc.player.getYRot();
            float bodyPitch = mc.player.getXRot();
            turn.invoke(mc.mouseHandler, INPUTS[frame][2]);
            if (held && (mc.player.getYRot() != bodyYaw || mc.player.getXRot() != bodyPitch)) {
                result[frame][0] = Float.NaN;
                result[frame][1] = Float.NaN;
            } else {
                result[frame][0] = mc.player.getYRot() + yaw.getFloat(null);
                result[frame][1] = mc.player.getXRot() + pitch.getFloat(null);
            }
        }
        return result;
    }

    /** Checks actual CameraType mixin ordering and the complete MouseHandler scroll dispatch. */
    public static Result runThirdPerson(Minecraft mc, Consumer<String> log) throws ReflectiveOperationException {
        if (!mc.isSameThread() || mc.player == null || mc.screen != null || mc.getOverlay() != null
                || mc.player.isSpectator() || mc.getCameraEntity() != mc.player) {
            throw new IllegalStateException("Third-person zoom probe needs a ready, non-spectator test world");
        }
        Field active = field(UnlockedCameraClient.class, "active");
        Field distance = field(UnlockedCameraClient.class, "targetDistance");
        Field scrollX = field(MouseHandler.class, "accumulatedScrollX");
        Field scrollY = field(MouseHandler.class, "accumulatedScrollY");
        Field modifier = Class.forName("de.keksuccino.justzoom.ZoomHandler").getField("zoomModifier");
        KeyMapping zoomKey = (KeyMapping) Class.forName("de.keksuccino.justzoom.KeyMappings")
                .getField("KEY_TOGGLE_ZOOM").get(null);
        Method scroll = MouseHandler.class.getDeclaredMethod("onScroll", long.class, double.class, double.class);
        scroll.setAccessible(true);

        CameraType previousPerspective = mc.options.getCameraType();
        boolean previousActive = active.getBoolean(null);
        float previousDistance = distance.getFloat(null);
        boolean previousZoomKey = zoomKey.isDown();
        float previousModifier = modifier.getFloat(null);
        double previousScrollX = scrollX.getDouble(mc.mouseHandler);
        double previousScrollY = scrollY.getDouble(mc.mouseHandler);
        double previousSensitivity = mc.options.mouseWheelSensitivity().get();
        int previousSlot = mc.player.getInventory().selected;
        boolean previousEnabled = ClientConfig.TOGGLE_MOD.get();
        boolean previousCameraEnabled = ClientConfig.ENABLE_UNLOCKED_CAMERA.get();
        double previousMin = ClientConfig.MIN_ZOOM.get();
        double previousMax = ClientConfig.MAX_ZOOM.get();
        int cases = 0;
        int failures = 0;
        try {
            ClientConfig.TOGGLE_MOD.set(true);
            ClientConfig.ENABLE_UNLOCKED_CAMERA.set(true);
            ClientConfig.MIN_ZOOM.set(0.5);
            ClientConfig.MAX_ZOOM.set(32.0);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.mouseWheelSensitivity().set(1.0);
            for (boolean cameraActive : BOOLEAN_VALUES) {
                active.setBoolean(null, cameraActive);
                for (boolean zooming : BOOLEAN_VALUES) {
                    zoomKey.setDown(zooming);
                    modifier.setFloat(null, 0.5f);
                    distance.setFloat(null, 4.0f);
                    scrollX.setDouble(mc.mouseHandler, 0);
                    scrollY.setDouble(mc.mouseHandler, 0);
                    mc.player.getInventory().selected = previousSlot;
                    String scenario = "active=" + cameraActive + ",zoom=" + zooming;

                    cases++;
                    boolean actualFirstPerson = CameraType.THIRD_PERSON_BACK.isFirstPerson();
                    boolean expectedFirstPerson = !cameraActive && zooming;
                    if (actualFirstPerson != expectedFirstPerson) {
                        failures++;
                        log.accept("ZOOM_THIRD_PERSON FAIL perspective " + scenario + ",actual=" + actualFirstPerson
                                + ",activeGate=" + UnlockedCameraClient.isUnlockedCameraActive()
                                + ",hooks=" + java.util.Arrays.stream(CameraType.class.getDeclaredMethods())
                                        .map(Method::getName).filter(name -> name.contains("preserveZoomPerspective"))
                                        .toList());
                    }

                    scroll.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0.0, 1.0);
                    boolean distanceChanged = distance.getFloat(null) != 4.0f;
                    boolean modifierChanged = modifier.getFloat(null) != 0.5f;
                    boolean slotChanged = mc.player.getInventory().selected != previousSlot;
                    cases++;
                    if (distanceChanged != (cameraActive && !zooming) || modifierChanged != zooming
                            || slotChanged != (!cameraActive && !zooming)) {
                        failures++;
                        log.accept("ZOOM_THIRD_PERSON FAIL scroll " + scenario + ",distanceChanged="
                                + distanceChanged + ",modifierChanged=" + modifierChanged + ",slotChanged=" + slotChanged);
                    }
                }
            }
            Result lens = checkThirdPersonLens(mc, zoomKey, modifier, log);
            cases += lens.cases();
            failures += lens.failures();
        } finally {
            mc.options.setCameraType(previousPerspective);
            active.setBoolean(null, previousActive);
            distance.setFloat(null, previousDistance);
            zoomKey.setDown(previousZoomKey);
            modifier.setFloat(null, previousModifier);
            scrollX.setDouble(mc.mouseHandler, previousScrollX);
            scrollY.setDouble(mc.mouseHandler, previousScrollY);
            mc.options.mouseWheelSensitivity().set(previousSensitivity);
            mc.player.getInventory().selected = previousSlot;
            ClientConfig.TOGGLE_MOD.set(previousEnabled);
            ClientConfig.ENABLE_UNLOCKED_CAMERA.set(previousCameraEnabled);
            ClientConfig.MIN_ZOOM.set(previousMin);
            ClientConfig.MAX_ZOOM.set(previousMax);
        }
        Result result = new Result(cases, failures, 0);
        log.accept("ZOOM_THIRD_PERSON " + result);
        return result;
    }

    /** Exercises the real camera setup and both of Just Zoom's lens paths, without rendering a frame. */
    private static Result checkThirdPersonLens(Minecraft mc, KeyMapping zoomKey, Field modifier,
                                               Consumer<String> log) throws ReflectiveOperationException {
        Map<Field, Object> cameraState = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "smoothedDistance", "lastFrameNanos",
                "collisionCap", "lastCapNanos", "shoulderSide", "shoulderOffset", "shoulderClearance",
                "lastClearanceSign", "lastShoulderNanos", "freelookYaw", "freelookPitch", "lastFreelookNanos"}) {
            Field state = field(UnlockedCameraClient.class, name);
            cameraState.put(state, state.get(null));
        }
        Object crosshair = field(UnlockedCameraClient.class, "shoulderCrosshair").get(null);
        Field ready = field(crosshair.getClass(), "ready");
        Field amount = field(crosshair.getClass(), "previousAmount");
        Object previousReady = ready.get(crosshair), previousAmount = amount.get(crosshair);
        boolean previousShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        boolean previousFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        KeyMapping freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        boolean previousFreelookKey = freelookKey.isDown();
        double previousShoulderAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get();
        double previousShoulderMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        Field fov = field(GameRenderer.class, "fov"), oldFov = field(GameRenderer.class, "oldFov");
        float previousFov = fov.getFloat(mc.gameRenderer), previousOldFov = oldFov.getFloat(mc.gameRenderer);
        Method getFov = GameRenderer.class.getDeclaredMethod("getFov", Camera.class, float.class, boolean.class);
        Method tickFov = GameRenderer.class.getDeclaredMethod("tickFov");
        getFov.setAccessible(true);
        tickFov.setAccessible(true);
        Class<?> zoomHandler = modifier.getDeclaringClass();
        Field cachedNormal = zoomHandler.getField("cachedNormalFov");
        Field cachedModified = zoomHandler.getField("cachedModifiedFov");
        double previousNormal = cachedNormal.getDouble(null), previousModified = cachedModified.getDouble(null);
        Object options = Class.forName("de.keksuccino.justzoom.JustZoom").getMethod("getOptions").invoke(null);
        Object smooth = options.getClass().getField("smoothZoomInOut").get(options);
        Method getOption = smooth.getClass().getMethod("getValue");
        Method setOption = smooth.getClass().getMethod("setValue", Object.class);
        Object previousSmooth = getOption.invoke(smooth);
        int cases = 0, failures = 0;
        try {
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.ENABLE_FREELOOK.set(true);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(0.95);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field(UnlockedCameraClient.class, "active").setBoolean(null, true);
            Camera camera = new Camera();
            // Fresh cameras otherwise start at foot height until Camera#tick catches up.
            field(Camera.class, "eyeHeight").setFloat(camera, mc.player.getEyeHeight());
            field(Camera.class, "eyeHeightOld").setFloat(camera, mc.player.getEyeHeight());
            zoomKey.setDown(false);
            fov.setFloat(mc.gameRenderer, 1);
            oldFov.setFloat(mc.gameRenderer, 1);
            setupSettledCamera(mc, camera);
            Vec3 normalPosition = camera.getPosition();
            Vec3 forward = new Vec3(camera.getLookVector());
            double normalBehind = mc.player.getEyePosition(1).subtract(normalPosition).dot(forward);
            double normalFov = (double) getFov.invoke(mc.gameRenderer, camera, 1.0f, true);
            for (boolean smoothing : BOOLEAN_VALUES) {
                setOption.invoke(smooth, smoothing);
                for (float ratio : new float[] {0.5f, 0.2f}) {
                    zoomKey.setDown(true);
                    modifier.setFloat(null, ratio);
                    fov.setFloat(mc.gameRenderer, 1);
                    oldFov.setFloat(mc.gameRenderer, 1);
                    // Smooth Just Zoom changes the actual renderer FOV state via tickFov;
                    // simply checking its cached target would miss a broken rendered lens.
                    if (smoothing) {
                        for (int tick = 0; tick < 32; tick++) tickFov.invoke(mc.gameRenderer);
                    }
                    setupSettledCamera(mc, camera);
                    double zoomedFov = (double) getFov.invoke(mc.gameRenderer, camera, 1.0f, true);
                    double expectedFov = Math.clamp(normalFov * ratio, 1, 170);
                    double behind = mc.player.getEyePosition(1).subtract(camera.getPosition())
                            .dot(new Vec3(camera.getLookVector()));
                    double poseChange = camera.getPosition().distanceTo(normalPosition);
                    cases++;
                    if (!Double.isFinite(zoomedFov) || zoomedFov >= normalFov
                            || Math.abs(zoomedFov - expectedFov) > 0.0001) {
                        failures++;
                        log.accept("ZOOM_THIRD_PERSON FAIL lens smooth=" + smoothing + ",ratio=" + ratio
                                + ",normalFov=" + normalFov + ",zoomedFov=" + zoomedFov
                                + ",expectedFov=" + expectedFov);
                    }
                    cases++;
                    if (!camera.isDetached() || normalBehind <= 0.25 * mc.player.getScale()
                            || behind <= 0.25 * mc.player.getScale() || poseChange > 0.00001) {
                        failures++;
                        log.accept("ZOOM_THIRD_PERSON FAIL detached pose smooth=" + smoothing + ",ratio=" + ratio
                                + ",detached=" + camera.isDetached() + ",normalBehind=" + normalBehind
                                + ",behind=" + behind + ",poseChange=" + poseChange);
                    }
                    log.accept("ZOOM_THIRD_PERSON lens smooth=" + smoothing + ",ratio=" + ratio
                            + ",normalFov=" + normalFov + ",zoomedFov=" + zoomedFov
                            + ",detached=" + camera.isDetached() + ",behind=" + behind
                            + ",poseChange=" + poseChange);
                }
            }
            // The same RETURN conflict also affects ordinary first-person zoom.
            // Use the actual freelook key in both states, with zero deflection so
            // this isolates lens magnification from camera-angle differences.
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            field(UnlockedCameraClient.class, "active").setBoolean(null, false);
            field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, 0);
            field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
            for (boolean freelooking : BOOLEAN_VALUES) {
                freelookKey.setDown(freelooking);
                zoomKey.setDown(false);
                fov.setFloat(mc.gameRenderer, 1);
                oldFov.setFloat(mc.gameRenderer, 1);
                setupSettledCamera(mc, camera);
                double firstPersonNormal = (double) getFov.invoke(mc.gameRenderer, camera, 1.0f, true);
                for (boolean smoothing : BOOLEAN_VALUES) {
                    setOption.invoke(smooth, smoothing);
                    for (float ratio : new float[] {0.5f, 0.2f}) {
                        zoomKey.setDown(true);
                        modifier.setFloat(null, ratio);
                        fov.setFloat(mc.gameRenderer, 1);
                        oldFov.setFloat(mc.gameRenderer, 1);
                        if (smoothing) {
                            for (int tick = 0; tick < 32; tick++) tickFov.invoke(mc.gameRenderer);
                        }
                        setupSettledCamera(mc, camera);
                        double zoomedFov = (double) getFov.invoke(mc.gameRenderer, camera, 1.0f, true);
                        double expected = Math.clamp(firstPersonNormal * ratio, 1, 170);
                        cases++;
                        if (!Double.isFinite(zoomedFov) || Math.abs(zoomedFov - expected) > 0.0001
                                || camera.isDetached()
                                || camera.getPosition().distanceTo(mc.player.getEyePosition(1)) > 0.00001) {
                            failures++;
                            log.accept("ZOOM_FIRST_PERSON FAIL lens freelook=" + freelooking
                                    + ",smooth=" + smoothing + ",ratio=" + ratio + ",normalFov=" + firstPersonNormal
                                    + ",zoomedFov=" + zoomedFov + ",detached=" + camera.isDetached());
                        }
                        log.accept("ZOOM_FIRST_PERSON lens freelook=" + freelooking
                                + ",smooth=" + smoothing + ",ratio=" + ratio + ",zoomedFov=" + zoomedFov);
                    }
                }
            }
            // Simply Swords currently skips Just Zoom's callback every time.
            // Exercise that real callback directly as well, so its normal path
            // cannot accidentally receive a second magnification from the fix.
            Class<?> bridge = Class.forName("com.caleb.unlockedcamera.client.JustZoomCompat");
            Method reads = bridge.getMethod("fovModifierReads");
            Method finish = bridge.getMethod("finishNonSmoothFov", double.class, long.class);
            Method zoomCallback = java.util.Arrays.stream(GameRenderer.class.getDeclaredMethods())
                    .filter(method -> method.getName().contains("return_getFov_JustZoom"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Just Zoom FOV callback missing"));
            zoomCallback.setAccessible(true);
            setOption.invoke(smooth, false);
            for (float ratio : new float[] {0.5f, 0.2f}) {
                modifier.setFloat(null, ratio);
                long before = (long) reads.invoke(null);
                CallbackInfoReturnable<Double> callback = new CallbackInfoReturnable<>("getFov", true, normalFov);
                zoomCallback.invoke(mc.gameRenderer, camera, 1.0f, true, callback);
                double alreadyZoomed = callback.getReturnValue();
                double finished = (double) finish.invoke(null, alreadyZoomed, before);
                cases++;
                if ((long) reads.invoke(null) == before || Math.abs(alreadyZoomed - normalFov * ratio) > 0.0001
                        || Math.abs(finished - alreadyZoomed) > 0.0001) {
                    failures++;
                    log.accept("ZOOM_THIRD_PERSON FAIL double magnification ratio=" + ratio
                            + ",callbackFov=" + alreadyZoomed + ",finishedFov=" + finished);
                }
            }
        } finally {
            for (Map.Entry<Field, Object> entry : cameraState.entrySet()) entry.getKey().set(null, entry.getValue());
            ready.set(crosshair, previousReady);
            amount.set(crosshair, previousAmount);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(previousShoulder);
            ClientConfig.ENABLE_FREELOOK.set(previousFreelookEnabled);
            freelookKey.setDown(previousFreelookKey);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(previousShoulderAmount);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(previousShoulderMax);
            fov.setFloat(mc.gameRenderer, previousFov);
            oldFov.setFloat(mc.gameRenderer, previousOldFov);
            cachedNormal.setDouble(null, previousNormal);
            cachedModified.setDouble(null, previousModified);
            setOption.invoke(smooth, previousSmooth);
        }
        return new Result(cases, failures, 0);
    }

    private static void setupSettledCamera(Minecraft mc, Camera camera) throws ReflectiveOperationException {
        float scale = mc.player.getScale();
        field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4);
        field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
        field(UnlockedCameraClient.class, "collisionCap").setFloat(null, 4 * scale);
        field(UnlockedCameraClient.class, "lastFrameNanos").setLong(null, 0);
        field(UnlockedCameraClient.class, "lastCapNanos").setLong(null, 0);
        field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
        field(UnlockedCameraClient.class, "shoulderOffset").setFloat(null, 0.95f * scale);
        field(UnlockedCameraClient.class, "shoulderClearance").setFloat(null, 0.95f * scale);
        field(UnlockedCameraClient.class, "lastClearanceSign").setFloat(null, 1);
        field(UnlockedCameraClient.class, "lastShoulderNanos").setLong(null, 0);
        CameraType perspective = mc.options.getCameraType();
        camera.setup(mc.level, mc.player, !perspective.isFirstPerson(), perspective.isMirrored(), 1);
    }

    private static double maximumError(float[][] reference, float[][] actual) {
        double error = 0;
        for (int frame = 0; frame < reference.length; frame++) {
            for (int axis = 0; axis < 2; axis++) {
                error = Math.max(error, Math.abs(reference[frame][axis] - actual[frame][axis]));
            }
        }
        return error;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Map<Field, Object> snapshot(SmoothDouble smooth) throws IllegalAccessException {
        Map<Field, Object> values = new LinkedHashMap<>();
        for (Field field : SmoothDouble.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                values.put(field, field.get(smooth));
            }
        }
        return values;
    }

    private static void restore(SmoothDouble smooth, Map<Field, Object> values) throws IllegalAccessException {
        for (Map.Entry<Field, Object> entry : values.entrySet()) {
            entry.getKey().set(smooth, entry.getValue());
        }
    }
}
