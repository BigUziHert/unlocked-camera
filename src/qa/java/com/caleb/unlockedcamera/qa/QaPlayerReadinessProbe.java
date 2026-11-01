package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Read-only diagnosis for a restored disposable player before any recovery. */
public final class QaPlayerReadinessProbe {
    private QaPlayerReadinessProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Disposable primary QA copy required");
        log.accept("QA_PLAYER_READINESS START read_only=true");
        var p = mc.player;
        log.accept("QA_PLAYER_READINESS CLIENT screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                + " player=" + (p == null ? "null" : "pos=" + p.position() + " velocity=" + p.getDeltaMovement()
                + " health=" + p.getHealth() + " dead=" + p.isDeadOrDying() + " removed=" + p.isRemoved()
                + " creative=" + p.isCreative() + " flying=" + p.getAbilities().flying + " passenger=" + p.isPassenger()
                + " using=" + p.isUsingItem() + " dimension=" + p.level().dimension().location())
                + " goldLoaded=" + (mc.level != null && mc.level.hasChunkAt(new BlockPos(1862, 221, 193)))
                + " gold=" + (mc.level == null ? "null" : mc.level.getBlockState(new BlockPos(1862, 221, 193))));
        if (p == null || mc.getSingleplayerServer() == null) { log.accept("QA_PLAYER_READINESS DONE no_server_player=true"); return; }
        var server = mc.getSingleplayerServer(); var id = p.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) log.accept("QA_PLAYER_READINESS SERVER missing=true");
            else log.accept("QA_PLAYER_READINESS SERVER pos=" + player.position() + " velocity=" + player.getDeltaMovement()
                    + " health=" + player.getHealth() + " dead=" + player.isDeadOrDying() + " removed=" + player.isRemoved()
                    + " mode=" + player.gameMode.getGameModeForPlayer() + " flying=" + player.getAbilities().flying
                    + " passenger=" + player.isPassenger() + " dimension=" + player.level().dimension().location());
            log.accept("QA_PLAYER_READINESS DONE read_only=true");
        });
    }
}
