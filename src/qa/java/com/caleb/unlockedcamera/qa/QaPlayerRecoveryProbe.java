package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Recover only the dead disposable test player through vanilla's respawn request. */
public final class QaPlayerRecoveryProbe {
    private static final Vec3 SAFE = new Vec3(1710.5, 160, 190.5);
    private static Consumer<String> log;
    private static CompletableFuture<?> task;
    private static boolean registered, running;
    private static int phase, age, readyServerTick;
    private static volatile boolean serverStable;

    private QaPlayerRecoveryProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (running || mc.player == null || mc.getSingleplayerServer() == null
                || (!game.endsWith(Path.of("build", "modpack-test", "game"))
                    && !game.endsWith(Path.of("build", "modpack-block-test", "game"))))
            throw new IllegalStateException("Disposable primary integrated player required");
        Path evidence = mc.gameDirectory.toPath().resolve("qa/player-recovery-" + System.currentTimeMillis() + ".log");
        log = line -> {
            output.accept(line);
            try { Files.writeString(evidence, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception error) { throw new RuntimeException(error); }
        };
        log.accept("QA_PLAYER_RECOVERY START file=" + evidence + " original_dead_void_pose_will_not_be_restored=true disposable_only=true");
        log.accept("QA_PLAYER_RECOVERY ORIGINAL_CLIENT pos=" + mc.player.position() + " velocity=" + mc.player.getDeltaMovement()
                + " health=" + mc.player.getHealth() + " dead=" + mc.player.isDeadOrDying() + " removed=" + mc.player.isRemoved()
                + " screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getName()) + " view=" + mc.options.getCameraType());
        if (!registered) { NeoForge.EVENT_BUS.addListener(QaPlayerRecoveryProbe::tick); registered = true; }
        running = true; phase = age = 0; serverStable = false;
        var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
        task = server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            log.accept("QA_PLAYER_RECOVERY ORIGINAL_SERVER pos=" + p.position() + " velocity=" + p.getDeltaMovement()
                    + " health=" + p.getHealth() + " dead=" + p.isDeadOrDying() + " removed=" + p.isRemoved()
                    + " dimension=" + p.level().dimension().location() + " mode=" + p.gameMode.getGameModeForPlayer()
                    + " flying=" + p.getAbilities().flying + " yaw=" + p.getYRot() + " pitch=" + p.getXRot()
                    + " inventory=" + p.getInventory().items);
            QaPacketBarrier.install(server);
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (task != null) { if (!task.isDone()) return; task.join(); task = null; age = 0; }
            KeyMapping.releaseAll();
            if (phase == 0) {
                field("active").setBoolean(null, false); field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (mc.player.isDeadOrDying() || mc.player.isRemoved()) {
                    mc.player.respawn();
                    log.accept("QA_PLAYER_RECOVERY RESPAWN_REQUEST vanilla_PERFORM_RESPAWN=true");
                }
                phase = 1;
                task = QaPacketBarrier.send(mc, p -> {
                    if (p.isDeadOrDying() || p.isRemoved()) throw new IllegalStateException("Vanilla respawn did not produce a live server player");
                    p.stopRiding(); p.setGameMode(GameType.CREATIVE); p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
                    p.getAbilities().flying = true; p.onUpdateAbilities();
                    p.teleportTo(p.getServer().overworld(), SAFE.x, SAFE.y, SAFE.z, 0, 0);
                    p.setDeltaMovement(Vec3.ZERO);
                    log.accept("QA_PLAYER_RECOVERY SAFE_SERVER pos=" + p.position() + " velocity=" + p.getDeltaMovement()
                            + " health=" + p.getHealth() + " flying=" + p.getAbilities().flying);
                });
                return;
            }
            if (mc.player == null || mc.player.isDeadOrDying() || mc.player.isRemoved()
                    || mc.player.position().distanceToSqr(SAFE) > .01 || !mc.player.getAbilities().flying) {
                if (++age > 1200) throw new IllegalStateException("Safe player synchronization timeout");
                return;
            }
            mc.setScreen(null); mc.player.setDeltaMovement(Vec3.ZERO); mc.player.fallDistance = 0;
            mc.player.input.forwardImpulse = mc.player.input.leftImpulse = 0;
            mc.player.input.jumping = mc.player.input.shiftKeyDown = false;
            if (phase == 1) {
                phase = 2;
                task = QaPacketBarrier.send(mc, p -> {
                    verify(p); readyServerTick = p.tickCount;
                    log.accept("QA_PLAYER_RECOVERY TELEPORT_ACKNOWLEDGED serverTick=" + readyServerTick);
                });
                return;
            }
            if (serverStable) {
                log.accept("QA_PLAYER_RECOVERY READY pos=" + mc.player.position() + " velocity=" + mc.player.getDeltaMovement()
                        + " health=" + mc.player.getHealth() + " screen=null stableServerTicks>=5 original_dead_pose_not_restored=true");
                log.accept("QA_PLAYER_RECOVERY DONE"); running = false; return;
            }
            if (++age < 10) return;
            age = 0;
            task = QaPacketBarrier.send(mc, p -> { verify(p); serverStable = p.tickCount - readyServerTick >= 5; });
        } catch (Throwable error) {
            running = false; log.accept("QA_PLAYER_RECOVERY ABORT " + error + " dead_pose_not_restored=true");
        }
    }

    private static void verify(ServerPlayer p) {
        try {
            var awaiting = p.connection.getClass().getDeclaredField("awaitingPositionFromClient"); awaiting.setAccessible(true);
            if (p.isDeadOrDying() || p.isRemoved() || p.position().distanceToSqr(SAFE) > .01 || !p.getAbilities().flying
                    || p.getDeltaMovement().lengthSqr() > 1.0E-8 || awaiting.get(p.connection) != null)
                throw new IllegalStateException("Server recovery verification failed pos=" + p.position() + " velocity=" + p.getDeltaMovement());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        var field = UnlockedCameraClient.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
