package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Ends a stale test actor dash through its public lifecycle while review is unequipped. */
public final class ItemUseIsolationRescueProbe {
    private ItemUseIsolationRescueProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Rescue is limited to the disposable QA copy");
        var loadedField = ItemUseReviewProbe.class.getDeclaredField("loaded"); loadedField.setAccessible(true);
        Class<?> controller = (Class<?>) loadedField.get(null);
        if (controller == null || mc.player == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Review is not loaded");
        var running = controller.getDeclaredField("running"); running.setAccessible(true);
        var waiting = controller.getDeclaredField("waitingForAbilities"); waiting.setAccessible(true);
        if (running.getBoolean(null) && !waiting.getBoolean(null))
            throw new IllegalStateException("Active review must be waiting for ability isolation");
        var id = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            try {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                if (running.getBoolean(null) && !waiting.getBoolean(null) || player == null || !player.getMainHandItem().isEmpty())
                    throw new IllegalStateException("Review isolation fixture changed");
                var type = Class.forName("net.sweenus.simplyswords.world.WhisperwindVisualManager");
                log.accept("ITEM_AIR_USE ISOLATION_RESCUE publicCall=WhisperwindVisualManager.finishDash(ServerLevel,LivingEntity) before="
                        + type.getMethod("hasActive", ServerLevel.class).invoke(null, player.serverLevel()));
                type.getMethod("finishDash", ServerLevel.class, LivingEntity.class).invoke(null, player.serverLevel(), player);
                log.accept("ITEM_AIR_USE ISOLATION_RESCUE_DONE after="
                        + type.getMethod("hasActive", ServerLevel.class).invoke(null, player.serverLevel())
                        + ";anyPendingStrikesMustExpireThroughNormalTicks");
            } catch (Throwable error) { log.accept("ITEM_AIR_USE ISOLATION_RESCUE_ERROR " + error); }
        });
    }
}
