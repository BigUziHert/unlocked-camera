package com.caleb.unlockedcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;

/** Precise, once-per-tick packet aim for addons that re-raycast server-side. */
public final class AddonInteractionAim {
    private static boolean initialized;
    private static Method isWire;
    private static Class<?> moduleItem, consoleEntity, wrenchItem;
    private static Method moduleType, nearestFree, nearestOccupied, mountSpots, spotTarget, spotCenter;

    private AddonInteractionAim() {}

    /** The addon clips in the console's render-pose coordinates, which may be scaled. */
    public static Double consoleRayParameter(BlockEntity target, Vec3 direction, float partialTick, double localDistanceSquared) {
        return SableCompat.consoleRayParameter(target, direction, partialTick, localDistanceSquared);
    }

    /**
     * An interaction target, never the projectile collider/centre-nudge target.
     * The caller caches this in the existing packet hold; no body or camera
     * orientation is changed here. Null means no applicable interaction.
     */
    public static float[] angles(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player);
        if (origin == null || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return null;
        if (!initialized) init();
        try {
            Vec3 eye = mc.player.getEyePosition();
            double reachSqr = Mth.square(mc.player.blockInteractionRange());
            Vec3 hitPoint = SableCompat.interactionWorldPoint(mc.level, hit.getLocation());
            if (hitPoint == null || hitPoint.distanceToSqr(eye) > reachSqr) return null;
            Vec3 point;
            ItemStack held = mc.player.getMainHandItem();
            if (isWire != null && (boolean) isWire.invoke(null, mc.level, held.getItem())) {
                // The use packet already names this OUTLINE hit. Preserve its
                // precise terminal coordinate instead of burying it in a block.
                point = hitPoint;
            } else {
                BlockEntity console = mc.level.getBlockEntity(hit.getBlockPos());
                if (consoleEntity == null || !consoleEntity.isInstance(console)) return null;
                // ConsoleBlock.useItemOn accepts only main-hand placement.
                // Empty-main interaction is consumed by the console itself;
                // an offhand module must not create an unrelated aim hold.
                boolean placing = moduleItem.isInstance(held.getItem());
                boolean removing = wrenchItem != null && wrenchItem.isInstance(held.getItem());
                if (!placing && !removing) return null;
                Vec3 direction = UnlockedCameraClient.crosshairRayDirection(mc.player);
                Vec3 end = origin.add(direction.scale(mc.player.blockInteractionRange()));
                Object target = placing
                        ? nearestFree.invoke(console, origin, end, moduleType.invoke(held.getItem()))
                        : nearestOccupied.invoke(console, origin, end);
                if (target == null) return null;
                point = null;
                for (Object spot : (List<?>) mountSpots.invoke(console)) {
                    if (!target.equals(spotTarget.invoke(spot))) continue;
                    Vec3 local = (Vec3) spotCenter.invoke(spot);
                    point = SableCompat.interactionWorldPoint(mc.level, local.add(Vec3.atLowerCornerOf(hit.getBlockPos())));
                    break;
                }
            }
            if (point == null || point.distanceToSqr(eye) > reachSqr) return null;
            Vec3 aim = point.subtract(eye);
            double horizontal = aim.horizontalDistance();
            if (aim.lengthSqr() < 1.0E-8 || horizontal < 1.0E-6) return null;
            float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
            return new float[]{mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()),
                    (float) -Math.toDegrees(Math.atan2(aim.y, horizontal)), (float) aim.length()};
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            // Optional addon APIs can change independently of the camera mod.
            return null;
        }
    }

    private static void init() {
        initialized = true;
        try {
            isWire = Class.forName("org.patryk3211.powergrid.electricity.wire.IWire")
                    .getMethod("isWire", Level.class, Item.class);
        } catch (ReflectiveOperationException | LinkageError absent) { isWire = null; }
        try {
            moduleItem = Class.forName("com.mred231.aeroworks.content.controls.module.ModuleItem");
            consoleEntity = Class.forName("com.mred231.aeroworks.content.controls.console.ConsoleBlockEntity");
            Class<?> type = Class.forName("com.mred231.aeroworks.content.controls.module.ModuleType");
            Class<?> spot = Class.forName("com.mred231.aeroworks.content.controls.console.ConsoleBlockEntity$MountSpot");
            moduleType = moduleItem.getMethod("getType");
            nearestFree = consoleEntity.getMethod("nearestFreeMount", Vec3.class, Vec3.class, type);
            nearestOccupied = consoleEntity.getMethod("nearestOccupiedMount", Vec3.class, Vec3.class);
            mountSpots = consoleEntity.getDeclaredMethod("mountSpots"); mountSpots.setAccessible(true);
            spotTarget = spot.getMethod("target"); spotCenter = spot.getMethod("center");
            wrenchItem = Class.forName("com.simibubi.create.content.equipment.wrench.WrenchItem");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) { consoleEntity = null; }
    }
}
