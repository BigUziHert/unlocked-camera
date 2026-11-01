package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Disposable client-only test driver, compiled only with -PcameraQa. */
@EventBusSubscriber(modid = "unlockedcamera", value = Dist.CLIENT)
public final class ModpackQa {
    private static final Path ROOT = Path.of("qa");
    private static int ticks;
    private static boolean ready;
    private static String benchmark;
    private static long lastFrame, start, end;
    private static int unfocusedFrames;
    private static boolean sampling;
    private static final ArrayList<Long> frames = new ArrayList<>();

    public static void log(String text) {
        try {
            Files.createDirectories(ROOT);
            Files.writeString(ROOT.resolve("results.log"), text + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        IntegrationSceneProbe.tick(mc);
        SableSceneProbe.tick(mc);
        PingActionProbe.tick(mc);
        SableMotionProbe.tick(mc);
        BlockInteractionProbe.tick(mc);
        if (!ready) {
            ready = true;
            log("READY " + mc.player.position() + " mods=" + net.neoforged.fml.ModList.get().size());
        }
        if (++ticks % 10 != 0) return;
        Path request = ROOT.resolve("request.txt");
        if (!Files.exists(request)) return;
        try {
            List<String> commands = Files.readAllLines(request);
            Files.delete(request);
            for (String line : commands) {
                String[] parts = line.split(" ", 2);
                String arg = parts.length == 2 ? parts[1] : "";
                switch (parts[0]) {
                    case "command" -> mc.player.connection.sendCommand(arg);
                    case "zoomprobe" -> invokeProbe("ZoomMouseProbe", mc);
                    case "zoomthirdprobe" -> ZoomMouseProbe.runThirdPerson(mc, ModpackQa::log);
                    case "shoulderprobe" -> invokeProbe("ShoulderRuntimeProbe", mc);
                    case "compatprobe" -> invokeProbe("CompatibilityProbe", mc);
                    case "swordsprobe" -> invokeProbe("SimplySwordsAimProbe", mc);
                    case "rayprobe" -> invokeProbe("CameraRayProbe", mc);
                    case "rayprofile" -> profileRays(mc, arg);
                    case "integrationprobe" -> IntegrationSceneProbe.start(mc, ModpackQa::log);
                    case "projectileall" -> IntegrationSceneProbe.projectiles(mc, ModpackQa::log);
                    case "projectilebaseline" -> IntegrationSceneProbe.projectileBaseline(mc, ModpackQa::log, arg);
                    case "meleeall" -> IntegrationSceneProbe.melee(mc, ModpackQa::log);
                    case "blockprobe" -> BlockInteractionProbe.run(mc, ModpackQa::log);
                    case "blockstates" -> BlockInteractionProbe.runStates(mc, ModpackQa::log);
                    case "blockinteractions" -> BlockInteractionProbe.start(mc, ModpackQa::log, arg);
                    case "blockstop" -> BlockInteractionProbe.stop(mc);
                    case "sableprobe" -> SableSceneProbe.start(mc, ModpackQa::log);
                    case "pingprobe" -> PingActionProbe.start(mc, ModpackQa::log);
                    case "probe" -> invokeProbe(arg, mc);
                    case "motionstart" -> SableMotionProbe.start(mc, ModpackQa::log, true);
                    case "motionstop" -> SableMotionProbe.stop(mc);
                    case "state" -> log(state(mc));
                    case "camera" -> {
                        ClientConfig.TOGGLE_MOD.set(true);
                        mc.setScreen(null);
                        set("active", true);
                        set("targetDistance", Float.parseFloat(arg));
                        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    }
                    case "off" -> {
                        ClientConfig.TOGGLE_MOD.set(false);
                        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    }
                    case "first" -> {
                        ClientConfig.TOGGLE_MOD.set(true);
                        set("active", false);
                        mc.options.setCameraType(CameraType.FIRST_PERSON);
                    }
                    case "benchmark" -> {
                        String[] b = arg.split(" ");
                        benchmark = b[0];
                        frames.clear();
                        unfocusedFrames = 0;
                        sampling = false;
                        start = System.nanoTime() + 10_000_000_000L;
                        end = start + Long.parseLong(b[1]) * 1_000_000_000L;
                        lastFrame = 0;
                        log("BENCH_START " + benchmark + " " + state(mc));
                    }
                    case "unpause" -> { mc.setScreen(null); mc.options.pauseOnLostFocus = false; }
                    case "quit" -> mc.stop();
                    default -> log("UNKNOWN " + line);
                }
            }
        } catch (Throwable e) { log("ERROR " + e); e.printStackTrace(); }
    }

    private static void invokeProbe(String name, Minecraft mc) throws Exception {
        Class.forName("com.caleb.unlockedcamera.qa." + name)
                .getMethod("run", Minecraft.class, java.util.function.Consumer.class)
                .invoke(null, mc, (java.util.function.Consumer<String>) ModpackQa::log);
    }

    private static void set(String name, Object value) throws Exception {
        Field f = UnlockedCameraClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static String state(Minecraft mc) {
        return "pos=" + mc.player.position() + " body=" + mc.player.getYRot() + "," + mc.player.getXRot()
                + " camera=" + mc.gameRenderer.getMainCamera().getPosition()
                + " type=" + mc.options.getCameraType() + " crosshair=" + UnlockedCameraClient.shouldForceCrosshair()
                + " hit=" + (mc.hitResult == null ? "null" : mc.hitResult.getType() + "@" + mc.hitResult.getLocation())
                + " held=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem())
                + " fps=" + mc.getFps() + " focused=" + mc.isWindowActive();
    }

    private static void profileRays(Minecraft mc, String label) {
        for (boolean aim : new boolean[]{false, true}) {
            long[] samples = new long[1000];
            Object result = null;
            for (int i = -100; i < samples.length; i++) {
                long before = System.nanoTime();
                result = aim ? UnlockedCameraClient.crosshairAimAngles()
                        : UnlockedCameraClient.cameraRayPick(mc.player,
                                mc.player.blockInteractionRange(), mc.player.entityInteractionRange(), 1.0f);
                if (i >= 0) samples[i] = System.nanoTime() - before;
            }
            Arrays.sort(samples);
            log(String.format(Locale.ROOT, "RAY_PROFILE %s %s mean_us=%.3f p95_us=%.3f p99_us=%.3f result=%s",
                    label, aim ? "aim" : "pick", Arrays.stream(samples).average().orElse(0) / 1000,
                    samples[950] / 1000.0, samples[990] / 1000.0, result));
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (benchmark == null) return;
        long now = System.nanoTime();
        if (now < start) { lastFrame = now; return; }
        if (!sampling) {
            log("BENCH_SAMPLING " + benchmark + " " + state(Minecraft.getInstance()));
            sampling = true;
            lastFrame = System.nanoTime();
            return;
        }
        if (!Minecraft.getInstance().isWindowActive()) unfocusedFrames++;
        if (lastFrame != 0) frames.add(now - lastFrame);
        lastFrame = now;
        if (now < end) return;
        try {
            Files.write(ROOT.resolve(benchmark + "-frames-ns.csv"), frames.stream().map(Object::toString).toList());
            long[] sorted = frames.stream().mapToLong(Long::longValue).sorted().toArray();
            double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
            log(String.format(Locale.ROOT, "BENCH_DONE %s frames=%d mean_ms=%.3f fps=%.2f p95_ms=%.3f p99_ms=%.3f over50ms=%d unfocusedFrames=%d",
                    benchmark, sorted.length, mean, 1000 / mean,
                    sorted[(int) (sorted.length * .95)] / 1e6,
                    sorted[(int) (sorted.length * .99)] / 1e6,
                    Arrays.stream(sorted).filter(n -> n > 50_000_000).count(), unfocusedFrames));
        } catch (Exception e) { log("BENCH_ERROR " + e); }
        benchmark = null;
    }
}
