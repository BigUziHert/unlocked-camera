package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Verifies an aborted unchanged broad sweep after its actual server mutation drains. */
public final class BlockFreelookRecoveryProbe {
    private static boolean running, registered;
    private static int phase, age, restoreAttempts;
    private static Consumer<String> log;
    private static CompletableFuture<?> earlier;
    private static CompletableFuture<Void> pending;
    private static Object snapshot;
    private static volatile Inspection observation;
    private static long verifiedAt;
    private record Inspection(long tick, int differentBlocks, int differentBlockEntities, int differentInventorySlots,
                              int remainingFixtureEntities, boolean mode, boolean selected, boolean dimension,
                              boolean pose, boolean teleportAcknowledged, boolean earlierMutationOnStack,
                              String actualPose) {
        boolean stateRestored() { return differentBlocks == 0 && differentBlockEntities == 0 && differentInventorySlots == 0
                && remainingFixtureEntities == 0 && mode && selected && dimension; }
        boolean exact() { return stateRestored() && pose && teleportAcknowledged && !earlierMutationOnStack; }
        String detail() { return "stateRestored=" + stateRestored() + " poseRestored=" + pose
                + " teleportAcknowledged=" + teleportAcknowledged + " differentBlocks=" + differentBlocks
                + " differentBlockEntities=" + differentBlockEntities + " differentInventorySlots=" + differentInventorySlots
                + " remainingFixtureEntities=" + remainingFixtureEntities + " modeRestored=" + mode
                + " selectedRestored=" + selected + " dimensionRestored=" + dimension
                + " earlierMutationOnStack=" + earlierMutationOnStack + " actualPose=" + actualPose; }
    }
    private BlockFreelookRecoveryProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Recovery is restricted to disposable QA copies");
        if (running || mc.player == null || mc.getSingleplayerServer() == null
                || field("running").getBoolean(null)) throw new IllegalStateException("Broad sweep must be stopped before recovery");
        earlier = (CompletableFuture<?>) field("task").get(null);
        snapshot = field("saved").get(null);
        if (earlier == null || snapshot == null) throw new IllegalStateException("No broad mutation/snapshot available");
        log = output; phase = age = restoreAttempts = 0; observation = null; running = true;
        if (!registered) { NeoForge.EVENT_BUS.addListener(BlockFreelookRecoveryProbe::tick); registered = true; }
        log.accept("BLOCK_FREELOOK_RECOVERY START earlierDone=" + earlier.isDone()
                + " earlierCanceled=" + earlier.isCancelled() + " policy=drain_then_verify_original_snapshot;no_resume;no_result_reclassification");
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Disconnected during recovery");
            if (field("running").getBoolean(null)) throw new IllegalStateException("Broad sweep started before recovery finished");
            age++;
            if (phase == 0) {
                if (earlier.isCancelled()) throw new IllegalStateException("Canceled earlier future cannot prove actual callback completion");
                if (!earlier.isDone()) {
                    if (age % 600 == 0) log.accept("BLOCK_FREELOOK_RECOVERY WAIT_MUTATION_DRAIN age=" + age);
                    return;
                }
                try { earlier.join(); } catch (Throwable error) { log.accept("BLOCK_FREELOOK_RECOVERY DRAINED_MUTATION_ERROR " + error); }
                pending = server(mc, p -> { QaPacketBarrier.install(mc.getSingleplayerServer()); observation = inspect(p); });
                phase = 1; age = 0;
            } else if (phase == 1) {
                if (!pending.isDone()) return;
                pending.join();
                if (observation.earlierMutationOnStack) {
                    pending = server(mc, p -> observation = inspect(p)); return;
                }
                log.accept("BLOCK_FREELOOK_RECOVERY INSPECT " + observation.detail());
                if (observation.exact()) {
                    verifiedAt = observation.tick; pending = QaPacketBarrier.send(mc, p -> observation = inspect(p));
                    phase = 4; age = 0; return;
                }
                if (++restoreAttempts > 2) { stop("NOT_RESTORED", observation.detail()); return; }
                Method release = BlockFreelookProbe.class.getDeclaredMethod("releaseHold"); release.setAccessible(true); release.invoke(null);
                mc.player.closeContainer(); mc.setScreen(null);
                log.accept("BLOCK_FREELOOK_RECOVERY RERESTORE attempt=" + restoreAttempts + " mutationDrained=true");
                pending = server(mc, BlockFreelookRecoveryProbe::restore); phase = 2; age = 0;
            } else if (phase == 2) {
                if (!pending.isDone()) return;
                pending.join(); pending = QaPacketBarrier.send(mc, p -> observation = inspect(p)); phase = 3; age = 0;
            } else if (phase == 3) {
                if (!pending.isDone()) return;
                pending.join();
                if (!observation.teleportAcknowledged) {
                    if (age > 600) { stop("NOT_RESTORED", "teleport_ack_timeout " + observation.detail()); return; }
                    pending = QaPacketBarrier.send(mc, p -> observation = inspect(p)); return;
                }
                phase = 1;
            } else if (phase == 4) {
                if (!pending.isDone()) return;
                pending.join();
                if (!observation.exact()) { phase = 1; return; }
                if (observation.tick - verifiedAt < 3) {
                    pending = QaPacketBarrier.send(mc, p -> observation = inspect(p)); return;
                }
                stop("RESTORED", "verified=true mutationDrained=true persistedServerTicks="
                        + (observation.tick - verifiedAt) + " " + observation.detail()
                        + " limits=original_snapshot_does_not_capture_flying_or_entity_NBT;camera_config_unchanged_by_recovery");
            }
        } catch (Throwable error) {
            // Never cancel pending server work or issue an overlapping restore.
            stop("NOT_RESTORED", "error=" + error + " pendingDone=" + (pending == null || pending.isDone()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Inspection inspect(ServerPlayer p) {
        try {
            ServerLevel level = (ServerLevel) value(snapshot, "level");
            Map<BlockPos, Object> blocks = (Map<BlockPos, Object>) value(snapshot, "blocks");
            List<ItemStack> inventory = (List<ItemStack>) value(snapshot, "inventory");
            Set<UUID> entities = (Set<UUID>) value(snapshot, "entities");
            int differentBlocks = 0, differentBlockEntities = 0, differentInventory = 0, extra = 0;
            for (var entry : blocks.entrySet()) {
                if (!level.getBlockState(entry.getKey()).equals(value(entry.getValue(), "state"))) differentBlocks++;
                CompoundTag expected = (CompoundTag) value(entry.getValue(), "data");
                var be = level.getBlockEntity(entry.getKey());
                CompoundTag actual = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
                if (!Objects.equals(expected, actual)) differentBlockEntities++;
            }
            for (int i = 0; i < inventory.size(); i++) if (!ItemStack.matches(inventory.get(i), p.getInventory().getItem(i))) differentInventory++;
            AABB area = (AABB) field("AREA").get(null);
            for (Entity entity : level.getEntities(p, area)) if (entity != null && !entity.isRemoved() && !entities.contains(entity.getUUID())) extra++;
            Vec3 expectedPosition = (Vec3) value(snapshot, "position");
            boolean pose = p.position().distanceToSqr(expectedPosition) < 1.0e-8
                    && Math.abs(Mth.wrapDegrees(p.getYRot() - (float) value(snapshot, "yaw"))) < .001f
                    && Math.abs(p.getXRot() - (float) value(snapshot, "pitch")) < .001f;
            Field teleport = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            teleport.setAccessible(true);
            boolean broadStack = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(BlockFreelookProbe.class.getName()));
            return new Inspection(level.getGameTime(), differentBlocks, differentBlockEntities, differentInventory, extra,
                    p.gameMode.getGameModeForPlayer() == value(snapshot, "mode"),
                    p.getInventory().selected == (int) value(snapshot, "selected"), p.serverLevel() == level,
                    pose, teleport.get(p.connection) == null, broadStack, p.position() + "," + p.getYRot() + "," + p.getXRot());
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    @SuppressWarnings("unchecked")
    private static void restore(ServerPlayer p) {
        try {
            if (java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(BlockFreelookProbe.class.getName())))
                throw new IllegalStateException("Earlier broad mutation remains on the server stack");
            ServerLevel level = (ServerLevel) value(snapshot, "level");
            Map<BlockPos, Object> blocks = (Map<BlockPos, Object>) value(snapshot, "blocks");
            List<ItemStack> inventory = (List<ItemStack>) value(snapshot, "inventory");
            Set<UUID> entities = (Set<UUID>) value(snapshot, "entities");
            AABB area = (AABB) field("AREA").get(null); BoundingBox box = (BoundingBox) field("BOX").get(null);
            p.stopRiding(); p.closeContainer(); p.setDeltaMovement(Vec3.ZERO);
            for (Entity entity : new ArrayList<>(level.getEntities(p, area)))
                if (entity != null && !entity.isRemoved() && !entities.contains(entity.getUUID())) entity.discard();
            level.getBlockTicks().clearArea(box); level.getFluidTicks().clearArea(box);
            for (var entry : blocks.entrySet()) {
                level.setBlock(entry.getKey(), (BlockState) value(entry.getValue(), "state"), 3);
                var be = level.getBlockEntity(entry.getKey()); CompoundTag data = (CompoundTag) value(entry.getValue(), "data");
                if (be != null && data != null) be.loadWithComponents(data.copy(), level.registryAccess());
            }
            for (int i = 0; i < inventory.size(); i++) p.getInventory().setItem(i, inventory.get(i).copy());
            p.getInventory().selected = (int) value(snapshot, "selected");
            p.setGameMode((GameType) value(snapshot, "mode")); Vec3 position = (Vec3) value(snapshot, "position");
            p.teleportTo(level, position.x, position.y, position.z, (float) value(snapshot, "yaw"), (float) value(snapshot, "pitch"));
            p.containerMenu.broadcastFullState(); p.inventoryMenu.broadcastFullState();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        CompletableFuture<Void> result = new CompletableFuture<>(); UUID player = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            try { action.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(player)); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private static Object value(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(record);
    }
    private static Field field(String name) throws Exception {
        Field result = BlockFreelookProbe.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void stop(String status, String detail) { running = false; log.accept("BLOCK_FREELOOK_RECOVERY " + status + " " + detail); }
}
