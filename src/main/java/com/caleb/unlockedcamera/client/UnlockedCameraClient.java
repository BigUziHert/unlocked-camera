package com.caleb.unlockedcamera.client;

import com.caleb.unlockedcamera.UnlockedCameraMod;
import com.caleb.unlockedcamera.mixin.CameraInvoker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

    /** The use key's state at the PREVIOUS tick's sample, read by the snap
     * handler to tell a fresh right-click from held-button auto-repeat. */
    private static boolean useKeyWasDownLastTick;
    private static boolean useKeyDownSample;

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
        // Not a per-click event: it re-fires every tick from continueAttack
        // while left-click is held mining a block, and every ~4 ticks from the
        // held right-click auto-repeat. Re-snapping on every firing chased the
        // view in half-degree quanta — freelook degraded to normal look while
        // mining, plus a full extra pick per tick. Only a fresh press commits
        // the deflection: mining in progress means the attack press is old,
        // and a use key already down last tick means auto-repeat.
        if (event.isAttack() && mc.gameMode != null && mc.gameMode.isDestroying()) {
            return;
        }
        if (event.isUseItem() && useKeyWasDownLastTick) {
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
            trackedPlayer = null;
            clearTransmittedAims();
            return;
        }

        // A fresh LocalPlayer instance (respawn, dimension change, reconnect):
        // nothing stamped for the old one can be a legitimate echo for the new
        // one — its spawn rotation arrives in exactly the packet the echo
        // check inspects — so forget the transmitted-aim history and the cache.
        if (mc.player != trackedPlayer) {
            trackedPlayer = mc.player;
            dropHeldAim();
            clearTransmittedAims();
        }

        // Sampled every tick (before any later early return) for the
        // keep-direction snap's fresh-press latch: the event handler compares
        // against the PREVIOUS tick's sample.
        useKeyWasDownLastTick = useKeyDownSample;
        useKeyDownSample = mc.options.keyUse.isDown();

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
        //
        // Mounted on a vanilla vehicle, the hold narrows to the actual draw:
        // steerable mounts copy the rider's server yaw onto their body every
        // tick (tickRidden), so a merely-held bow twisted the horse toward the
        // crosshair for OTHER players — invisible to the rider, whose client
        // re-derives from its own rotation. Instant uses (pearls, snowballs)
        // stay covered by the click-time UseItem rewrite, which the
        // unconditional per-tick passenger rotation stream makes sufficient
        // while mounted. TACZ guns never start a vanilla item use — their
        // "draw" is the gun being aimed, charged, or fired, which TaczCompat
        // reads from the gun operator; the shoot packet itself goes out from
        // ClientTickEvent.Post, so a press seen here has its aim on the wire
        // (this tick's passenger rotation stream) before the shot arrives.
        // (Sable seats never reach here — their path returned above.)
        boolean holdWanted = (holdingRangedItem(mc) || freelookDeflected(mc))
                && (!mc.player.isPassenger() || mc.player.isUsingItem()
                        || TaczCompat.isGunEngaged(mc.player));
        float[] freshAim = holdWanted ? crosshairAimAngles() : null;
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
            recordTransmittedAim(freshAim);
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
        Vec3 entityPoint = null;
        if (entityAim != null) {
            entityPoint = entityAim.getLocation();
            if (entityPoint.distanceToSqr(origin) > Mth.square(range + 1.0)) {
                // An entity riding a Sable sublevel (an item frame on a ship
                // wall, a mob on deck): Sable clips the sweep inside the
                // sublevel's frame, so the location AND the entity's bounding
                // box come back in far-away plot space. Subtracting the
                // world-space eye from either is garbage — recover the true
                // along-ray point exactly like the block hit above, and nudge
                // INTO the entity along the ray instead of toward a centre
                // that lives in another coordinate frame. The sweep's own
                // occlusion bound (entitySearch, already recovered) still
                // holds: Sable measured the sweep's distances sublevel-aware.
                double t = plotSpaceAlongRay(entityAim, origin, direction, mc.player, playerDepth);
                entityPoint = t > 0.0
                        ? origin.add(direction.scale(Math.min(t + 0.2, entitySearch)))
                        : null; // degenerate solve: measurement noise, keep the block aim
            } else {
                // Nudge toward the entity's centre so spread can't graze past.
                Vec3 toBody = entityAim.getEntity().getBoundingBox().getCenter().subtract(entityPoint);
                double toBodyLen = toBody.length();
                if (toBodyLen > 1.0E-4) {
                    entityPoint = entityPoint.add(toBody.scale(Math.min(0.2, toBodyLen * 0.35) / toBodyLen));
                }
            }
        }
        if (entityPoint != null) {
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

    /** The LocalPlayer the aim history belongs to; a new instance resets it. */
    private static LocalPlayer trackedPlayer;

    /**
     * Ring of aim rotations ACTUALLY transmitted (tick sends, rewritten
     * movement packets, rewritten use packets), for the echo check below. A
     * correction the server issues carries the rotation it held when it
     * decided to correct — under latency while the player turns, that is a
     * stamp several sends old, which "current aim or latest send" could never
     * recognize. Bounded by count and by age: an echo cannot be older than
     * the round trip plus the server's 20-tick pending-teleport re-send. At
     * one stamp per tick (a walking rider's movement stream) 128 entries
     * outlast the five-second lifetime, so age is always the binding bound.
     */
    private static final int AIM_HISTORY_SIZE = 128;
    private static final long AIM_HISTORY_LIFETIME_NANOS = 5_000_000_000L;
    private static final float[] aimHistoryYaw = new float[AIM_HISTORY_SIZE];
    private static final float[] aimHistoryPitch = new float[AIM_HISTORY_SIZE];
    private static final long[] aimHistoryNanos = new long[AIM_HISTORY_SIZE];
    private static int aimHistoryNext;
    private static int aimHistoryCount;
    /** The array the last entry was recorded from: a tick's cached aim is
     * stamped into every movement packet of that tick, so the same instance
     * arriving again is the same transmission, not a new one. */
    private static float[] lastRecordedAim;

    /**
     * Note that {@code aim} ({yaw, pitch, ...}) just went out on the wire.
     * Called by the tick sender and by the packet rewrites in
     * {@link com.caleb.unlockedcamera.mixin.ClientPacketListenerMixin}.
     */
    public static void recordTransmittedAim(float[] aim) {
        if (aim == null || aim == lastRecordedAim) {
            return;
        }
        lastRecordedAim = aim;
        aimHistoryYaw[aimHistoryNext] = aim[0];
        aimHistoryPitch[aimHistoryNext] = aim[1];
        aimHistoryNanos[aimHistoryNext] = System.nanoTime();
        aimHistoryNext = (aimHistoryNext + 1) % AIM_HISTORY_SIZE;
        aimHistoryCount = Math.min(aimHistoryCount + 1, AIM_HISTORY_SIZE);
    }

    private static void clearTransmittedAims() {
        aimHistoryCount = 0;
        aimHistoryNext = 0;
        lastRecordedAim = null;
    }

    /**
     * Called from {@link com.caleb.unlockedcamera.mixin.TeleportRotationMixin}:
     * whether an incoming ABSOLUTE server rotation for {@code player} is just
     * a stamped crosshair aim echoed back. While the hold is active the
     * server's stored rotation IS the stamped aim, so rubber-band corrections
     * return it instead of vanilla's effectively rotation-neutral value. The
     * aim drifts well under a degree between stamps, so a one-degree window
     * around any recently transmitted aim recognizes the echo; a deliberate
     * server rotation landing inside that window is indistinguishable from
     * the aim anyway, and the window is what keeps deliberate ones intact —
     * widening it would swallow them. A packet for a player other than the
     * one the history was recorded for (respawn, dimension change) is never
     * an echo: its spawn rotation must apply.
     */
    public static boolean isHeldAimEcho(LocalPlayer player, float yaw, float pitch) {
        if (player == null || player != trackedPlayer) {
            return false;
        }
        long now = System.nanoTime();
        for (int i = 1; i <= aimHistoryCount; i++) {
            int slot = Math.floorMod(aimHistoryNext - i, AIM_HISTORY_SIZE);
            if (now - aimHistoryNanos[slot] > AIM_HISTORY_LIFETIME_NANOS) {
                break; // older entries only get older
            }
            if (Math.abs(Mth.wrapDegrees(yaw - aimHistoryYaw[slot])) < 1.0f
                    && Math.abs(pitch - aimHistoryPitch[slot]) < 1.0f) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdingRangedItem(Minecraft mc) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            UseAnim anim = stack.getUseAnimation();
            if (anim == UseAnim.BOW || anim == UseAnim.CROSSBOW || anim == UseAnim.SPEAR
                    || stack.getItem() instanceof ProjectileItem
                    // EnderpearlItem does NOT implement ProjectileItem in
                    // 1.21.1 — this clause alone enables the hold for pearls.
                    || stack.getItem() instanceof EnderpearlItem
                    // TACZ guns: UseAnim.NONE and not ProjectileItem, but their
                    // shoot packet carries no rotation — the server fires along
                    // the rotation it last heard, so the hold is what lands the
                    // shot on the crosshair.
                    || TaczCompat.isGun(stack)) {
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
     * Called from {@link com.caleb.unlockedcamera.mixin.TaczCrosshairMixin}:
     * whether TACZ should draw its own gun reticle even though the camera isn't
     * first person. TACZ cancels the vanilla crosshair layer outright while a
     * gun is held and draws its reticle instead, but suppresses that reticle in
     * third person unless a shoulder-camera mod vouches for it — so with a gun
     * in hand our forced crosshair produced NO crosshair at all. Answer through
     * their own compat seam, on exactly the condition our own crosshair uses.
     */
    public static boolean taczShouldShowCrosshair() {
        return shouldForceCrosshair();
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
            if (!(hit instanceof BlockHitResult blockHit)
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
            if (i >= 8) {
                // Iteration cap hit while still inside the gap (9+ camera-
                // transparent solids stacked between camera and player, e.g. a
                // deep glass cube crossed diagonally): jump the final clip to
                // the player's depth instead of returning this block — it is
                // BEHIND the character, and aiming or picking it reverses the
                // shot. A straddling block yields an inside-hit AT the player,
                // which the aim path drops as too close and reach filtering
                // treats sanely.
                hit = mc.level.clip(new ClipContext(
                        origin.add(direction.scale(playerDepth)), end,
                        shapeMode, fluidMode, entity));
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
     * Called from {@link com.caleb.unlockedcamera.mixin.GameRendererMixin}:
     * whether this frame's render-level pick should move from BEFORE
     * {@code Camera#setup} to right after it. Vanilla picks first and sets the
     * camera up second, which is harmless for an eye ray (the eye is entity
     * state) but makes the camera ray here read the PREVIOUS frame's pose —
     * the block outline trails the displayed camera by a frame, visibly at
     * low fps. Only while the crosshair aim is active, and only when the
     * deferred pick can be run under the same Sable sublevel poses the
     * original call site gets (see {@link SableCompat#canPushRenderPoses}):
     * without those, ship outlines would jitter instead — the vanilla
     * ordering is the lesser evil then.
     */
    public static boolean shouldDeferRenderPick() {
        return crosshairAimActive() && SableCompat.canPushRenderPoses();
    }

    /**
     * Run the deferred render pick under Sable's interpolated sublevel poses
     * for {@code partialTick}, exactly as Sable's own wrapper does around the
     * original call site (plain call when Sable is absent).
     */
    public static void runDeferredRenderPick(float partialTick, Runnable pick) {
        SableCompat.withRenderPoses(partialTick, pick);
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

        // Entity-vs-block tie-break, both sides in camera-ray distances. An
        // entity riding a sublevel (an item frame on a ship wall) comes back
        // with a PLOT-space location — Sable resolves it correctly INSIDE
        // getEntityHitResult, but raw location math out here made it lose to
        // the recovered block distance every time, so the wall behind the
        // frame ate the click. Recover its along-ray distance exactly like
        // the block side does. (Sable's sublevel-aware HitResult#distanceTo
        // is defined on the base class, so it serves entity hits too.)
        if (entityHit != null) {
            double entityGeomSqr = entityHit.getLocation().distanceToSqr(origin);
            if (entityGeomSqr > Mth.square(maxRange + 2.0)) {
                double t = plotSpaceAlongRay(entityHit, origin, direction, entity, playerDepth);
                entityGeomSqr = t > 0.0 ? Mth.square(Math.min(t, maxRange)) : Double.MAX_VALUE;
            }
            if (entityGeomSqr < blockGeomSqr) {
                return filterToPlayerRange(entityHit, entity, entityInteractionRange);
            }
        }
        return filterToPlayerRange(blockHit, entity, blockInteractionRange);
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
        Vec3 hitLocation = mc.hitResult.getLocation();
        if (hitLocation.distanceToSqr(origin) > Mth.square(256.0f)) {
            // Sable plot-space location (crosshair anywhere on a ship).
            // Standing down here turned Create's ray back into the body ray
            // and killed steering-wheel/throttle targeting everywhere on a
            // deck — their selection rides these rays. The hit still lies on
            // the camera ray, so recover its world-space point and aim
            // through that instead.
            hitLocation = plotSpaceHitOnCameraRay();
            if (hitLocation == null) {
                return null;
            }
        }
        Vec3 aim = hitLocation.subtract(origin);
        // A degenerate direction (hit at the origin) has no ray to give.
        if (aim.lengthSqr() < 1.0E-4) {
            return null;
        }
        return origin.add(aim.normalize().scale(range));
    }

    /**
     * World-space point on the camera ray where the current plot-space
     * {@code mc.hitResult} actually sits, via the plot-space solve — null when
     * the camera isn't ready or the solve degenerates. (A reach-degraded MISS
     * keeps the raw plot location but loses Sable's distance override; its
     * astronomical solve just lands the point far along the ray — still the
     * correct DIRECTION, which is all a MISS has to offer anyway.)
     */
    private static Vec3 plotSpaceHitOnCameraRay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.hitResult == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vector3f forward = camera.getLookVector();
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());
        Vec3 origin = camera.getPosition();
        double playerDepth = Math.max(0.0, mc.player.getEyePosition().subtract(origin).dot(direction));
        double t = plotSpaceAlongRay(mc.hitResult, origin, direction, mc.player, playerDepth);
        return t > 0.0 ? origin.add(direction.scale(t)) : null;
    }

    /**
     * Companion to {@link #crosshairRayGapFreeOrigin} for Create's contraption
     * raycast: the ray endpoint along the camera direction. The origin already
     * sits at the player's depth, so the raw range IS the reach in front of the
     * player — no setback re-add.
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
        Vec3 direction = new Vec3(forward.x(), forward.y(), forward.z());
        double total = range;
        // Cap the endpoint at the crosshair hit, or the ray selects
        // contraptions through the block in front of them (Create's own reach
        // clamp measured the same way disarms identically).
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            double hitSqr = mc.hitResult.getLocation().distanceToSqr(origin);
            if (hitSqr <= Mth.square(total + 2.0)) {
                total = Math.min(total, Math.sqrt(hitSqr) + 0.05);
            } else {
                // Sable plot-space location: the raw distance is astronomical,
                // which silently DISARMED this cap on Aeronautics decks — a
                // right-click could reach a contraption behind the hull wall
                // the crosshair rested on. Recover the true along-ray distance
                // instead (the origin already sits at the player's depth, so
                // the near-root floor is zero).
                double t = plotSpaceAlongRay(mc.hitResult, origin, direction, mc.player, 0.0);
                if (t > 0.0) {
                    total = Math.min(total, t + 0.05);
                }
            }
        }
        return origin.add(direction.scale(total));
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
     * Origin for compat rays that must not cover the camera-player gap: the
     * point ON the crosshair ray at the player's own depth. A ray traced from
     * here with the mod's RAW (un-setback) reach matches first-person reach in
     * front of the player, and can never claim blocks or entities BEHIND the
     * character — the region the mod's own picks exclude via gapWalkClip and
     * the entity-sweep start. Null under the same conditions as
     * {@link #crosshairRayOrigin}.
     */
    public static Vec3 crosshairRayGapFreeOrigin(net.minecraft.world.entity.player.Player player) {
        Vec3 origin = crosshairRayOrigin(player);
        if (origin == null) {
            return null;
        }
        Vec3 direction = crosshairRayDirection(player);
        return origin.add(direction.scale(crosshairRaySetback(player)));
    }

    /**
     * Whether {@code mc.hitResult} can steer the hit-derived compat redirects
     * this frame (Create's BigOutlines origin — its direction rides
     * RaycastHelper). The hooks engage and stand down TOGETHER: redirecting
     * only origin or only range pairs a camera origin with a body-ray
     * direction — a hybrid ray corresponding to no gaze at all. A world-space
     * location is directly usable; a Sable plot-space one is recovered onto
     * the camera ray (see {@link #plotSpaceHitOnCameraRay}) — ship decks are
     * plot-space on every frame, and fully standing down there killed
     * steering-wheel/throttle targeting.
     */
    public static boolean crosshairHitUsable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.hitResult == null) {
            return false;
        }
        if (mc.hitResult.getLocation().distanceToSqr(mc.player.getEyePosition())
                <= Mth.square(256.0f)) {
            return true;
        }
        return plotSpaceHitOnCameraRay() != null;
    }

    /**
     * Called from the Create compat mixins (BigOutlines' range seed, the
     * chain-conveyor occlusion seed): true squared distance from
     * {@code reference} to the crosshair hit when its location is Sable
     * plot-space. Those consumers seed distance caps/culls with the hit's raw
     * squared distance — astronomical for a plot-space hit, so the cap never
     * fires and blocks were claimable through the hull wall the crosshair
     * rested on. Null when recovery does not apply ({@code location} is not
     * the current crosshair hit's own location object, aim inactive, or the
     * solve degenerates) — callers keep the raw value.
     */
    public static Double plotAwareHitDistSqr(Vec3 location, Vec3 reference) {
        Minecraft mc = Minecraft.getInstance();
        if (!crosshairAimActive() || mc.hitResult == null
                || location != mc.hitResult.getLocation()) {
            return null;
        }
        Vec3 recovered = plotSpaceHitOnCameraRay();
        return recovered != null ? recovered.distanceToSqr(reference) : null;
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
            // Same bamboo transparency as the zoom cap, or stalks beside the
            // shoulder yank the offset while walking a bamboo forest.
            HitResult hit = cameraVisualClip(mc.level, from, to, mc.player,
                    Mth.square(rawClearance + 0.1 + 2.0));
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
     * VISUAL clip for the camera that treats bamboo as camera-transparent.
     * Glass gets that from vanilla (empty visual shape), but bamboo's thin
     * stalk still blocks the camera, so walking or orbiting in a bamboo forest
     * snapped the zoom on every stalk a corner ray grazed. Walk the clip past
     * bamboo hits the way gapWalkClip walks gap blocks — bounded, and never
     * restarting from a Sable plot-space hit (re-clipping across half the
     * world froze the game once already).
     */
    private static HitResult cameraVisualClip(BlockGetter level, Vec3 from, Vec3 to,
            Entity entity, double maxHitSqr) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-7) {
            return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
        }
        Vec3 direction = delta.scale(1.0 / length);
        double startParam = 0.0;
        HitResult hit;
        for (int i = 0; ; i++) {
            hit = level.clip(new ClipContext(
                    from.add(direction.scale(startParam)), to,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (i >= 8 || !(hit instanceof BlockHitResult blockHit)
                    || hit.getType() != HitResult.Type.BLOCK
                    || hit.getLocation().distanceToSqr(from) > maxHitSqr) {
                break;
            }
            BlockState state = level.getBlockState(blockHit.getBlockPos());
            if (!state.is(Blocks.BAMBOO) && !state.is(Blocks.BAMBOO_SAPLING)) {
                break;
            }
            double exit = rayExitOfBlock(blockHit.getBlockPos(), from, direction);
            if (exit <= startParam || !Double.isFinite(exit)) {
                break;
            }
            if (exit >= length) {
                // Only bamboo remains between here and the segment end.
                return BlockHitResult.miss(to, blockHit.getDirection(), blockHit.getBlockPos());
            }
            startParam = exit + 1.0E-4;
        }
        return hit;
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
            HitResult hit = cameraVisualClip(level, from, to, entity, maxHitSqr);
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
