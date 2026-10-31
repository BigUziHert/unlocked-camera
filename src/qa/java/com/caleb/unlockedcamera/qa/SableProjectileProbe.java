package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Real bow packets while the current crosshair targets a stationary or moving Sable plot. */
@EventBusSubscriber(modid = "unlockedcamera", value = Dist.CLIENT)
public final class SableProjectileProbe {
    private static Consumer<String> log;
    private static boolean running;
    private static volatile boolean watching;
    private static int ticks;
    private static int clientSelected;
    private static boolean previousUseDown;
    private static UUID playerId;
    private static volatile Vec3 expected;
    private static volatile double bestDot;
    private static volatile String error;
    private static volatile Snapshot snapshot;
    private static final List<UUID> spawned = new CopyOnWriteArrayList<>();
    private record Snapshot(int selected, List<ItemStack> inventory) {}

    private SableProjectileProbe() {}

    /** Command: probe SableProjectileProbe. Start while visibly aiming at the existing ship. */
    public static void run(Minecraft mc, Consumer<String> output) {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isUsingItem())
            throw new IllegalStateException("Sable projectile probe needs an idle isolated singleplayer world");
        requireSublevelHit(mc);
        log = output; running = true; watching = false; ticks = 0;
        expected = null; snapshot = null; error = null; bestDot = -1; spawned.clear();
        playerId = mc.player.getUUID();
        clientSelected = mc.player.getInventory().selected;
        previousUseDown = mc.options.keyUse.isDown();
        mc.options.keyUse.setDown(false);
        log.accept("SABLE_PROJECTILE START player=" + mc.player.position());
        mc.getSingleplayerServer().execute(() -> {
            try {
                ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("Server player absent");
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                    items.add(player.getInventory().getItem(i).copy());
                snapshot = new Snapshot(player.getInventory().selected, items);
                player.getInventory().selected = 0;
                player.getInventory().setItem(0, new ItemStack(Items.BOW));
                player.getInventory().setItem(1, new ItemStack(Items.ARROW, 64));
                player.getInventory().offhand.set(0, ItemStack.EMPTY);
                player.containerMenu.broadcastChanges();
            } catch (Throwable e) { error = e.toString(); }
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (error != null) throw new IllegalStateException(error);
            if (mc.player == null || mc.getSingleplayerServer() == null)
                throw new IllegalStateException("Test world closed");
            if (++ticks > 120) throw new IllegalStateException("Server setup/fire timeout");
            if (snapshot != null && ticks < 20) mc.player.getInventory().selected = 0;
            if (ticks == 20) {
                if (snapshot == null || !mc.player.getMainHandItem().is(Items.BOW))
                    throw new IllegalStateException("Bow fixture did not synchronize");
                requireSublevelHit(mc);
                log.accept("SABLE_PROJECTILE DRAW " + mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
                mc.options.keyUse.setDown(true);
            }
            if (ticks == 56) {
                BlockHitResult hit = requireSublevelHit(mc);
                float[] aim = UnlockedCameraClient.crosshairAimAngles();
                if (aim == null) throw new IllegalStateException("No crosshair aim at release");
                expected = Vec3.directionFromRotation(aim[1], aim[0]);
                double bodyDot = expected.dot(Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot()));
                if (bodyDot >= .995) throw new IllegalStateException("Fixture cannot distinguish body aim: dot=" + bodyDot);
                log.accept("SABLE_PROJECTILE RELEASE plot=" + hit.getBlockPos() + " raw=" + hit.getLocation()
                        + " aim=" + aim[0] + "," + aim[1] + " bodyDirectionDot=" + bodyDot);
                watching = true;
                mc.options.keyUse.setDown(false);
                mc.gameMode.releaseUsingItem(mc.player);
            }
            if (ticks == 78) {
                log.accept("SABLE_PROJECTILE " + (!spawned.isEmpty() && bestDot > .995 ? "PASS" : "FAIL")
                        + " serverProjectiles=" + spawned.size() + " bestDirectionDot=" + bestDot
                        + " (launch direction; no impact assertion)");
                finish(mc);
            }
        } catch (Throwable e) {
            log.accept("SABLE_PROJECTILE FAIL " + e);
            finish(mc);
        }
    }

    @SubscribeEvent
    public static void projectileSpawn(EntityJoinLevelEvent event) {
        // Observe the real server projectile before short-range hull collisions
        // or gravity can change its launch vector; no projectile is synthesized.
        if (!watching || event.getLevel().isClientSide() || !(event.getEntity() instanceof Projectile projectile)
                || projectile.getOwner() == null || !playerId.equals(projectile.getOwner().getUUID()) || expected == null) return;
        Vec3 velocity = projectile.getDeltaMovement();
        if (velocity.lengthSqr() < .01) return;
        double dot = velocity.normalize().dot(expected.normalize());
        bestDot = Math.max(bestDot, dot);
        spawned.add(projectile.getUUID());
        log.accept("SABLE_PROJECTILE SAMPLE entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType())
                + " ticks=" + projectile.tickCount + " velocity=" + velocity + " directionDot=" + dot);
    }

    private static BlockHitResult requireSublevelHit(Minecraft mc) {
        if (UnlockedCameraClient.crosshairRayDirection(mc.player) == null)
            throw new IllegalStateException("Shoulder camera must be active");
        mc.gameRenderer.pick(1);
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || hit.getLocation().distanceToSqr(mc.player.position()) < 256 * 256)
            throw new IllegalStateException("Crosshair must select an actual Sable plot block: " + mc.hitResult);
        return hit;
    }

    private static void finish(Minecraft mc) {
        running = false; watching = false;
        mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
        if (mc.player != null) mc.player.getInventory().selected = clientSelected;
        mc.options.keyUse.setDown(previousUseDown);
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || snapshot == null) return;
            for (UUID id : spawned) {
                var entity = player.serverLevel().getEntity(id);
                if (entity != null) entity.discard();
            }
            for (int i = 0; i < snapshot.inventory.size(); i++) player.getInventory().setItem(i, snapshot.inventory.get(i));
            player.getInventory().selected = snapshot.selected;
            player.containerMenu.broadcastChanges();
            log.accept("SABLE_PROJECTILE RESTORED");
        });
    }
}
