package com.caleb.contraptioncamera.client;

import com.caleb.contraptioncamera.ContraptionCameraMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
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

@Mod(value = ContraptionCameraMod.MOD_ID, dist = Dist.CLIENT)
public class ContraptionCameraClient {
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

    private static final KeyMapping FREELOOK_KEY = new KeyMapping(
            "key.contraptioncamera.freelook",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.contraptioncamera");

    private static boolean active = false;
    private static float targetDistance = INITIAL_DISTANCE;
    private static float smoothedDistance = INITIAL_DISTANCE;
    private static long lastFrameNanos = 0L;
    private static float collisionCap = INITIAL_DISTANCE;
    private static long lastCapNanos = 0L;
    private static float freelookYaw = 0.0f;
    private static float freelookPitch = 0.0f;
    private static long lastFreelookNanos = 0L;

    public ContraptionCameraClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(ContraptionCameraClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ContraptionCameraClient::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(ContraptionCameraClient::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(ContraptionCameraClient::onCameraDistance);
        NeoForge.EVENT_BUS.addListener(ContraptionCameraClient::onComputeCameraAngles);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(FREELOOK_KEY);
    }

    /**
     * Runs before vanilla's {@code Minecraft#handleKeybinds}, so we can consume the
     * perspective key's clicks first and run our own four-state cycle:
     * first person -> third person back -> contraption camera -> third person front.
     */
    static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
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
        mc.player.displayClientMessage(Component.translatable("contraptioncamera.message.enter"), true);
    }

    private static boolean isFreelookHeld(Minecraft mc) {
        return FREELOOK_KEY.isDown()
                && mc.player != null
                && mc.screen == null
                && mc.options.getCameraType() == CameraType.FIRST_PERSON;
    }

    /**
     * Called from {@link com.caleb.contraptioncamera.mixin.MouseHandlerMixin} at the
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
     * Called from {@link com.caleb.contraptioncamera.mixin.CameraMixin} in place of
     * vanilla's {@code Camera#getMaxZoom} collision check. Returns null when the
     * contraption camera is inactive so vanilla logic runs untouched.
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
