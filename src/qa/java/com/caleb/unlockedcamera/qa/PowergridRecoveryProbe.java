package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Restores only the stopped QA session, after its original server stack has actually unwound. */
public final class PowergridRecoveryProbe {
    private static boolean registered, running;
    private static int age, wait, phase;
    private static Class<?> parent;
    private static Object snapshot;
    private static Vec3 position;
    private static float yaw, pitch;
    private static GameType mode;
    private static int selected;
    private static List<ItemStack> inventory;
    private static Consumer<String> log;
    private static CompletableFuture<?> pending;
    private static volatile boolean drained, mutated;
    private static volatile Verification verification;
    private record Verification(boolean coreState, boolean pose, boolean acknowledged, int differentSlots,
                                int differentBlocks, Vec3 position, float yaw, float pitch) {}
    private PowergridRecoveryProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Idle integrated-server recovery required");
        parent = Class.forName("com.caleb.unlockedcamera.qa.PowergridEndpointSession");
        snapshot = field(parent, "snapshot").get(null);
        if (snapshot == null) throw new IllegalStateException("No Powergrid saved snapshot exists");
        if (field(parent, "running").getBoolean(null) && !field(parent, "restoring").getBoolean(null))
            throw new IllegalStateException("Powergrid has not entered its abort/restoration phase");
        position = (Vec3) value(snapshot, "position"); yaw = (float) value(snapshot, "yaw"); pitch = (float) value(snapshot, "pitch");
        mode = (GameType) value(snapshot, "mode"); selected = (int) value(snapshot, "selected");
        @SuppressWarnings("unchecked") List<ItemStack> saved = (List<ItemStack>) value(snapshot, "inventory");
        inventory = saved.stream().map(ItemStack::copy).toList();
        log = output; age = wait = phase = 0; pending = null; drained = mutated = false; verification = null;
        // Stop the old observer from repeatedly timing out and queuing more cleanup.
        // Its current pending future is the abort's packet barrier, not the lost setup future.
        field(parent, "running").setBoolean(null, false);
        field(parent, "terminalPoint").set(null, null);
        Collection<?> steps = (Collection<?>) field(parent, "steps").get(null); int cleared = steps.size(); steps.clear();
        Object previous = field(parent, "pending").get(null);
        if (previous instanceof CompletableFuture<?> future) future.cancel(false);
        field(parent, "pending").set(null, null);
        restoreClientSettings(mc); body(mc);
        if (!registered) { NeoForge.EVENT_BUS.addListener(PowergridRecoveryProbe::tick); registered = true; }
        running = true;
        log.accept("POWERGRID_RECOVERY START originalSessionStopped=true clearedSteps=" + cleared
                + " scope=saved-QA-blocks-inventory-mode-and-pose;requires-original-server-stack-unwound");
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 12000) throw new IllegalStateException("Recovery still waiting after 12000 client ticks; no restored claim");
            requireGeneration(); body(mc);
            if (pending != null) { if (!pending.isDone()) return; pending.join(); pending = null; }
            if (wait-- > 0) return;
            if (phase == 0) {
                drained = false;
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                pending = server.submit(() -> {
                    try {
                        requireGeneration();
                        // This check MUST be inside the queued server callback. Chunk loading
                        // can reenter an executor; an outer setup frame must block restoration.
                        if (originalFrameOnCurrentStack()) return;
                        drained = true;
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p == null) throw new IllegalStateException("Player absent");
                        int count = 0;
                        if (field(parent, "fixtureInitialized").getBoolean(null)) {
                            for (var entry : savedBlocks().entrySet()) { p.serverLevel().setBlock(entry.getKey(), entry.getValue(), 3); count++; }
                        }
                        p.getInventory().clearContent();
                        for (int i = 0; i < inventory.size(); i++) p.getInventory().setItem(i, inventory.get(i).copy());
                        p.getInventory().selected = selected; p.setGameMode(mode);
                        p.teleportTo(p.serverLevel(), position.x, position.y, position.z, yaw, pitch);
                        p.setYRot(yaw); p.setXRot(pitch); p.setYHeadRot(yaw); p.setYBodyRot(yaw);
                        p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
                        mutated = true;
                        log.accept("POWERGRID_RECOVERY APPLIED originalServerStackUnwound=true restoredBlocks=" + count);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                });
                phase = 1; return;
            }
            if (phase == 1) {
                if (!drained || !mutated) {
                    if (age % 200 < 25) log.accept("POWERGRID_RECOVERY WAIT originalSetupStillOnServerStack=true");
                    phase = 0; wait = 20; return;
                }
                if (mc.player.position().distanceToSqr(position) > .0004) return;
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                verification = null;
                pending = server.submit(() -> {
                    try {
                        requireGeneration();
                        if (originalFrameOnCurrentStack()) throw new IllegalStateException("Original Powergrid setup reappeared after recovery");
                        verification = verify(server.getPlayerList().getPlayer(uuid));
                    } catch (Exception error) { throw new IllegalStateException(error); }
                });
                phase = 2; return;
            }
            if (verification == null) return;
            if (!verification.acknowledged || !verification.pose) { phase = 1; wait = 5; return; }
            boolean okay = verification.coreState && verification.pose && verification.acknowledged;
            if (okay) field(parent, "restoring").setBoolean(null, false);
            log.accept("POWERGRID_RECOVERY " + (okay ? "RESTORED" : "NOT_RESTORED")
                    + " originalServerStackUnwound=" + drained + " stateRestored=" + verification.coreState
                    + " poseRestored=" + verification.pose + " teleportAcknowledged=" + verification.acknowledged
                    + " differentInventorySlots=" + verification.differentSlots + " differentBlocks=" + verification.differentBlocks
                    + " actualPosition=" + verification.position + " expectedPosition=" + position
                    + " actualYawPitch=" + verification.yaw + "," + verification.pitch + " expectedYawPitch=" + yaw + "," + pitch);
            running = false;
        } catch (Throwable error) { running = false; log.accept("POWERGRID_RECOVERY NOT_RESTORED error=" + error); }
    }

    private static boolean originalFrameOnCurrentStack() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals(parent.getName()) || frame.getClassName().startsWith(parent.getName() + "$$Lambda")) return true;
        }
        return false;
    }
    private static Verification verify(ServerPlayer p) throws Exception {
        if (p == null) throw new IllegalStateException("Player absent during verification");
        int slots = 0, blocks = 0;
        for (int i = 0; i < inventory.size(); i++) if (!ItemStack.matches(inventory.get(i), p.getInventory().getItem(i))) slots++;
        for (var entry : savedBlocks().entrySet()) if (!p.serverLevel().getBlockState(entry.getKey()).equals(entry.getValue())) blocks++;
        boolean state = slots == 0 && blocks == 0 && p.getInventory().selected == selected
                && p.gameMode.getGameModeForPlayer() == mode && p.position().distanceToSqr(position) < .0004;
        boolean pose = Math.abs(Mth.wrapDegrees(p.getYRot() - yaw)) < .05 && Math.abs(p.getXRot() - pitch) < .05;
        boolean ready = field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(p.connection) == null;
        return new Verification(state, pose, ready, slots, blocks, p.position(), p.getYRot(), p.getXRot());
    }
    @SuppressWarnings("unchecked") private static Map<BlockPos, BlockState> savedBlocks() throws Exception {
        return (Map<BlockPos, BlockState>) field(parent, "savedBlocks").get(null);
    }
    @SuppressWarnings("unchecked") private static void restoreClientSettings(Minecraft mc) throws Exception {
        for (var entry : ((Map<Field, Object>) field(parent, "savedCamera").get(null)).entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(field(parent, "oldToggle").getBoolean(null));
        ClientConfig.ENABLE_SHOULDER_OFFSET.set(field(parent, "oldShoulder").getBoolean(null));
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(field(parent, "oldAmount").getDouble(null));
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(field(parent, "oldMax").getDouble(null));
        ClientConfig.ENABLE_FREELOOK.set(field(parent, "oldFreelookEnabled").getBoolean(null));
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(field(parent, "oldKeepDirection").getBoolean(null));
        ((KeyMapping) field(parent, "freelookKey").get(null)).setDown(field(parent, "oldFreelook").getBoolean(null));
        boolean shift = field(parent, "oldShift").getBoolean(null);
        mc.options.keyShift.setDown(shift); mc.player.setShiftKeyDown(shift); mc.player.input.shiftKeyDown = shift;
        mc.options.setCameraType((CameraType) field(parent, "oldType").get(null));
    }
    private static void body(Minecraft mc) throws Exception {
        mc.player.getInventory().selected = selected;
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw); mc.player.yHeadRotO = yaw; mc.player.setYBodyRot(yaw); mc.player.yBodyRotO = yaw;
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager); if (camera == null) return;
        Class<?> type = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object rotation = type.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", type).invoke(camera, rotation); field(camera.getClass(), "playerRotation").set(camera, rotation);
    }
    private static void requireGeneration() throws Exception {
        if (field(parent, "snapshot").get(null) != snapshot || field(parent, "running").getBoolean(null))
            throw new IllegalStateException("Powergrid scenario restarted during recovery");
    }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Object value(Object record, String name) throws Exception { Method m = record.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(record); }
}
