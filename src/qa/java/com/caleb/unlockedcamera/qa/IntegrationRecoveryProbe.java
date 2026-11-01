package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Recover the verified residual pose from a finished QA integration snapshot; never starts a scenario. */
public final class IntegrationRecoveryProbe {
    private static boolean registered, running;
    private static int age, phase;
    private static Class<?> parent;
    private static Object snapshot;
    private static Vec3 position;
    private static float yaw, pitch;
    private static GameType mode;
    private static int selected;
    private static List<ItemStack> inventory;
    private static Consumer<String> log;
    private static CompletableFuture<?> pending;
    private static volatile Verification verification;
    private record Verification(boolean state, boolean pose, boolean ready, boolean idle, Vec3 actualPosition,
                                float actualYaw, float actualPitch, int differentSlots) {}
    private IntegrationRecoveryProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Idle integrated-server recovery required");
        parent = Class.forName("com.caleb.unlockedcamera.qa.IntegrationSceneProbe");
        if (field(parent, "running").getBoolean(null)) throw new IllegalStateException("Integration scenario is still running");
        snapshot = field(parent, "original").get(null);
        if (snapshot == null) throw new IllegalStateException("No integration snapshot exists");
        position = (Vec3) value(snapshot, "position"); yaw = (float) value(snapshot, "yaw"); pitch = (float) value(snapshot, "pitch");
        mode = (GameType) value(snapshot, "mode"); selected = (int) value(snapshot, "selected");
        @SuppressWarnings("unchecked") List<ItemStack> savedInventory = (List<ItemStack>) value(snapshot, "inventory");
        inventory = savedInventory.stream().map(ItemStack::copy).toList();
        log = output; age = phase = 0; verification = null;
        if (!registered) { NeoForge.EVENT_BUS.addListener(IntegrationRecoveryProbe::tick); registered = true; }
        running = true;
        log.accept("INTEGRATION_RECOVERY START scope=finished-QA-shot-and-step-cleanup-plus-saved-pose;inventory-and-mode-read-only");
        var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
        pending = server.submit(() -> {
            try {
                requireSameGeneration();
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                Verification before = verify(player);
                if (!before.state) throw new IllegalStateException("Non-pose snapshot state changed; refusing pose-only recovery: " + before);
                Field shot = field(parent, "shot"); boolean shotWasNull = shot.get(null) == null;
                Collection<?> steps = (Collection<?>) field(parent, "steps").get(null); int clearedSteps = steps.size();
                shot.set(null, null); steps.clear();
                log.accept("INTEGRATION_RECOVERY CLEANUP afterServerBarrier=true shotWasNull=" + shotWasNull + " clearedSteps=" + clearedSteps
                        + " oldYawPitch=" + player.getYRot() + "," + player.getXRot() + " savedYawPitch=" + yaw + "," + pitch);
                // Put the saved angles into the actual teleport packet itself.
                player.teleportTo(player.serverLevel(), position.x, position.y, position.z, yaw, pitch);
                player.setYRot(yaw); player.yRotO = yaw; player.setXRot(pitch); player.xRotO = pitch;
                player.setYHeadRot(yaw); player.yHeadRotO = yaw; player.setYBodyRot(yaw); player.yBodyRotO = yaw;
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 600) throw new IllegalStateException("Recovery teleport/verification timeout");
            requireSameGeneration();
            if (pending != null) { if (!pending.isDone()) return; pending.join(); pending = null; }
            body(mc, yaw, pitch);
            if (phase == 0) {
                if (age < 5 || mc.player.position().distanceToSqr(position) > .0004) return;
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                verification = null;
                pending = server.submit(() -> {
                    try { requireSameGeneration(); verification = verify(server.getPlayerList().getPlayer(uuid)); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                });
                phase = 1; return;
            }
            if (verification == null) return;
            if (!verification.ready || !verification.pose) { phase = 0; return; }
            boolean restored = verification.state && verification.pose && verification.ready && verification.idle;
            log.accept("INTEGRATION_RECOVERY " + (restored ? "RESTORED" : "NOT_RESTORED")
                    + " stateRestored=" + verification.state + " poseRestored=" + verification.pose
                    + " teleportAcknowledged=" + verification.ready + " qaIdle=" + verification.idle
                    + " actualPosition=" + verification.actualPosition + " expectedPosition=" + position
                    + " actualYawPitch=" + verification.actualYaw + "," + verification.actualPitch
                    + " expectedYawPitch=" + yaw + "," + pitch + " differentInventorySlots=" + verification.differentSlots
                    + " clientYawPitch=" + mc.player.getYRot() + "," + mc.player.getXRot());
            running = false;
        } catch (Throwable error) { running = false; log.accept("INTEGRATION_RECOVERY NOT_RESTORED error=" + error); }
    }

    private static Verification verify(ServerPlayer player) throws Exception {
        if (player == null) throw new IllegalStateException("Player absent during recovery");
        int differences = 0;
        for (int i = 0; i < Math.max(inventory.size(), player.getInventory().getContainerSize()); i++) {
            ItemStack expected = i < inventory.size() ? inventory.get(i) : ItemStack.EMPTY;
            ItemStack actual = i < player.getInventory().getContainerSize() ? player.getInventory().getItem(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(expected, actual)) differences++;
        }
        boolean state = differences == 0 && player.getInventory().selected == selected && player.gameMode.getGameModeForPlayer() == mode
                && player.position().distanceToSqr(position) < .0004;
        boolean pose = Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) < .05 && Math.abs(player.getXRot() - pitch) < .05;
        boolean ready = field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(player.connection) == null;
        boolean idle = !field(parent, "running").getBoolean(null) && field(parent, "shot").get(null) == null
                && ((Collection<?>) field(parent, "steps").get(null)).isEmpty();
        return new Verification(state, pose, ready, idle, player.position(), player.getYRot(), player.getXRot(), differences);
    }
    private static void requireSameGeneration() throws Exception {
        if (field(parent, "running").getBoolean(null) || field(parent, "original").get(null) != snapshot)
            throw new IllegalStateException("Integration scenario restarted during recovery");
    }
    private static void body(Minecraft mc, float yaw, float pitch) throws Exception {
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setYHeadRot(yaw); mc.player.yHeadRotO = yaw;
        mc.player.setYBodyRot(yaw); mc.player.yBodyRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object value = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, value); field(camera.getClass(), "playerRotation").set(camera, value);
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object value(Object record, String name) throws Exception { Method method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(record); }
}
