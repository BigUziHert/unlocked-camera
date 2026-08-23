package com.caleb.unlockedcamera.client;

import com.caleb.unlockedcamera.UnlockedCameraMod;
import com.caleb.unlockedcamera.mixin.CameraInvoker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

@Mod(value = UnlockedCameraMod.MOD_ID, dist = Dist.CLIENT)
public class UnlockedCameraClient {
    /** Camera distance when first entering the mode. Vanilla third-person is 4. */
    private static final float INITIAL_DISTANCE = 8.0f;
    /** Each scroll notch multiplies/divides the distance by this, so zoom feels uniform at any range. */
    private static final float ZOOM_STEP = 1.15f;
    /** Higher = snappier zoom interpolation (per second). */
    private static final float SMOOTHING_SPEED = 10.0f;
    /** How quickly the camera glides back out after an obstruction pulled it in (per second). */
    private static final float COLLISION_RECOVER_SPEED = 8.0f;
    /** How quickly the view recenters after releasing the freelook key (per second). */
    private static final float FREELOOK_RETURN_SPEED = 12.0f;
    /** Sideways shoulder offset in blocks, and how fast the camera slides between shoulders (per second). */
    private static final float SHOULDER_OFFSET_AMOUNT = 0.75f;
    private static final float SHOULDER_SPEED = 8.0f;

    private static final KeyMapping FREELOOK_KEY = new KeyMapping(
            "key.unlockedcamera.freelook",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.unlockedcamera");

    private static final KeyMapping SWAP_SHOULDER_KEY = new KeyMapping(
            "key.unlockedcamera.swapShoulder",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            "key.categories.unlockedcamera");

    private static boolean active = false;
    private static float targetDistance = INITIAL_DISTANCE;
    private static float smoothedDistance = INITIAL_DISTANCE;
    private static long lastFrameNanos = 0L;
    private static float collisionCap = INITIAL_DISTANCE;
    private static long lastCapNanos = 0L;
    private static float freelookYaw = 0.0f;
    private static float freelookPitch = 0.0f;
    private static long lastFreelookNanos = 0L;
    /** Which shoulder the camera favors when zoomed in: +1 or -1. */
    private static int shoulderSide = 1;
    private static float shoulderOffset = 0.0f;
    /** shoulderOffset after wall clearance, smoothed so passing terrain can't make it snap. */
    private static float clippedShoulderOffset = 0.0f;
    private static long lastShoulderNanos = 0L;



    public UnlockedCameraClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(UnlockedCameraClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onCameraDistance);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onComputeCameraAngles);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(FREELOOK_KEY);
        event.register(SWAP_SHOULDER_KEY);
    }

    /**
     * Runs before vanilla's {@code Minecraft#handleKeybinds}, so we can consume the
     * perspective key's clicks first and run our own four-state cycle:
     * first person -> third person back -> unlocked camera -> third person front.
     */
    static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            active = false;
            return;
        }

        // Always drain the swap-shoulder key so clicks don't queue up while the
        // camera is off; only act on them while it's on.
        while (SWAP_SHOULDER_KEY.consumeClick()) {
            if (active) {
                shoulderSide = -shoulderSide;
            }
        }

        // Unlocked camera disabled in config: leave the perspective key to vanilla.
        if (!ClientConfig.unlockedCameraEnabled()) {
            active = false;
            return;
        }

        // Seated in an Aeronautics/Sable contraption: step aside entirely. Don't
        // consume the perspective key (Sable's camera cycle hooks inside vanilla's
        // key handling, so it must see the clicks) and drop out of our camera.
        if (SableCompat.isRidingSubLevel(mc.player)) {
            active = false;
            return;
        }

        // The camera is in a modded camera type we don't know (e.g. Sable's
        // sub-level views): leave the perspective key alone until it's back to a
        // vanilla view.
        if (!isVanillaCameraType(mc.options.getCameraType())) {
            active = false;
            return;
        }

        while (mc.options.keyTogglePerspective.consumeClick()) {
            cyclePerspective(mc);
        }

        // Something else (another mod, spectator, etc.) changed the view out from under us.
        if (active && mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            active = false;
        }

        if (active) {
            turnPlayerWhileAiming(mc);
        }
    }

    /** How far out the camera ray converges projectile aim, in blocks. */
    private static final float PROJECTILE_AIM_RANGE = 64.0f;

    /**
     * Projectiles fly along the PLAYER's rotation, which the camera-ray pick can't
     * influence. While drawing a bow/crossbow/trident with the shoulder engaged,
     * turn the player toward the camera ray's target so shots land where the
     * center crosshair points.
     */
    private static void turnPlayerWhileAiming(Minecraft mc) {
        if (Math.abs(clippedShoulderOffset) <= 0.01f || mc.level == null || !mc.player.isUsingItem()) {
            return;
        }
        UseAnim anim = mc.player.getUseItem().getUseAnimation();
        if (anim != UseAnim.BOW && anim != UseAnim.CROSSBOW && anim != UseAnim.SPEAR) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        Vector3f forward = camera.getLookVector();
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());
        Vec3 origin = camera.getPosition();
        Vec3 eye = mc.player.getEyePosition();
        double range = PROJECTILE_AIM_RANGE + origin.distanceTo(eye);
        HitResult hit = mc.level.clip(new ClipContext(
                origin, origin.add(direction.scale(range)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

        Vec3 aim = hit.getLocation().subtract(eye);
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        if (aim.length() < 0.5 || horizontal < 1.0E-4) {
            return; // target is basically at the player; keep current rotation
        }
        float yaw = (float) Math.toDegrees(Mth.atan2(aim.z, aim.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Mth.atan2(aim.y, horizontal));
        mc.player.setYRot(yaw);
        mc.player.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
    }

    /** Mods like Sable extend the CameraType enum; only handle the vanilla three. */
    private static boolean isVanillaCameraType(CameraType type) {
        return type == CameraType.FIRST_PERSON
                || type == CameraType.THIRD_PERSON_BACK
                || type == CameraType.THIRD_PERSON_FRONT;
    }

    private static void cyclePerspective(Minecraft mc) {
        if (active) {
            // Third state ends; move on to front-facing third person.
            active = false;
            setCameraType(mc, CameraType.THIRD_PERSON_FRONT);
            return;
        }

        switch (mc.options.getCameraType()) {
            case FIRST_PERSON -> setCameraType(mc, CameraType.THIRD_PERSON_BACK);
            case THIRD_PERSON_BACK -> enterCamera(mc);
            case THIRD_PERSON_FRONT -> setCameraType(mc, CameraType.FIRST_PERSON);
        }
    }

    /** Mirrors what vanilla does when the perspective key changes the camera type. */
    private static void setCameraType(Minecraft mc, CameraType type) {
        CameraType old = mc.options.getCameraType();
        mc.options.setCameraType(type);
        if (old.isFirstPerson() != type.isFirstPerson()) {
            mc.gameRenderer.checkEntityPostEffect(type.isFirstPerson() ? mc.getCameraEntity() : null);
        }
        mc.levelRenderer.needsUpdate();
    }

    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.screen != null || mc.player == null) {
            return;
        }

        double notches = event.getScrollDeltaY();
        if (notches != 0) {
            // Scroll up zooms in, scroll down zooms out.
            targetDistance = Mth.clamp(
                    targetDistance / (float) Math.pow(ZOOM_STEP, notches),
                    ClientConfig.minZoom(), ClientConfig.maxZoom());
        }
        // Swallow the scroll so it doesn't cycle the hotbar.
        event.setCanceled(true);
    }

    static void onCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (!active) {
            return;
        }

        // Ease toward the target distance using real frame time so zoom speed
        // is framerate-independent.
        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0 ? 0.0f : (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;

        float blend = 1.0f - (float) Math.exp(-deltaSeconds * SMOOTHING_SPEED);
        smoothedDistance = Mth.lerp(blend, smoothedDistance, targetDistance);

        event.setDistance(smoothedDistance);
    }

    private static void enterCamera(Minecraft mc) {
        active = true;
        setCameraType(mc, CameraType.THIRD_PERSON_BACK);
        targetDistance = Mth.clamp(INITIAL_DISTANCE, ClientConfig.minZoom(), ClientConfig.maxZoom());
        // Start from vanilla's distance so entering plays a short zoom-out.
        smoothedDistance = 4.0f;
        lastFrameNanos = 0L;
        collisionCap = 4.0f;
        lastCapNanos = 0L;
        shoulderOffset = 0.0f;
        clippedShoulderOffset = 0.0f;
        lastShoulderNanos = 0L;
        if (ClientConfig.showEnterMessage()) {
            mc.player.displayClientMessage(Component.translatable("unlockedcamera.message.enter"), true);
        }
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.GuiMixin}: whether the
     * vanilla CENTER crosshair should draw even though the camera isn't first
     * person. It stays truthful because picking runs along the camera ray while
     * the shoulder offset is engaged (see cameraRayPick).
     */
    public static boolean shouldForceCrosshair() {
        return active && (ClientConfig.crosshairAlways()
                || (ClientConfig.crosshairOnShoulderOffset() && Math.abs(shoulderOffset) > 0.01f));
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.GameRendererMixin} in place
     * of vanilla's pick while the shoulder offset is engaged: raycast from the
     * CAMERA through the center crosshair, so whatever sits under the crosshair is
     * what gets targeted — mining, attacks, and block placement all use this
     * result. Returns null (vanilla pick) otherwise.
     *
     * <p>The ray is extended by the camera's setback behind the player, and the
     * result is then validated against the player's own interaction ranges
     * (mirroring vanilla's filterHitResult) so the server never rejects the action.
     */
    public static HitResult cameraRayPick(Entity entity, double blockInteractionRange, double entityInteractionRange, float partialTick) {
        if (!active || Math.abs(clippedShoulderOffset) <= 0.01f) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (mc.level == null || !camera.isInitialized()) {
            return null;
        }

        Vec3 playerEye = entity.getEyePosition(partialTick);
        Vec3 origin = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());

        double setback = origin.distanceTo(playerEye);
        double maxRange = Math.max(blockInteractionRange, entityInteractionRange) + setback;

        Vec3 end = origin.add(direction.scale(maxRange));
        HitResult blockHit = mc.level.clip(new ClipContext(
                origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
        double blockDistSqr = blockHit.getType() != HitResult.Type.MISS
                ? blockHit.getLocation().distanceToSqr(origin)
                : Mth.square(maxRange);

        // Entities, like vanilla: search only up to the first block hit.
        double entitySearch = Math.sqrt(blockDistSqr);
        Vec3 entityEnd = origin.add(direction.scale(entitySearch));
        AABB searchBox = new AABB(origin, entityEnd).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                entity, origin, entityEnd, searchBox,
                target -> !target.isSpectator() && target.isPickable(), blockDistSqr);

        return entityHit != null && entityHit.getLocation().distanceToSqr(origin) < blockDistSqr
                ? filterToPlayerRange(entityHit, playerEye, entityInteractionRange)
                : filterToPlayerRange(blockHit, playerEye, blockInteractionRange);
    }

    /** Vanilla's filterHitResult, but measured from the PLAYER's eye, not the ray origin. */
    private static HitResult filterToPlayerRange(HitResult hit, Vec3 playerEye, double range) {
        Vec3 location = hit.getLocation();
        if (!location.closerThan(playerEye, range)) {
            Direction direction = Direction.getNearest(
                    location.x - playerEye.x, location.y - playerEye.y, location.z - playerEye.z);
            return BlockHitResult.miss(location, direction, BlockPos.containing(location));
        }
        return hit;
    }

    private static boolean isFreelookHeld(Minecraft mc) {
        return ClientConfig.freelookEnabled()
                && FREELOOK_KEY.isDown()
                && mc.player != null
                && mc.screen == null
                && mc.options.getCameraType() == CameraType.FIRST_PERSON;
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.MouseHandlerMixin} at the
     * top of {@code MouseHandler#turnPlayer}. While freelooking, accumulates the
     * mouse movement into a camera-only yaw/pitch offset and returns true so the
     * player entity itself doesn't turn.
     */
    public static boolean freelookMouseTurn(double accumulatedDX, double accumulatedDY) {
        Minecraft mc = Minecraft.getInstance();
        if (!isFreelookHeld(mc)) {
            return false;
        }

        // Vanilla's sensitivity curve (MouseHandler#turnPlayer), including the 0.15
        // factor Entity#turn applies, so freelook feels identical to normal look.
        double d = mc.options.sensitivity().get() * 0.6 + 0.2;
        double cubed = d * d * d;
        double scale = (mc.player.isScoping() ? cubed : cubed * 8.0) * 0.15;
        double invert = mc.options.invertYMouse().get() ? -1.0 : 1.0;

        float yawLimit = ClientConfig.freelookYawLimit();
        freelookYaw = Mth.clamp(freelookYaw + (float) (accumulatedDX * scale), -yawLimit, yawLimit);
        // Clamp so the *final* pitch stays within vanilla's straight-up/down limits.
        float basePitch = mc.player.getXRot();
        freelookPitch = Mth.clamp(freelookPitch + (float) (accumulatedDY * scale * invert),
                -90.0f - basePitch, 90.0f - basePitch);
        return true;
    }

    static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            freelookYaw = 0.0f;
            freelookPitch = 0.0f;
            return;
        }
        applyFreelook(event, mc);
    }

    private static void applyFreelook(ViewportEvent.ComputeCameraAngles event, Minecraft mc) {
        long now = System.nanoTime();
        float deltaSeconds = lastFreelookNanos == 0 ? 0.0f : (now - lastFreelookNanos) / 1_000_000_000.0f;
        lastFreelookNanos = now;

        if (!isFreelookHeld(mc)) {
            if (freelookYaw == 0.0f && freelookPitch == 0.0f) {
                return;
            }
            // Key released: ease the view back to where the player actually looks.
            float blend = 1.0f - (float) Math.exp(-deltaSeconds * FREELOOK_RETURN_SPEED);
            freelookYaw = Mth.lerp(blend, freelookYaw, 0.0f);
            freelookPitch = Mth.lerp(blend, freelookPitch, 0.0f);
            if (Math.abs(freelookYaw) < 0.05f && Math.abs(freelookPitch) < 0.05f) {
                freelookYaw = 0.0f;
                freelookPitch = 0.0f;
                return;
            }
        }

        float partialTick = (float) event.getPartialTick();
        event.setYaw(mc.player.getViewYRot(partialTick) + freelookYaw);
        event.setPitch(Mth.clamp(mc.player.getViewXRot(partialTick) + freelookPitch, -90.0f, 90.0f));
    }

    /**
     * Over-the-shoulder offset while zoomed in close. Eases in below the configured
     * distance (fading out over one block above it), slides sides on the swap key,
     * and clips sideways so it can't push the camera into a wall.
     *
     * <p>Called from {@link com.caleb.unlockedcamera.mixin.CameraMixin} at the END
     * of {@code Camera#setup}, once the camera's final position is known — position
     * changes made any earlier get overwritten by vanilla.
     */
    public static void applyShoulderOffset(Camera camera, boolean detached, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!detached || !active || mc.player == null) {
            shoulderOffset = 0.0f;
            clippedShoulderOffset = 0.0f;
            lastShoulderNanos = 0L;
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastShoulderNanos == 0 ? 0.0f : (now - lastShoulderNanos) / 1_000_000_000.0f;
        lastShoulderNanos = now;

        float target = 0.0f;
        if (ClientConfig.shoulderOffsetEnabled()) {
            // Gate on the zoom the player has chosen (targetDistance), not the
            // animated or collision-limited distance: the enter animation sweeps
            // through close distances and walls push the camera in, and neither
            // should flash the offset on.
            float fade = Mth.clamp(ClientConfig.shoulderOffsetMaxZoom() + 1.0f - targetDistance, 0.0f, 1.0f);
            target = shoulderSide * SHOULDER_OFFSET_AMOUNT * fade;
        }

        float blend = 1.0f - (float) Math.exp(-deltaSeconds * SHOULDER_SPEED);
        shoulderOffset = Mth.lerp(blend, shoulderOffset, target);
        if (Math.abs(shoulderOffset) < 0.005f) {
            shoulderOffset = 0.0f;
            clippedShoulderOffset = 0.0f;
            return;
        }

        float allowed = shoulderOffset;
        if (mc.level != null) {
            // Clip along the offset direction, keeping a small margin off walls.
            // Camera#move's dx runs along camera-local +X, which is camera-RIGHT
            // (the left vector is -X), so positive offsets travel opposite to it.
            Vector3f left = camera.getLeftVector();
            Vec3 from = camera.getPosition();
            Vec3 direction = new Vec3(-left.x(), -left.y(), -left.z()).scale(Math.signum(shoulderOffset));
            Vec3 to = from.add(direction.scale(Math.abs(shoulderOffset) + 0.1));
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                float clearance = (float) Math.max(0.0, hit.getLocation().distanceTo(from) - 0.1);
                allowed = Math.min(Math.abs(shoulderOffset), clearance) * Math.signum(shoulderOffset);
            }
        }

        // Blocked: pull in instantly so the camera never sits inside a wall.
        // Freed: glide back out — otherwise walking past uneven terrain makes the
        // offset snap in and out every time a block grazes the clearance ray.
        if (Math.abs(allowed) < Math.abs(clippedShoulderOffset)
                && Math.signum(allowed) == Math.signum(clippedShoulderOffset)) {
            clippedShoulderOffset = allowed;
        } else {
            float clipBlend = 1.0f - (float) Math.exp(-deltaSeconds * COLLISION_RECOVER_SPEED);
            clippedShoulderOffset = Mth.lerp(clipBlend, clippedShoulderOffset, allowed);
        }

        ((CameraInvoker) camera).unlockedcamera$move(0.0f, 0.0f, clippedShoulderOffset);
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.CameraMixin} in place of
     * vanilla's {@code Camera#getMaxZoom} collision check. Returns null when the
     * unlocked camera is inactive so vanilla logic runs untouched.
     *
     * <p>Same collision as vanilla, except an obstruction only pulls the camera in
     * instantly (so it can't clip inside blocks); once the obstruction clears, the
     * camera glides back out smoothly instead of snapping.
     */
    public static Float overrideMaxZoom(BlockGetter level, Entity entity, Vec3 position, Vector3f forwards, float desired) {
        if (!active) {
            return null;
        }

        // Vanilla's 8-point raycast (one from each corner of a small box around the
        // focus point).
        float limit = desired;
        for (int i = 0; i < 8; i++) {
            float ox = ((i & 1) * 2 - 1) * 0.1f;
            float oy = ((i >> 1 & 1) * 2 - 1) * 0.1f;
            float oz = ((i >> 2 & 1) * 2 - 1) * 0.1f;
            Vec3 from = position.add(ox, oy, oz);
            Vec3 to = new Vec3(
                    position.x - forwards.x() * desired + ox,
                    position.y - forwards.y() * desired + oy,
                    position.z - forwards.z() * desired + oz);
            HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.MISS) {
                float d = (float) hit.getLocation().distanceTo(position);
                if (d < limit) {
                    limit = d;
                }
            }
        }

        long now = System.nanoTime();
        float deltaSeconds = lastCapNanos == 0 ? 0.0f : (now - lastCapNanos) / 1_000_000_000.0f;
        lastCapNanos = now;

        if (limit <= collisionCap) {
            // Obstructed (or user zoomed in): move in immediately so the camera
            // never sits inside a block.
            collisionCap = limit;
        } else {
            // Obstruction cleared (or user zoomed out): glide back out.
            float blend = 1.0f - (float) Math.exp(-deltaSeconds * COLLISION_RECOVER_SPEED);
            collisionCap = Mth.lerp(blend, collisionCap, limit);
        }
        return collisionCap;
    }
}
