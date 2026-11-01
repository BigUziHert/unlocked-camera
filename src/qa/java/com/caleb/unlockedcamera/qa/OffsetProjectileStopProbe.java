package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Minecraft;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

/** Stops only the fresh offset projectile QA core through its ordered restoration path. */
public final class OffsetProjectileStopProbe {
    private OffsetProjectileStopProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Disposable primary copy only");
        Class<?> core = (Class<?>) field(OffsetProjectileRegressionProbe.class, "active").get(null);
        if (core == null || !field(core, "running").getBoolean(null)) {
            log.accept("PROJECTILE_OFFSET_STOP ALREADY_IDLE"); return;
        }
        log.accept("PROJECTILE_OFFSET_STOP REQUEST reason=QA_Post_camera_and_rendered_shoulder_ray_disagree;partial_rows_preserved"
                + " cachedAim=" + Arrays.toString(UnlockedCameraClient.continuousAimAngles())
                + " freshAim=" + Arrays.toString(UnlockedCameraClient.crosshairAimAngles())
                + " camera=" + mc.gameRenderer.getMainCamera().getPosition()
                + " cameraYaw=" + mc.gameRenderer.getMainCamera().getYRot()
                + " bodyYaw=" + mc.player.getYRot()
                + " gapOrigin=" + UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player)
                + " direction=" + UnlockedCameraClient.crosshairRayDirection(mc.player));
        Method finish = core.getDeclaredMethod("finish", Minecraft.class, String.class); finish.setAccessible(true);
        finish.invoke(null, mc, "ABORT");
        log.accept("PROJECTILE_OFFSET_STOP REQUESTED waitFor=PROJECTILE_OFFSET_SUITE_RESTORED;not_restoration_confirmation");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
