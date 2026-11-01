package com.caleb.unlockedcamera.client;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Supplier;

/** Limits the shared Item POV hook to a bucket's synchronous client use. */
public final class BucketUseRayContext {
    private record Context(Level level, Player player, InteractionHand hand) {}
    private static final class Request {
        final Level level;
        final Player player;
        final InteractionHand hand;
        int bucketDepth;
        boolean consumed;
        float[] aim;
        Request(Level level, Player player, InteractionHand hand) {
            this.level = level; this.player = player; this.hand = hand;
        }
    }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();

    private BucketUseRayContext() {}

    public static <T> T duringUse(Level level, Player player, Supplier<T> action) {
        return duringUse(level, player, null, action);
    }

    public static <T> T duringUse(Level level, Player player, InteractionHand hand, Supplier<T> action) {
        return withContext(new Context(level, player, hand), action);
    }

    /** Covers prediction AND the subsequent send, never a later tick or click. */
    public static <T> T duringRequest(Level level, Player player, InteractionHand hand, Supplier<T> action) {
        if (!level.isClientSide) return action.get();
        Request previous = REQUEST.get();
        REQUEST.set(new Request(level, player, hand));
        try {
            return action.get();
        } finally {
            if (previous == null) REQUEST.remove();
            else REQUEST.set(previous);
        }
    }

    /** An unrelated item used from a bucket callback keeps its own vanilla ray. */
    public static <T> T outsideUse(Supplier<T> action) {
        return withContext(null, action);
    }

    private static <T> T withContext(Context context, Supplier<T> action) {
        Context previous = CURRENT.get();
        Request request = REQUEST.get();
        boolean bucketRequest = context != null && context.level.isClientSide && context.hand != null
                && request != null && context.level == request.level && context.player == request.player && context.hand == request.hand;
        float[] previousAim = bucketRequest ? request.aim : null;
        if (bucketRequest) request.bucketDepth++;
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
        boolean completed = false;
        try {
            T result = action.get(); completed = true; return result;
        } finally {
            if (bucketRequest) {
                request.bucketDepth--;
                if (!completed) request.aim = previousAim;
            }
            // A nested item use and an exception must both restore the caller's
            // scope. Never leave unrelated later POV queries camera-corrected.
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static boolean matches(Level level, Player player) {
        Context context = CURRENT.get();
        return level.isClientSide && context != null && context.level == level && context.player == player;
    }

    /** Capture before bucket prediction removes a source or replaces its stack. */
    public static void capturePacketAim(Level level, Player player, BlockHitResult hit) {
        Context context = CURRENT.get(); Request request = REQUEST.get();
        if (!matches(level, player) || request == null || context.hand != request.hand
                || request.level != level || request.player != player || request.bucketDepth != 1 || request.consumed) return;
        request.aim = UnlockedCameraClient.bucketHitAimAngles(player, hit);
    }

    /** One matching packet after prediction, only while its game-mode call is active. */
    public static float[] consumePacketAim(Player player, InteractionHand hand) {
        Request request = REQUEST.get();
        if (player == null || request == null || !request.level.isClientSide || request.level != player.level()
                || request.player != player || request.hand != hand || request.bucketDepth != 0
                || request.consumed || request.aim == null) return null;
        float[] aim = request.aim;
        request.aim = null; request.consumed = true;
        return aim.clone();
    }
}
