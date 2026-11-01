package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Loads only the opt-in QA driver, leaving all production classes and live suites untouched. */
public final class WeaponModeRunner {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.IntegrationSceneProbe";
    private static Class<?> active;
    private static boolean registered;
    private static Method tick, frame, spawn;
    private static Consumer<String> output;
    private WeaponModeRunner() {}
    public static void start(Minecraft mc, Consumer<String> log, boolean melee) throws Exception {
        start(mc, log, melee ? "offsetMelee" : "freelookProjectiles");
    }
    public static void start(Minecraft mc, Consumer<String> log, String operation) throws Exception {
        boolean melee = operation.equals("offsetMelee") || operation.equals("offsetMeleeResume") || operation.equals("meleePathDiagnostic");
        String marker = switch (operation) {
            case "offsetMelee", "offsetMeleeResume" -> "MELEE_OFFSET";
            case "meleePathDiagnostic" -> "MELEE_PATH";
            case "freelookProjectiles" -> "PROJECTILE_FREELOOK";
            case "freelookAbility" -> "ABILITY_FREELOOK";
            case "pairedAbilityRecheck" -> "ABILITY_PAIR";
            case "standardRecheck" -> "INTEGRATION_RECHECK";
            default -> throw new IllegalArgumentException(operation);
        };
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Weapon mode tests require the disposable primary copy");
        if (running(IntegrationSceneProbe.class) || active != null && running(active))
            throw new IllegalStateException("Another integration weapon suite is running");
        ClassLoader loader = new ClassLoader(WeaponModeRunner.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (in == null) throw new ClassNotFoundException(name);
                            byte[] data = in.readAllBytes(); result = defineClass(name, data, 0, data.length);
                        } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                    }
                    if (resolve) resolveClass(result); return result;
                }
            }
        };
        active = loader.loadClass(TYPE);
        tick = active.getMethod("tick", Minecraft.class);
        frame = active.getMethod("afterFrame", RenderFrameEvent.Post.class);
        spawn = active.getMethod("projectileSpawn", EntityJoinLevelEvent.class);
        output = line -> {
            if (line.startsWith("INTEGRATION ")) log.accept(marker + "_SUITE " + line.substring(12));
            else if (line.startsWith("MELEE ") && melee) log.accept(marker + " " + line.substring(6));
            else if (line.startsWith("PROJECTILE ") && operation.equals("freelookProjectiles")) log.accept("PROJECTILE_FREELOOK " + line.substring(11));
            else log.accept(marker + "_DETAIL " + line);
        };
        if (!registered) {
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> invoke(tick, Minecraft.getInstance()));
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> invoke(frame, event));
            NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> invoke(spawn, event));
            registered = true;
        }
        log.accept(marker + "_MODE " + (operation.equals("pairedAbilityRecheck") ? "paired_first_person_body60_camera60_and_real_freelook_body0_camera60;physical_key_release_then_press;read_only_handler_diagnostics"
                : operation.equals("standardRecheck") ? "mixed_standard_actions;direct_fixture_camera;full_inventory_sync;ordered_packet_acks"
                : operation.equals("meleePathDiagnostic") ? "paired_first_person_and_offset;latest_offset_nonpass_only;actual_input_event_and_exact_victim_trace"
                : melee ? "THIRD_PERSON_BACK active=true shoulderOffset=.95 freelookKey=false" : "FIRST_PERSON active=false freelookKey=true bodyYaw=0 cameraYaw=60 keepDirection=false"));
        active.getMethod(operation, Minecraft.class, Consumer.class).invoke(null, mc, output);
    }
    private static boolean running(Class<?> type) throws Exception {
        var field = type.getDeclaredField("running"); field.setAccessible(true); return field.getBoolean(null);
    }
    private static void invoke(Method method, Object event) {
        try { if (active != null && running(active)) method.invoke(null, event); }
        catch (Throwable error) { output.accept("INTEGRATION ABORT callback " + error); }
    }
}
