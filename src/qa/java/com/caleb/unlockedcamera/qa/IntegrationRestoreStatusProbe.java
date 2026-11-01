package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Read-only barrier and comparison against the loaded integration scenario's saved snapshot. */
public final class IntegrationRestoreStatusProbe {
    private IntegrationRestoreStatusProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Integrated server required");
        Class<?> probe = Class.forName("com.caleb.unlockedcamera.qa.IntegrationSceneProbe");
        Field running = field(probe, "running"), original = field(probe, "original");
        if (running.getBoolean(null)) {
            log.accept("INTEGRATION_RESTORE_STATUS NOT_RESTORED reason=scenario-still-running"); return;
        }
        Object saved = original.get(null);
        if (saved == null) {
            log.accept("INTEGRATION_RESTORE_STATUS NOT_RESTORED reason=no-saved-snapshot"); return;
        }
        Vec3 expectedPosition = (Vec3) value(saved, "position");
        float expectedYaw = (float) value(saved, "yaw"), expectedPitch = (float) value(saved, "pitch");
        Object expectedMode = value(saved, "mode"); int expectedSlot = (int) value(saved, "selected");
        @SuppressWarnings("unchecked") List<ItemStack> savedInventory = (List<ItemStack>) value(saved, "inventory");
        // Copy at query time so subsequent comparisons are not against the live
        // ItemStack references that the old restoration code placed in inventory.
        List<ItemStack> expectedInventory = savedInventory.stream().map(ItemStack::copy).toList();
        float[] held = UnlockedCameraClient.continuousAimAngles();
        float[] heldCopy = held == null ? null : held.clone();
        Vec3 clientPosition = mc.player.position();
        var uuid = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        log.accept("INTEGRATION_RESTORE_STATUS BARRIER_QUEUED readOnly=true snapshot=IntegrationSceneProbe.original");
        server.execute(() -> {
            try {
                if (running.getBoolean(null) || original.get(null) != saved) {
                    log.accept("INTEGRATION_RESTORE_STATUS NOT_RESTORED reason=scenario-generation-changed"); return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Player absent at server barrier");
                List<Integer> differentSlots = new ArrayList<>();
                for (int i = 0; i < Math.max(expectedInventory.size(), player.getInventory().getContainerSize()); i++) {
                    ItemStack expected = i < expectedInventory.size() ? expectedInventory.get(i) : ItemStack.EMPTY;
                    ItemStack actual = i < player.getInventory().getContainerSize() ? player.getInventory().getItem(i) : ItemStack.EMPTY;
                    if (!ItemStack.matches(expected, actual)) differentSlots.add(i);
                }
                boolean position = player.position().distanceToSqr(expectedPosition) < .0004;
                boolean mode = player.gameMode.getGameModeForPlayer() == expectedMode;
                boolean selected = player.getInventory().selected == expectedSlot;
                boolean yaw = Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw)) < .05;
                boolean pitch = Math.abs(player.getXRot() - expectedPitch) < .05;
                boolean heldMatches = heldCopy != null && Math.abs(Mth.wrapDegrees(player.getYRot() - heldCopy[0])) < .05
                        && Math.abs(player.getXRot() - heldCopy[1]) < .05;
                boolean teleportReady = field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(player.connection) == null;
                boolean currentRunning = running.getBoolean(null);
                boolean shotNull = field(probe, "shot").get(null) == null;
                int queuedSteps = ((java.util.Collection<?>) field(probe, "steps").get(null)).size();
                boolean stateRestored = position && mode && selected && differentSlots.isEmpty();
                boolean restored = stateRestored && yaw && pitch && teleportReady && !currentRunning && shotNull && queuedSteps == 0;
                log.accept("INTEGRATION_RESTORE_STATUS " + (restored ? "RESTORED" : "NOT_RESTORED")
                        + " stateRestored=" + stateRestored + " positionMatches=" + position + " modeMatches=" + mode
                        + " inventoryMatches=" + differentSlots.isEmpty() + " selectedMatches=" + selected
                        + " yawMatches=" + yaw + " pitchMatches=" + pitch + " heldAimMatches=" + heldMatches
                        + " running=" + currentRunning + " shotNull=" + shotNull + " stepsEmpty=" + (queuedSteps == 0)
                        + " queuedSteps=" + queuedSteps + " priorQueuedServerTasksDrained=true"
                        + " teleportReady=" + teleportReady + " differentSlots=" + differentSlots
                        + " actualPosition=" + player.position() + " expectedPosition=" + expectedPosition
                        + " clientPositionAtQuery=" + clientPosition + " actualYawPitch=" + player.getYRot() + "," + player.getXRot()
                        + " expectedYawPitch=" + expectedYaw + "," + expectedPitch
                        + " actualMode=" + player.gameMode.getGameModeForPlayer() + " expectedMode=" + expectedMode
                        + " actualSlot=" + player.getInventory().selected + " expectedSlot=" + expectedSlot
                        + " heldAimAtQuery=" + java.util.Arrays.toString(heldCopy)
                        + " scope=loaded-saved-snapshot-comparison;no-state-mutated");
            } catch (Throwable error) { log.accept("INTEGRATION_RESTORE_STATUS NOT_RESTORED error=" + error); }
        });
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object value(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(record);
    }
}
