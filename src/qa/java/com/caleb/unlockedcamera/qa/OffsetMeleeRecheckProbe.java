package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Fresh complete offset melee suite using natural rendered camera geometry. */
public final class OffsetMeleeRecheckProbe {
    private static Class<?> active;
    private static boolean registered;
    private static long renderedFrames;

    private OffsetMeleeRecheckProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.isSameThread()) throw new IllegalStateException("Client-thread startup required");
        checkIdle(IntegrationSceneProbe.class);
        checkRunner(WeaponModeRunner.class);
        checkRunner(OffsetProjectileRegressionProbe.class);
        Class<?> projectileRecheck = (Class<?>) field(OffsetProjectileRecheckProbe.class, "loaded").get(null);
        if (projectileRecheck != null) checkRunner(projectileRecheck);
        if (active != null) checkIdle(active);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> renderedFrames++);
            registered = true;
        }
        Consumer<String> renamed = line -> log.accept(line.replace("MELEE_OFFSET", "MELEE_OFFSET_RECHECK")
                .replace("pose=direct_fixture;", "pose=natural_render_camera;"));
        // WeaponModeRunner creates a fresh IntegrationSceneProbe/nested-class
        // loader on each start. The scheduled inventory and attack oracles remain
        // the existing complete offsetMelee suite, without retaining old PASS rows.
        WeaponModeRunner.start(mc, renamed, "offsetMelee");
        active = (Class<?>) field(WeaponModeRunner.class, "active").get(null);
        try {
            field(active, "cameraPosition").set(null, null);
            addInitialRenderedCameraGate(mc, log, active);
        } catch (Exception setupError) {
            // A reflective readiness setup failure must not leave the scheduled
            // suite running after the QA command reports its startup error.
            try {
                var finish = active.getDeclaredMethod("finish", Minecraft.class, String.class);
                finish.setAccessible(true); finish.invoke(null, mc, "ABORT");
            } catch (Exception cleanupError) { setupError.addSuppressed(cleanupError); }
            throw setupError;
        }
        log.accept("MELEE_OFFSET_RECHECK_FIXTURE natural_render_camera=true manual_camera_position_disabled=true"
                + " complete_live_inventory=true attack_and_damage_oracles_unchanged=true"
                + " target_placement=existing_gap_free_ray_after_render_gate"
                + " reason=prior_Post_fixture_used_opposite_horizontal_shoulder_sign;prior_rows_preserved");
    }

    @SuppressWarnings("unchecked")
    private static void addInitialRenderedCameraGate(Minecraft mc, Consumer<String> log, Class<?> core) throws Exception {
        ArrayDeque<Object> steps = (ArrayDeque<Object>) field(core, "steps").get(null);
        var original = new ArrayList<>(steps);
        Class<?> stepType = Class.forName(core.getName() + "$Step", false, core.getClassLoader());
        Class<?> actionType = Class.forName(core.getName() + "$Action", false, core.getClassLoader());
        var delay = stepType.getDeclaredMethod("delayAfter"); delay.setAccessible(true);
        if (original.size() < 3 || (int) delay.invoke(original.get(0)) != 50 || (int) delay.invoke(original.get(1)) != 25)
            throw new IllegalStateException("Unexpected integration startup sequence; render gate cannot be placed safely");
        Constructor<?> constructor = stepType.getDeclaredConstructor(int.class, actionType); constructor.setAccessible(true);
        Object[] gate = new Object[1];
        long[] firstFrame = {-1}; int[] polls = {0};
        Object action = Proxy.newProxyInstance(core.getClassLoader(), new Class<?>[]{actionType}, (proxy, method, args) -> {
            if (!method.getName().equals("run")) {
                return switch (method.getName()) {
                    case "toString" -> "Natural offset melee camera readiness";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (firstFrame[0] < 0) firstFrame[0] = renderedFrames;
            Vec3 eye = mc.player.getEyePosition();
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player);
            Vec3 direction = UnlockedCameraClient.crosshairRayDirection(mc.player);
            boolean ready = renderedFrames - firstFrame[0] >= 2 && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                    && origin != null && direction != null && direction.distanceToSqr(new Vec3(0, 0, 1)) < 1.0E-5
                    && origin.subtract(eye).distanceToSqr(new Vec3(-.95, 0, 0)) < .01
                    && camera.subtract(eye).distanceToSqr(new Vec3(-.95, 0, -4)) < .04;
            if (!ready) {
                if (++polls[0] > 200) throw new IllegalStateException("Natural melee camera did not settle camera=" + camera
                        + " eye=" + eye + " gapOrigin=" + origin + " direction=" + direction + " renderedFrames=" + (renderedFrames - firstFrame[0]));
                steps.addFirst(gate[0]);
                return null;
            }
            log.accept("MELEE_OFFSET_RECHECK_CAMERA_READY actualRenderFrames=" + (renderedFrames - firstFrame[0])
                    + " camera=" + camera + " eye=" + eye + " gapOrigin=" + origin + " direction=" + direction
                    + " bodyYaw=" + mc.player.getYRot() + " placement=unmodified_core_ray_capture;no_camera_or_server_aim_write=true");
            return null;
        });
        gate[0] = constructor.newInstance(0, action);
        // Initial setup and shoulder configuration already wait50 and25 actual
        // server ticks. Add a read-only rendered-frame gate immediately after
        // those, before the initial geometry capture. All scheduled melee cases
        // keep that same view configuration and sample its current natural ray.
        steps.clear();
        for (int i = 0; i < original.size(); i++) {
            if (i == 2) steps.addLast(gate[0]);
            steps.addLast(original.get(i));
        }
    }

    private static void checkRunner(Class<?> runner) throws Exception {
        Class<?> core = (Class<?>) field(runner, "active").get(null);
        if (core != null) checkIdle(core);
    }

    private static void checkIdle(Class<?> core) throws Exception {
        if (field(core, "running").getBoolean(null)) throw new IllegalStateException("Prior integration suite is active");
        for (String name : new String[]{"serverTask", "observationTask"}) {
            CompletableFuture<?> pending = (CompletableFuture<?>) field(core, name).get(null);
            if (pending != null && !pending.isDone()) throw new IllegalStateException("Prior callback still draining: " + name);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
}
