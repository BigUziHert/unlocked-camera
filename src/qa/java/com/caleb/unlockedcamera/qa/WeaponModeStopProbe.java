package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Controlled QA-only stop, preserving partial evidence and normal fixture restoration. */
public final class WeaponModeStopProbe {
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Disposable primary copy only");
        Field active = WeaponModeRunner.class.getDeclaredField("active"); active.setAccessible(true);
        Class<?> type = (Class<?>) active.get(null);
        if (type == null) throw new IllegalStateException("No dynamic weapon probe");
        Field running = type.getDeclaredField("running"); running.setAccessible(true);
        if (!running.getBoolean(null)) { log.accept("WEAPON_QA_STOP ALREADY_IDLE"); return; }
        log.accept("WEAPON_QA_STOP REQUEST reason=manual_offset_fixture_and_rendered_camera_use_opposite_shoulder_sides;partial_rows_preserved");
        Method finish = type.getDeclaredMethod("finish", Minecraft.class, String.class); finish.setAccessible(true);
        finish.invoke(null, mc, "ABORT");
    }
}
