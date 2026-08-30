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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.util.Mth;
import net.minecraft.util.SmoothDouble;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
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
    /** Vanilla fixed third-person camera distance; the unlocked camera enters at it. */
    private static final float VANILLA_DISTANCE = 4.0f;
    /** Each scroll notch multiplies/divides the distance by this, so zoom feels uniform at any range. */
    private static final float ZOOM_STEP = 1.15f;
    /** Higher = snappier zoom interpolation (per second). */
    private static final float SMOOTHING_SPEED = 10.0f;
    /** How quickly the camera glides back out after an obstruction pulled it in (per second). */
    private static final float COLLISION_RECOVER_SPEED = 8.0f;
    /** How quickly the view recenters after releasing the freelook key (per second). */
    private static final float FREELOOK_RETURN_SPEED = 12.0f;
    /** The gentler recenter rate while the cinematic camera smooths everything
     * else — the normal snap reads as a jolt against its heavy easing. */
    private static final float CINEMATIC_FREELOOK_RETURN_SPEED = 2.0f;
    /** How fast the camera slides between shoulders (per second). */
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
    /** We were in the unlocked camera when a Sable seat took over; resume on dismount. */
    private static boolean resumeAfterSeat = false;
    /** The view during the last seated tick — tells a deliberate in-seat view
     * choice apart from Sable's own dismount behavior. */
    private static CameraType lastSeatCameraType = null;
    private static float targetDistance = VANILLA_DISTANCE;
    private static float smoothedDistance = VANILLA_DISTANCE;
    private static long lastFrameNanos = 0L;
    private static float collisionCap = VANILLA_DISTANCE;
    private static long lastCapNanos = 0L;
    private static float freelookYaw = 0.0f;
    private static float freelookPitch = 0.0f;
    private static long lastFreelookNanos = 0L;
    /** Which shoulder the camera favors when zoomed in: +1 or -1. */
    private static int shoulderSide = 1;
    private static float shoulderOffset = 0.0f;
    /** Wall clearance (magnitude) on the current side: drops instantly, releases smoothly. */
    private static float shoulderClearance = 0.75f;
    private static float lastClearanceSign = 0.0f;
    private static long lastShoulderNanos = 0L;



    public UnlockedCameraClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(UnlockedCameraClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onClientTickPre);
        // LOW priority: mods with scroll interactions (e.g. Create's value boxes and
        // contraption controls) cancel the scroll event when they handle it, and
        // they should win over zoom - we only see scrolls nobody else claimed.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, UnlockedCameraClient::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onCameraDistance);
        NeoForge.EVENT_BUS.addListener(UnlockedCameraClient::onComputeCameraAngles);
        // receiveCanceled: Create cancels this event for its own click claims
        // (contraption controls, curved tracks). The keep-direction snap must
        // still commit the freelook deflection on those clicks — the
        // interaction itself already happened where the player looked.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, true,
                InputEvent.InteractionKeyMappingTriggered.class,
                UnlockedCameraClient::onInteractionKeyTriggered);
    }

    /**
     * Clicking during freelook while Keep Freelook Direction is on: snap the
     * player's body to where the freelook camera points (the view itself
     * doesn't move — the offset collapses into the real rotation), then
     * refresh targeting so this very click lands on the crosshair. With the
     * option off, clicks interact along the body's own facing, as plain
     * freelook implies.
     */
    static void onInteractionKeyTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        // Camera-entity guard: clicking while spectating another entity must
        // not snap the detached player's body to a leftover deflection.
        if (mc.player == null || !ClientConfig.freelookKeepDirection()
                || mc.getCameraEntity() != mc.player) {
            return;
        }
        // Pick-block already follows the crosshair; middle-click shouldn't
        // commit the freelook direction.
        if (event.isPickBlock()) {
            return;
        }
        if (Math.abs(freelookYaw) < 0.5f && Math.abs(freelookPitch) < 0.5f) {
            return;
        }

        snapPlayerToFreelook(mc);

        // hitResult was computed from the old rotation at the start of the tick;
        // recompute so the pending interaction uses the snapped aim.
        mc.gameRenderer.pick(1.0f);
    }

    /** Collapse the freelook offset into the player's real rotation; the view doesn't move. */
    private static void snapPlayerToFreelook(Minecraft mc) {
        float newYaw = mc.player.getYRot() + freelookYaw;
        float newPitch = Mth.clamp(mc.player.getXRot() + freelookPitch, -90.0f, 90.0f);
        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);
        // Also set the previous-tick rotations so the frame doesn't interpolate
        // through the swing while the freelook offset simultaneously collapses.
        mc.player.yRotO = newYaw;
        mc.player.xRotO = newPitch;
        // The first-person hand sways by the gap between the rotation and these
        // eased "bob" angles; sync them so the hand doesn't lurch after a snap.
        mc.player.yBob = newYaw;
        mc.player.yBobO = newYaw;
        mc.player.xBob = newPitch;
        mc.player.xBobO = newPitch;
        freelookYaw = 0.0f;
        freelookPitch = 0.0f;
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
            resumeAfterSeat = false;
            // Forget any freelook deflection too: with Keep Freelook Direction
            // on, a stale offset would snap the body to it on the next join.
            freelookYaw = 0.0f;
            freelookPitch = 0.0f;
            lastFreelookNanos = 0L;
            dropHeldAim();
            return;
        }

        // Always drain the swap-shoulder key so clicks don't queue up while the
        // camera is off; only act on them while it's on.
        while (SWAP_SHOULDER_KEY.consumeClick()) {
            if (active) {
                shoulderSide = -shoulderSide;
            }
        }

        // Master switch off: every feature stands down. A pending seat resume
        // dies with it — letting it survive meant a forced camera switch firing
        // whenever the switch came back on, however much later. The server may
        // still hold a stamped aim, so tell it the true rotation on the way out.
        if (!ClientConfig.modEnabled()) {
            active = false;
            resumeAfterSeat = false;
            lastSeatCameraType = null;
            releaseHeldAim(mc);
            return;
        }

        // Seated in an Aeronautics/Sable contraption: step aside entirely. Don't
        // consume the perspective key (Sable's camera cycle hooks inside vanilla's
        // key handling, so it must see the clicks) and drop out of our camera —
        // remembering to resume it once the player dismounts.
        if (SableCompat.isRidingSubLevel(mc.player)) {
            if (active) {
                resumeAfterSeat = true;
                active = false;
            }
            lastSeatCameraType = mc.options.getCameraType();
            dropHeldAim();
            return;
        }

        // Spectator: step aside like the Sable seat. Don't consume the
        // perspective key (vanilla's own cycle handles spectating) and don't
        // stay active, so scrolling keeps adjusting fly speed and the
        // spectator menu instead of being swallowed as zoom.
        if (mc.player.isSpectator()) {
            active = false;
            // Going spectator while seated dismounts server-side without a
            // normal dismount tick, so a pending seat resume would survive all
            // of spectator and force a camera switch on exit — minutes or
            // hours later. Kill it like the master-off path does.
            resumeAfterSeat = false;
            lastSeatCameraType = null;
            dropHeldAim();
            return;
        }

        if (!ClientConfig.unlockedCameraEnabled()) {
            // Fourth camera disabled in config: leave the perspective key to
            // vanilla, but fall through to the aim hold below — freelook works
            // without the camera, its deflection still drives picking and the
            // UseItem rewrite, and without the hold a crossbow shot fired
            // mid-freelook flies along the head rotation, a tick behind.
            active = false;
            // A pending seat resume must not survive into the disabled state
            // either — it would force a camera switch on re-enable.
            resumeAfterSeat = false;
            lastSeatCameraType = null;
        } else {
            // Dismounted from a Sable seat. What the seat's view cycle ended on
            // decides the view now: Sable's own contraption camera exits to a first
            // person the player never chose, so that (or the vanilla back view)
            // resumes the unlocked camera at the pre-seat zoom — while first person
            // or the front view chosen deliberately in the seat is kept. Wait out
            // any lingering Sable camera type first.
            if (resumeAfterSeat && isVanillaCameraType(mc.options.getCameraType())) {
                resumeAfterSeat = false;
                if (lastSeatCameraType == null || !isVanillaCameraType(lastSeatCameraType)
                        || lastSeatCameraType == CameraType.THIRD_PERSON_BACK) {
                    active = true;
                    setCameraType(mc, CameraType.THIRD_PERSON_BACK);
                    // The easing timestamps still hold pre-seat times; reset them
                    // so the first frame back doesn't blend across the whole seat
                    // and resume behaves the same after any seat duration.
                    lastFrameNanos = 0L;
                    lastCapNanos = 0L;
                    lastShoulderNanos = 0L;
                    // Resume the way F5 enters: start at vanilla's distance and
                    // glide out to the pre-seat zoom instead of snapping there.
                    smoothedDistance = VANILLA_DISTANCE;
                    collisionCap = VANILLA_DISTANCE * cameraEntityScale(mc.player);
                }
                lastSeatCameraType = null;
            }

            // The camera is in a modded camera type we don't know (e.g. Sable's
            // sub-level views): leave the perspective key alone until it's back to a
            // vanilla view.
            if (!isVanillaCameraType(mc.options.getCameraType())) {
                active = false;
                releaseHeldAim(mc);
                return;
            }

            while (mc.options.keyTogglePerspective.consumeClick()) {
                cyclePerspective(mc);
            }

            // Vanilla third person shouldn't be reachable while it's disabled. If
            // the option was turned on while already standing in it, step into the
            // unlocked camera immediately instead of waiting for an F5 press.
            if (!active && ClientConfig.disableVanillaThirdPerson()
                    && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
                enterCamera(mc);
            }

            // Live edits to the zoom sliders take effect right away, not on the
            // next scroll.
            targetDistance = Mth.clamp(targetDistance, ClientConfig.minZoom(), ClientConfig.maxZoom());
        }

        // Keep the server's rotation glued to the crosshair while a ranged or
        // thrown item is held. Rotation only reaches the server inside movement
        // packets, vanilla only sends one when the BODY turned, and the head —
        // which crossbows fire along — lags a further tick behind, so syncing
        // at click time is always one tick too late. Holding it continuously
        // means any click fires true. The client body is never touched.
        //
        // Computed ONCE per tick and cached for the packet rewrites: on a Sable
        // sublevel each raycast runs their plot-space tracing, and per-packet
        // recomputation dropped the game to a slideshow. The extra rotation
        // sends are throttled for the same reason — Sable also does
        // contraption-relative work per movement packet.
        // During freelook the aim is held with ANY item: the head visibly
        // tracks the deflected view for other players, and shots land true.
        float[] freshAim = (holdingRangedItem(mc) || freelookDeflected(mc))
                ? crosshairAimAngles() : null;
        boolean hadAim = cachedHeldAim != null;
        cachedHeldAim = freshAim;
        heldAimTicks++;
        if (freshAim == null) {
            if (hadAim) {
                // The hold just ended (item put away, freelook released — or
                // zeroed outright by an F5 mid-freelook). The server still has
                // the stamped aim and vanilla won't send rotation until the
                // body next turns, so tell it the true rotation once. Clear
                // the cache FIRST so this packet passes the rewrite untouched.
                dropHeldAim();
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                            mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
                }
            }
        } else if (mc.getConnection() != null
                && heldAimTicks - lastAimSendTick >= 2
                && (lastSentAim == null
                        || Math.abs(Mth.wrapDegrees(freshAim[0] - lastSentAim[0])) > 0.25f
                        || Math.abs(freshAim[1] - lastSentAim[1]) > 0.25f)) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    freshAim[0], freshAim[1], mc.player.onGround()));
            lastSentAim = freshAim;
            lastAimSendTick = heldAimTicks;
        }

        // A use packet was rewritten while no hold was running (eating,
        // blocking, a spyglass — with the crosshair aim active): the server
        // has the stamped aim and nothing else will correct it, so send the
        // true rotation right back. Skipped if a hold started this tick — the
        // hold owns the rotation then.
        if (aimResyncRequested) {
            aimResyncRequested = false;
            if (cachedHeldAim == null && mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
            }
        }

        // Something else (another mod, spectator, etc.) changed the view out from under us.
        if (active && mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            active = false;
        }

        if (active) {
            turnPlayerWhileAiming(mc);
        }
    }

    /** Closest aim target the body still turns for; nearer than this the
     * required swing is too extreme to hold against Better Third Person and the
     * body just thrashes. Below it the shot is left alone, as vanilla would. */
    private static final float MIN_AIM_TARGET_DISTANCE = 4.0f;
    /** Most the aimed body rotation may change in one tick, damping any
     * aim/camera/body feedback oscillation into a convergence. */
    private static final float MAX_AIM_STEP = 15.0f;
    /** Corrections smaller than this are not worth applying. */
    private static final float AIM_DEADBAND = 0.25f;
    /** Beyond this pitch the body is left alone: rotating it that steeply is a
     * fight with Better Third Person and the body flips between the two. The
     * shot itself is still corrected on the wire, so only the cosmetic turn is
     * given up where it cannot be won. */
    private static final float MAX_AIM_PITCH = 70.0f;

    /** How far out the camera ray converges projectile aim, in blocks. */
    private static final float PROJECTILE_AIM_RANGE = 64.0f;

    /**
     * Whether the shoulder system is meant to be on right now, based on the
     * player's chosen zoom — NOT the instantaneous slide position, which passes
     * through zero mid-swap and would make gates flicker.
     */
    private static boolean shoulderEngaged() {
        return active && ClientConfig.shoulderOffsetEnabled()
                && ClientConfig.shoulderOffsetMaxZoom() + 1.0f - targetDistance > 0.0f;
    }

    /** First-person freelook is deflecting the view away from the body. */
    private static boolean freelookDeflected(Minecraft mc) {
        return (Math.abs(freelookYaw) > 0.5f || Math.abs(freelookPitch) > 0.5f)
                && mc.options.getCameraType().isFirstPerson();
    }

    /**
     * The centered crosshair defines aim independently of the body: the
     * shoulder camera is engaged, or first-person freelook is deflecting the
     * view. Targeting, other mods' raycasts, and projectile correction all
     * follow the camera in both states — in first person the camera sits at
     * the eye, so the shoulder machinery reduces to plain forward rays.
     */
    private static boolean crosshairAimActive() {
        Minecraft mc = Minecraft.getInstance();
        return shoulderEngaged() || (mc.player != null && freelookDeflected(mc));
    }

    /**
     * Projectiles fly along the PLAYER's rotation. The packet layer corrects
     * the shot itself at any distance (see ClientPacketListenerMixin); this
     * turn is the cosmetic half — while drawing a bow/crossbow/trident, face
     * the body toward the crosshair target so the pose reads right. Close
     * targets are left alone: the swing they need cannot be held against
     * Better Third Person and the body just thrashes.
     */
    private static void turnPlayerWhileAiming(Minecraft mc) {
        if (!shoulderEngaged() || mc.level == null || !mc.player.isUsingItem()) {
            return;
        }
        UseAnim anim = mc.player.getUseItem().getUseAnimation();
        if (anim != UseAnim.BOW && anim != UseAnim.CROSSBOW && anim != UseAnim.SPEAR) {
            return;
        }
        float[] aim = continuousAimAngles(); // the tick's cached ray — never recompute it
        if (aim == null || aim[2] < MIN_AIM_TARGET_DISTANCE || Math.abs(aim[1]) > MAX_AIM_PITCH) {
            return;
        }
        // Approach in damped steps via the nearest-equivalent yaw: yaw
        // accumulates instead of wrapping, and the body/camera/aim feedback
        // loop oscillates near straight up/down if snapped.
        float yawDelta = Mth.wrapDegrees(aim[0] - mc.player.getYRot());
        float pitchDelta = aim[1] - mc.player.getXRot();
        if (Math.abs(yawDelta) < AIM_DEADBAND && Math.abs(pitchDelta) < AIM_DEADBAND) {
            return;
        }
        mc.player.setYRot(mc.player.getYRot() + Mth.clamp(yawDelta, -MAX_AIM_STEP, MAX_AIM_STEP));
        mc.player.setXRot(Mth.clamp(
                mc.player.getXRot() + Mth.clamp(pitchDelta, -MAX_AIM_STEP, MAX_AIM_STEP), -90.0f, 90.0f));
    }

    /**
     * The yaw/pitch the player must face for a projectile fired from their eyes
     * to land where the centered crosshair points, or null while the crosshair aim is inactive (shoulder offset off, no freelook deflection). Returns {yaw, pitch, distance from eye to target}.
     */
    public static float[] crosshairAimAngles() {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.level == null || mc.player == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }

        Vector3f forward = camera.getLookVector();
        if (!shoulderEngaged()) {
            // First-person freelook: the camera sits at the eye, so the exact
            // aim is simply the view direction — no parallax, no raycast. There
            // is no ray target here, so the distance slot reports the nominal
            // far range to keep the {yaw, pitch, distance} contract intact.
            double flHorizontal = Math.sqrt(forward.x() * forward.x() + forward.z() * forward.z());
            float flRawYaw = (float) Math.toDegrees(Mth.atan2(forward.z(), forward.x())) - 90.0f;
            float flCurrentYaw = mc.player.getYRot();
            return new float[] {
                    flCurrentYaw + Mth.wrapDegrees(flRawYaw - flCurrentYaw),
                    Mth.clamp((float) -Math.toDegrees(Mth.atan2(forward.y(), Math.max(flHorizontal, 1.0E-4))),
                            -90.0f, 90.0f),
                    PROJECTILE_AIM_RANGE};
        }
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());
        Vec3 origin = camera.getPosition();
        Vec3 eye = mc.player.getEyePosition();
        double range = PROJECTILE_AIM_RANGE + origin.distanceTo(eye);
        double playerDepth = Math.max(0.0, eye.subtract(origin).dot(direction));
        Vec3 end = origin.add(direction.scale(range));
        // COLLIDER, not OUTLINE: projectiles fly through non-colliding blocks
        // (tall grass, flowers), so aim must ignore them too. The gap walk keeps
        // blocks between the camera and the player from becoming the target.
        HitResult hit = gapWalkClip(origin, direction, end, playerDepth,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);

        Vec3 aimPoint = hit.getLocation();
        // Sable plot-space hit: recover the true along-ray point FIRST — the
        // entity sweep and the aim both need it. Raw plot coordinates read as
        // huge distances, which left the sweep unbounded (an occluded mob beat
        // the hull in front of it) and the old post-sweep recovery always took
        // the far sphere root, over-shooting near hull surfaces by up to a
        // couple of blocks when looking down.
        boolean plotSpace = hit.getType() != HitResult.Type.MISS
                && aimPoint.distanceToSqr(origin) > Mth.square(range + 1.0);
        if (plotSpace) {
            double t = plotSpaceAlongRay(hit, origin, direction, mc.player, playerDepth);
            aimPoint = origin.add(direction.scale(t > 0.0 ? Math.min(t, range) : range));
        }

        // A mob standing under the crosshair is the target, not the backdrop
        // behind it: sweep entities along the ray out to the block hit, the
        // same way interaction targeting does.
        double blockGeomSqr = hit.getType() != HitResult.Type.MISS
                ? aimPoint.distanceToSqr(origin) // plot hits already recovered above
                : Mth.square(range);
        double entitySearch = Math.min(Math.sqrt(blockGeomSqr), range);
        Vec3 sweepStart = origin.add(direction.scale(Math.min(playerDepth, entitySearch)));
        Vec3 entityEnd = origin.add(direction.scale(entitySearch));
        EntityHitResult entityAim = ProjectileUtil.getEntityHitResult(
                mc.player, sweepStart, entityEnd,
                new AABB(sweepStart, entityEnd).inflate(1.0),
                target -> !target.isSpectator() && target.isPickable(), Mth.square(entitySearch));
        if (entityAim != null) {
            // Nudge toward the entity's centre so spread can't graze past.
            Vec3 entityPoint = entityAim.getLocation();
            Vec3 toBody = entityAim.getEntity().getBoundingBox().getCenter().subtract(entityPoint);
            double toBodyLen = toBody.length();
            if (toBodyLen > 1.0E-4) {
                entityPoint = entityPoint.add(toBody.scale(Math.min(0.2, toBodyLen * 0.35) / toBodyLen));
            }
            aimPoint = entityPoint;
        } else if (!plotSpace && hit instanceof BlockHitResult buriedHit && hit.getType() == HitResult.Type.BLOCK) {
            // Bury the aim point toward the struck block's CENTRE (capped). A
            // hit on an edge or corner is a graze — the random projectile spread
            // then decides each shot, with misses sailing far past. Pulling the
            // point into the block's meat makes grazes stick where the crosshair
            // touches; for face-centre hits the shift is invisible.
            Vec3 toCenter = Vec3.atCenterOf(buriedHit.getBlockPos()).subtract(aimPoint);
            double toCenterLen = toCenter.length();
            if (toCenterLen > 1.0E-4) {
                aimPoint = aimPoint.add(toCenter.scale(Math.min(0.2, toCenterLen * 0.35) / toCenterLen));
            }
        }

        Vec3 aim = aimPoint.subtract(eye);
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        if (aim.length() < 0.5 || horizontal < 1.0E-4) {
            return null; // target is basically at the player; keep current rotation
        }
        float rawYaw = (float) Math.toDegrees(Mth.atan2(aim.z, aim.x)) - 90.0f;
        float currentYaw = mc.player.getYRot();
        float yaw = currentYaw + Mth.wrapDegrees(rawYaw - currentYaw);
        float pitch = (float) -Math.toDegrees(Mth.atan2(aim.y, horizontal));
        return new float[] {yaw, Mth.clamp(pitch, -90.0f, 90.0f), (float) aim.length()};
    }

    private static float[] lastSentAim;
    private static float[] cachedHeldAim;
    private static long heldAimTicks;
    private static long lastAimSendTick;

    /**
     * Forget the held aim on any tick path that skips recomputing it. The
     * packet rewrite reads the cache unconditionally, so a stale value would
     * keep stamping an old direction into every outgoing rotation — while
     * seated in a Sable contraption, or with the mod toggled off entirely.
     * Also forget what was last sent, so resuming re-syncs the server even if
     * the fresh aim happens to match the stale one.
     */
    private static void dropHeldAim() {
        cachedHeldAim = null;
        lastSentAim = null;
        // Rewind the throttle too, so the first tick of a resumed hold sends
        // instead of losing the very race the hold exists to win.
        lastAimSendTick = heldAimTicks - 2;
    }

    /**
     * Drop the held aim AND tell the server the true rotation once — for the
     * stand-down paths where the server still holds a stamped aim and vanilla
     * won't send rotation again until the body next turns.
     */
    private static void releaseHeldAim(Minecraft mc) {
        boolean hadAim = cachedHeldAim != null;
        dropHeldAim();
        if (hadAim && mc.player != null && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
        }
    }

    /** Set when a use packet was aim-rewritten with no hold running (food, a
     * shield — anything unranged used while the crosshair aim is active): the
     * stamp would persist server-side with no hold to end and correct it. */
    private static boolean aimResyncRequested;

    public static void requestAimResync() {
        aimResyncRequested = true;
    }

    /**
     * The crosshair aim to hold the server's rotation at while a ranged or
     * thrown item is in hand — null otherwise, letting rotation flow normally.
     * Cached once per tick by the tick handler; packet rewrites read it free.
     */
    public static float[] continuousAimAngles() {
        return cachedHeldAim;
    }

    private static boolean holdingRangedItem(Minecraft mc) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            UseAnim anim = stack.getUseAnimation();
            if (anim == UseAnim.BOW || anim == UseAnim.CROSSBOW || anim == UseAnim.SPEAR
                    || stack.getItem() instanceof ProjectileItem
                    // EnderpearlItem does NOT implement ProjectileItem in
                    // 1.21.1 — this clause alone enables the hold for pearls.
                    || stack.getItem() instanceof EnderpearlItem) {
                return true;
            }
        }
        return false;
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
            case FIRST_PERSON -> {
                // With the unlocked camera entering at vanilla distance, the two
                // behind views read as duplicates; optionally skip the vanilla one.
                if (ClientConfig.disableVanillaThirdPerson()) {
                    enterCamera(mc);
                } else {
                    setCameraType(mc, CameraType.THIRD_PERSON_BACK);
                }
            }
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
        // The spectator check covers the frames between a gamemode switch and
        // the next tick (which drops active): spectator fly-speed and menu
        // scrolling both run after this event, and must not be swallowed.
        if (!active || mc.screen != null || mc.player == null || mc.player.isSpectator()) {
            return;
        }

        // Only vertical scroll zooms (and only vertical scroll cycles the
        // hotbar) — horizontal-only scroll events are left for other mods.
        // Cancelling a mixed event does swallow its horizontal delta too;
        // the event has no per-axis consume.
        double notches = event.getScrollDeltaY();
        if (notches != 0) {
            // Scroll up zooms in, scroll down zooms out.
            targetDistance = Mth.clamp(
                    targetDistance / (float) Math.pow(ZOOM_STEP, notches),
                    ClientConfig.minZoom(), ClientConfig.maxZoom());
            // Swallow the scroll so it doesn't cycle the hotbar.
            event.setCanceled(true);
        }
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

    /**
     * Vanilla scales every third-person camera distance by the entity's
     * generic.scale attribute, and this mod keeps that convention: configured
     * distances mean blocks at normal size. The factor is needed wherever the
     * scale-relative model meets real world-space geometry — the collision
     * cap's seeds and the shoulder move/clearance.
     */
    private static float cameraEntityScale(Entity entity) {
        return entity instanceof LivingEntity living ? living.getScale() : 1.0f;
    }

    private static void enterCamera(Minecraft mc) {
        active = true;
        setCameraType(mc, CameraType.THIRD_PERSON_BACK);
        targetDistance = Mth.clamp(VANILLA_DISTANCE, ClientConfig.minZoom(), ClientConfig.maxZoom());
        // Enter at vanilla's distance; when the configured zoom differs the
        // camera glides out to it from here (settled by design).
        smoothedDistance = VANILLA_DISTANCE;
        lastFrameNanos = 0L;
        // The cap lives in getMaxZoom's POST-entity-scale space (smoothedDistance
        // is pre-scale by the event contract); seeding it unscaled made a
        // scaled-up player enter at half vanilla's distance and glide wrong.
        collisionCap = VANILLA_DISTANCE * cameraEntityScale(mc.player);
        lastCapNanos = 0L;
        shoulderOffset = 0.0f;
        shoulderClearance = ClientConfig.shoulderOffsetAmount();
        lastClearanceSign = 0.0f;
        lastShoulderNanos = 0L;
        if (ClientConfig.showEnterMessage()) {
            mc.player.displayClientMessage(Component.translatable("unlockedcamera.message.enter"), true);
        }
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.GuiMixin}: whether the
     * vanilla CENTER crosshair should draw even though the camera isn't first
     * person. While the shoulder offset is engaged it stays truthful, because
     * picking runs along the camera ray (see cameraRayPick); with
     * crosshairAlways, beyond the shoulder gate it merely draws — picking and
     * aim follow the body's facing there.
     */
    public static boolean shouldForceCrosshair() {
        return active && (ClientConfig.crosshairAlways()
                || (ClientConfig.crosshairOnShoulderOffset() && shoulderEngaged()));
    }

    /**
     * Clip along the crosshair ray, skipping blocks the ray fully EXITS before
     * reaching the player's depth — those sit wholly between the camera and the
     * player, i.e. behind the character, and targeting them places blocks or
     * breaks things backwards. A block the crosshair rests on can straddle the
     * player's depth (a clip starting at that depth would begin inside it and
     * tunnel through), so it is kept.
     */
    private static HitResult gapWalkClip(Vec3 origin, Vec3 direction, Vec3 end,
            double playerDepth, ClipContext.Block shapeMode, ClipContext.Fluid fluidMode, Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        double maxHitSqr = Mth.square(end.subtract(origin).length() + 2.0);
        double startParam = 0.0;
        HitResult hit;
        for (int i = 0; ; i++) {
            hit = mc.level.clip(new ClipContext(
                    origin.add(direction.scale(startParam)), end,
                    shapeMode, fluidMode, entity));
            if (i >= 8 || !(hit instanceof BlockHitResult blockHit)
                    || hit.getType() != HitResult.Type.BLOCK) {
                break;
            }
            // Sable sublevel hits report plot-space coordinates thousands of
            // blocks away. They can never be gap blocks, and walking them would
            // compute a garbage restart point and re-clip a ray across half the
            // world every frame — freezing the game. Accept them as-is.
            if (blockHit.getLocation().distanceToSqr(origin) > maxHitSqr) {
                break;
            }
            double exit = rayExitOfBlock(blockHit.getBlockPos(), origin, direction);
            if (exit >= playerDepth || exit <= startParam || !Double.isFinite(exit)) {
                break;
            }
            startParam = exit + 1.0E-4;
        }
        return hit;
    }

    /**
     * True along-ray distance to a Sable plot-space hit. The hit's location is
     * unusable, but the sublevel-aware HitResult#distanceTo still gives the
     * true squared world distance from the entity's feet, and the hit lies on
     * the ray: solve |origin + t*dir - feet|^2 = distSqr. The sphere around the
     * feet meets the ray twice and a feet distance cannot tell the crossings
     * apart; the near root is taken when it lands at or past
     * {@code nearRootFloor} (a hit in front of the player), the far root
     * otherwise (distant terrain, where the near root goes negative). Layered
     * decks can defeat the choice — inherent to a feet-relative distance.
     * Returns -1 when the solve degenerates, which a true on-ray hit cannot
     * produce, so: measurement noise.
     */
    private static double plotSpaceAlongRay(HitResult hit, Vec3 origin, Vec3 direction,
            Entity entity, double nearRootFloor) {
        double distSqr = hit.distanceTo(entity);
        Vec3 rel = origin.subtract(entity.position());
        double b = rel.dot(direction);
        double disc = b * b - rel.lengthSqr() + distSqr;
        if (disc < 0.0) {
            return -1.0;
        }
        double sq = Math.sqrt(disc);
        double near = -b - sq;
        return near >= nearRootFloor ? near : -b + sq;
    }

    /** Distance along the ray (unit direction) where it leaves the block's 1x1x1 cell. */
    private static double rayExitOfBlock(BlockPos pos, Vec3 origin, Vec3 dir) {
        double tx = dir.x == 0.0 ? Double.POSITIVE_INFINITY
                : ((dir.x > 0.0 ? pos.getX() + 1 : pos.getX()) - origin.x) / dir.x;
        double ty = dir.y == 0.0 ? Double.POSITIVE_INFINITY
                : ((dir.y > 0.0 ? pos.getY() + 1 : pos.getY()) - origin.y) / dir.y;
        double tz = dir.z == 0.0 ? Double.POSITIVE_INFINITY
                : ((dir.z > 0.0 ? pos.getZ() + 1 : pos.getZ()) - origin.z) / dir.z;
        return Math.min(tx, Math.min(ty, tz));
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
        if (!crosshairAimActive()) {
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
        double playerDepth = Math.max(0.0, playerEye.subtract(origin).dot(direction));

        Vec3 end = origin.add(direction.scale(maxRange));
        HitResult blockHit = gapWalkClip(origin, direction, end, playerDepth,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity);
        // Geometric distance along our ray, for the entity sweep and the
        // entity-vs-block tie-break. Sable sublevel hits report plot-space
        // locations thousands of blocks away — recover the true along-ray
        // distance, or the sweep goes unbounded and an entity behind a hull
        // wall beats the wall (then fails entity reach and targets nothing).
        double blockGeomSqr;
        if (blockHit.getType() == HitResult.Type.MISS) {
            blockGeomSqr = Mth.square(maxRange);
        } else {
            double rawSqr = blockHit.getLocation().distanceToSqr(origin);
            if (rawSqr > Mth.square(maxRange + 2.0)) {
                double t = plotSpaceAlongRay(blockHit, origin, direction, entity, playerDepth);
                blockGeomSqr = Mth.square(t > 0.0 ? Math.min(t, maxRange) : maxRange);
            } else {
                blockGeomSqr = rawSqr;
            }
        }
        double entitySearch = Math.min(Math.sqrt(blockGeomSqr), maxRange);
        // Sweep for entities only from the player's depth outward, for the same
        // reason the block clip walks the gap: an entity between the camera and
        // the player is behind the character, not under the crosshair.
        Vec3 sweepStart = origin.add(direction.scale(Math.min(playerDepth, entitySearch)));
        Vec3 entityEnd = origin.add(direction.scale(entitySearch));
        AABB searchBox = new AABB(sweepStart, entityEnd).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                entity, sweepStart, entityEnd, searchBox,
                target -> !target.isSpectator() && target.isPickable(), Mth.square(entitySearch));

        return entityHit != null && entityHit.getLocation().distanceToSqr(origin) < blockGeomSqr
                ? filterToPlayerRange(entityHit, entity, entityInteractionRange)
                : filterToPlayerRange(blockHit, entity, blockInteractionRange);
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.CreateRaycastMixin} in
     * place of Create's RaycastHelper#getTraceTarget: while the shoulder offset is
     * engaged, aim Create's UI raycasts from the player's eyes THROUGH the point
     * the crosshair actually targets (mc.hitResult follows the camera ray here),
     * so value boxes and contraption controls select what the crosshair shows.
     * Returns null to use Create's own ray.
     */
    public static Vec3 createTraceTarget(net.minecraft.world.entity.player.Player player, double range, Vec3 origin) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || player != mc.player || mc.hitResult == null) {
            return null;
        }
        Vec3 aim = mc.hitResult.getLocation().subtract(origin);
        // Degenerate or plot-space (Sable sublevel) locations have no usable
        // world direction; let Create aim its own ray.
        if (aim.lengthSqr() < 1.0E-4 || aim.lengthSqr() > Mth.square(256.0f)) {
            return null;
        }
        return origin.add(aim.normalize().scale(range));
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.CreateContraptionRayMixin}:
     * origin for Create's contraption raycast. The camera position, so the ray IS
     * the crosshair ray at every depth. Returns null to use Create's own (eye).
     */
    public static Vec3 contraptionRayOrigin(net.minecraft.client.player.LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || player != mc.player) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        return camera.isInitialized() ? camera.getPosition() : null;
    }

    /**
     * Companion to {@link #contraptionRayOrigin}: the ray endpoint, along the
     * camera direction, extended by the camera's setback behind the player so the
     * effective reach in front of the player is unchanged.
     */
    public static Vec3 contraptionRayTarget(net.minecraft.world.entity.player.Player player, double range, Vec3 origin) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || player != mc.player) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vector3f forward = camera.getLookVector();
        double total = range + crosshairRaySetback(player);
        // Create clamps its reach to mc.hitResult measured from this (wrapped)
        // origin; blindly re-adding the setback then overshoots the crosshair
        // hit by that much and selects contraptions through the block in front
        // of them. Cap the endpoint at the crosshair hit instead.
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            double hitSqr = mc.hitResult.getLocation().distanceToSqr(origin);
            if (hitSqr <= Mth.square(total + 2.0)) {
                total = Math.min(total, Math.sqrt(hitSqr) + 0.05);
            }
        }
        return origin.add(new Vec3(forward.x(), forward.y(), forward.z()).scale(total));
    }

    /**
     * Origin of the crosshair ray (the camera position) while the shoulder offset
     * is engaged, else null. Used to redirect other mods' own eye-and-look
     * raycasts onto the crosshair — without it they target whatever the player's
     * body faces, which the sideways offset decouples from what you see.
     *
     * <p>Only redirects rays cast for the LOCAL player: some injection targets
     * (e.g. Simulated's SteeringWheelBlock) are common code, and in singleplayer
     * the integrated server runs the same mixed class for other players' checks.
     */
    public static Vec3 crosshairRayOrigin(net.minecraft.world.entity.player.Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.player == null || player != mc.player) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        return camera.isInitialized() ? camera.getPosition() : null;
    }


    /**
     * Offset from the player's eye to the point on the crosshair ray that sits
     * exactly {@code distance} away from the eye. Lets other mods' "eye + look *
     * distance" targeting follow the crosshair while keeping the distance they
     * chose — solving |origin + t*dir - eye| = distance for t. No player guard:
     * the only call site (the physics staff's drag handler) is a client-only
     * class that always acts for the local player.
     *
     * <p>Returns null while the crosshair aim is inactive (shoulder offset off, no freelook deflection), or when the
     * crosshair ray never reaches that distance from the eye.
     */
    public static Vec3 crosshairOffsetFromEye(double distance) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.player == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vector3f forward = camera.getLookVector();
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());
        Vec3 origin = camera.getPosition();
        Vec3 eye = mc.player.getEyePosition();

        Vec3 originFromEye = origin.subtract(eye);
        double b = 2.0 * originFromEye.dot(direction);
        double c = originFromEye.lengthSqr() - distance * distance;
        double discriminant = b * b - 4.0 * c;
        if (discriminant < 0.0) {
            return null;
        }
        double t = (-b + Math.sqrt(discriminant)) / 2.0;
        if (t <= 0.0) {
            return null;
        }
        return origin.add(direction.scale(t)).subtract(eye);
    }

    /**
     * Crosshair-aligned replacement for {@code Entity#pick}, which raycasts from
     * the entity's eye along its body look. Returns null while the crosshair aim is inactive (shoulder offset off, no freelook deflection), or when the pick is for someone other than the local player,
     * so the caller keeps vanilla behaviour.
     */
    public static HitResult crosshairPick(Entity entity, double hitDistance, float partialTick, boolean hitFluids) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.level == null || entity != mc.player) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vector3f forward = camera.getLookVector();
        Vec3 origin = camera.getPosition();
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());
        double playerDepth = Math.max(0.0, mc.player.getEyePosition().subtract(origin).dot(direction));
        Vec3 end = origin.add(direction.scale(hitDistance + crosshairRaySetback(mc.player)));
        // Walk the camera-player gap like every other crosshair ray, so grass
        // or blocks behind the character can't become the staff's target.
        return gapWalkClip(origin, direction, end, playerDepth, ClipContext.Block.OUTLINE,
                hitFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, entity);
    }

    /**
     * How far the camera sits behind the player's eyes, so a raycast re-based onto
     * the camera can extend its range and keep the same reach in front of the
     * player. Zero while the crosshair aim is inactive (shoulder offset off, no freelook deflection) or the raycast is for
     * someone other than the local player.
     */
    public static double crosshairRaySetback(net.minecraft.world.entity.player.Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.player == null || player != mc.player) {
            return 0.0;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return 0.0;
        }
        // The projection onto the camera forward, not the straight-line
        // distance: the sideways shoulder offset inflates |camera - eye| and
        // handed the compat rays a small reach bonus over first person.
        Vec3 camPos = camera.getPosition();
        Vector3f f = camera.getLookVector();
        Vec3 eye = mc.player.getEyePosition();
        return Math.max(0.0, (eye.x - camPos.x) * f.x() + (eye.y - camPos.y) * f.y() + (eye.z - camPos.z) * f.z());
    }

    /** Direction of the crosshair ray, or null while the crosshair aim is inactive (shoulder offset off, no freelook deflection) or the ray is for someone other than the local player. */
    public static Vec3 crosshairRayDirection(net.minecraft.world.entity.player.Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.player == null || player != mc.player) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vector3f forward = camera.getLookVector();
        return new Vec3(forward.x(), forward.y(), forward.z());
    }

    /**
     * Vanilla's filterHitResult. World-space hits are measured from the EYE,
     * exactly like vanilla, so reach matches first person. Sable sublevel hits
     * carry far plot-space locations with no usable raw distance — those fall
     * back to {@code HitResult#distanceTo} (a squared distance), which Sable
     * overwrites to be sublevel-aware, so hits on Aeronautics contraptions
     * survive the reach check.
     */
    private static HitResult filterToPlayerRange(HitResult hit, Entity player, double range) {
        if (hit.getType() == HitResult.Type.MISS) {
            return hit;
        }
        double rawEyeSqr = hit.getLocation().distanceToSqr(player.getEyePosition());
        double distSqr = rawEyeSqr <= Mth.square(256.0f) ? rawEyeSqr : hit.distanceTo(player);
        if (distSqr > range * range) {
            Vec3 location = hit.getLocation();
            Vec3 eye = player.getEyePosition();
            Direction direction = Direction.getNearest(
                    location.x - eye.x, location.y - eye.y, location.z - eye.z);
            return BlockHitResult.miss(location, direction, BlockPos.containing(location));
        }
        return hit;
    }

    private static boolean isFreelookHeld(Minecraft mc) {
        return ClientConfig.freelookEnabled()
                && FREELOOK_KEY.isDown()
                && mc.player != null
                && mc.screen == null
                // While spectating another entity the view shows that entity's
                // rotation; deflecting relative to the detached player's would
                // wrench the camera somewhere unrelated.
                && mc.getCameraEntity() == mc.player
                && mc.options.getCameraType() == CameraType.FIRST_PERSON;
    }

    /** Freelook's own cinematic-camera smoothers, mirroring MouseHandler's
     * smoothTurnX/Y (vanilla's stop advancing while turnPlayer is cancelled). */
    private static final SmoothDouble freelookSmoothX = new SmoothDouble();
    private static final SmoothDouble freelookSmoothY = new SmoothDouble();

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.MouseHandlerMixin} at the
     * top of {@code MouseHandler#turnPlayer}. While freelooking, accumulates the
     * mouse movement into a camera-only yaw/pitch offset and returns true so the
     * player entity itself doesn't turn.
     */
    public static boolean freelookMouseTurn(double accumulatedDX, double accumulatedDY, double movementTime) {
        Minecraft mc = Minecraft.getInstance();
        if (!isFreelookHeld(mc)) {
            freelookSmoothX.reset();
            freelookSmoothY.reset();
            return false;
        }

        // Vanilla's sensitivity curve (MouseHandler#turnPlayer), including the 0.15
        // factor Entity#turn applies and the cinematic-camera smoothing, so
        // freelook feels identical to normal look. Cancelling turnPlayer at HEAD
        // also skips its CalculatePlayerTurnEvent — the event's only call site —
        // so post it here: a mod adjusting sensitivity or forcing cinematic
        // through it must apply to freelook too.
        CalculatePlayerTurnEvent turnEvent = ClientHooks.getTurnPlayerValues(
                mc.options.sensitivity().get(), mc.options.smoothCamera);
        double d = turnEvent.getMouseSensitivity() * 0.6 + 0.2;
        double cubed = d * d * d;
        double dx;
        double dy;
        if (turnEvent.getCinematicCameraEnabled()) {
            double scaled = cubed * 8.0;
            dx = freelookSmoothX.getNewDeltaValue(accumulatedDX * scaled, movementTime * scaled);
            dy = freelookSmoothY.getNewDeltaValue(accumulatedDY * scaled, movementTime * scaled);
        } else {
            freelookSmoothX.reset();
            freelookSmoothY.reset();
            double scaled = mc.player.isScoping() ? cubed : cubed * 8.0;
            dx = accumulatedDX * scaled;
            dy = accumulatedDY * scaled;
        }
        double invert = mc.options.invertYMouse().get() ? -1.0 : 1.0;

        float yawLimit = ClientConfig.freelookYawLimit();
        freelookYaw = Mth.clamp(freelookYaw + (float) (dx * 0.15), -yawLimit, yawLimit);
        // Clamp so the *final* pitch stays within vanilla's straight-up/down limits.
        float basePitch = mc.player.getXRot();
        freelookPitch = Mth.clamp(freelookPitch + (float) (dy * 0.15 * invert),
                -90.0f - basePitch, 90.0f - basePitch);
        return true;
    }

    static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        // The camera-entity check matters while spectating another entity:
        // that keeps the camera type FIRST_PERSON, and easing or snapping a
        // leftover deflection there would wrench the view (or the detached
        // player's body) around rotations that aren't on screen. Zero the
        // offset and leave the event angles alone.
        if (mc.player == null || mc.options.getCameraType() != CameraType.FIRST_PERSON
                || mc.getCameraEntity() != mc.player) {
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
            if (ClientConfig.freelookKeepDirection()) {
                // Key released: keep facing where we looked. Collapse the offset
                // into the player's rotation on the very first frame after
                // release, before any ease-back can move the view — the screen
                // doesn't shift at all, the body just pivots under it. The event's
                // default angles were captured BEFORE the snap, so they must be
                // overridden or this frame flashes the old rotation.
                snapPlayerToFreelook(mc);
                event.setYaw(mc.player.getYRot());
                event.setPitch(mc.player.getXRot());
                return;
            }
            // Key released: ease the view back to where the player actually looks,
            // gently enough to match the cinematic camera when it is smoothing.
            float returnSpeed = mc.options.smoothCamera
                    ? CINEMATIC_FREELOOK_RETURN_SPEED : FREELOOK_RETURN_SPEED;
            float blend = 1.0f - (float) Math.exp(-deltaSeconds * returnSpeed);
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
            shoulderClearance = ClientConfig.shoulderOffsetAmount();
            lastClearanceSign = 0.0f;
            lastShoulderNanos = 0L;
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastShoulderNanos == 0 ? 0.0f : (now - lastShoulderNanos) / 1_000_000_000.0f;
        lastShoulderNanos = now;

        // The zoom gate compares pre-scale distances (both sides scale-relative);
        // the sideways move and its clearance ray are real world-space blocks, so
        // they carry the entity scale — a half-size character gets a half-size
        // shoulder slide, keeping the framing proportional at any size.
        float entityScale = cameraEntityScale(camera.getEntity());
        float target = 0.0f;
        if (ClientConfig.shoulderOffsetEnabled()) {
            // Gate on the zoom the player has chosen (targetDistance), not the
            // animated or collision-limited distance: the enter animation sweeps
            // through close distances and walls push the camera in, and neither
            // should flash the offset on.
            float fade = Mth.clamp(ClientConfig.shoulderOffsetMaxZoom() + 1.0f - targetDistance, 0.0f, 1.0f);
            target = shoulderSide * ClientConfig.shoulderOffsetAmount() * entityScale * fade;
        }

        float blend = 1.0f - (float) Math.exp(-deltaSeconds * SHOULDER_SPEED);
        shoulderOffset = Mth.lerp(blend, shoulderOffset, target);
        // Settle to zero only when disengaging — never mid-swap, where the offset
        // legitimately passes through zero and zeroing it would hitch the slide.
        if (target == 0.0f && Math.abs(shoulderOffset) < 0.005f) {
            shoulderOffset = 0.0f;
            return;
        }

        // Wall clearance on the current side, always measured out to the maximum
        // possible offset so the value is stable while the camera slides. Camera#move's
        // dx runs along camera-local +X, which is camera-RIGHT (the left vector is
        // -X), so positive offsets travel opposite to it.
        float sign = shoulderOffset == 0.0f ? shoulderSide : Math.signum(shoulderOffset);
        float rawClearance = ClientConfig.shoulderOffsetAmount() * entityScale;
        if (mc.level != null) {
            Vector3f left = camera.getLeftVector();
            Vec3 from = camera.getPosition();
            Vec3 direction = new Vec3(-left.x(), -left.y(), -left.z()).scale(sign);
            Vec3 to = from.add(direction.scale(rawClearance + 0.1));
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                if (hit.getLocation().distanceToSqr(from) <= Mth.square(rawClearance + 0.1 + 2.0)) {
                    rawClearance = (float) Math.max(0.0, hit.getLocation().distanceTo(from) - 0.1);
                } else {
                    // Sable sublevel hit: the location is in far-away plot space,
                    // but the sublevel-aware HitResult#distanceTo still gives the
                    // true squared world distance from the player's feet, and the
                    // hit lies on this sideways ray — solve
                    // |from + t*dir - feet|^2 = distSqr for the exact clearance.
                    // (Treating hulls as a miss let the offset slide the camera
                    // into hull walls.)
                    double distSqr = hit.distanceTo(mc.player);
                    Vec3 rel = from.subtract(mc.player.position());
                    double b = rel.dot(direction);
                    double disc = b * b - rel.lengthSqr() + distSqr;
                    if (disc >= 0.0) {
                        double sq = Math.sqrt(disc);
                        double t = -b - sq > 1.0E-4 ? -b - sq : -b + sq;
                        if (t > 1.0E-4 && t <= rawClearance + 0.3) {
                            rawClearance = (float) Math.max(0.0, t - 0.1);
                        }
                    }
                }
            }
        }

        // Blocked: cap drops instantly so the camera never sits inside a wall.
        // Freed: the cap glides back up — otherwise walking past uneven terrain
        // snaps the offset. On a side switch, adopt the new side's clearance as-is.
        if (sign != lastClearanceSign) {
            shoulderClearance = rawClearance;
            lastClearanceSign = sign;
        } else if (rawClearance <= shoulderClearance) {
            shoulderClearance = rawClearance;
        } else {
            float clipBlend = 1.0f - (float) Math.exp(-deltaSeconds * COLLISION_RECOVER_SPEED);
            shoulderClearance = Mth.lerp(clipBlend, shoulderClearance, rawClearance);
        }

        float applied = Math.min(Math.abs(shoulderOffset), shoulderClearance) * Math.signum(shoulderOffset);
        if (applied != 0.0f) {
            ((CameraInvoker) camera).unlockedcamera$move(0.0f, 0.0f, applied);
        }
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
        // Sable sublevel hits report plot-space locations thousands of blocks
        // away, which would never pull the camera in — a hull between camera
        // and player wouldn't constrain it. Their true world distance survives
        // only in the sublevel-aware HitResult#distanceTo (squared, from the
        // entity's feet); the hit lies on this ray, so the quadratic below
        // recovers the exact along-ray distance from that.
        double maxHitSqr = Mth.square(desired + 2.0);
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
                float d;
                if (hit.getLocation().distanceToSqr(position) <= maxHitSqr) {
                    d = (float) hit.getLocation().distanceTo(position);
                } else {
                    // Solve |from - t*forwards - feet|^2 = distSqr for t, the
                    // exact obstruction distance along this ray. Measuring
                    // straight from the feet instead over-reads by up to an eye
                    // height, letting the camera sink ~a block into hull walls
                    // and roofs (the floor only escaped because the feet stand
                    // on it).
                    double distSqr = hit.distanceTo(entity);
                    Vec3 rel = from.subtract(entity.position());
                    double b = rel.x * forwards.x() + rel.y * forwards.y() + rel.z * forwards.z();
                    double disc = b * b - rel.lengthSqr() + distSqr;
                    if (disc >= 0.0) {
                        // Far root, always: this ray starts at the eye, and a
                        // hull hit lies past the ray's closest approach to the
                        // feet — the near sphere crossing is open air, and
                        // preferring it collapsed the camera onto the head
                        // past 45 degrees of pitch on Sable decks.
                        double t = b + Math.sqrt(disc);
                        d = t > 1.0E-4 ? (float) t : (float) Math.sqrt(distSqr);
                    } else {
                        // Degenerate solve; fall back to the feet distance.
                        d = (float) Math.sqrt(distSqr);
                    }
                }
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
