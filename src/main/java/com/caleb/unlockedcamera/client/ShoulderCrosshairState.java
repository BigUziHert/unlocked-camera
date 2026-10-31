package com.caleb.unlockedcamera.client;

/**
 * Preserve first-person crosshair continuity on direct shoulder-camera entry;
 * otherwise wait for the shoulder framing before revealing its crosshair.
 * Once revealed, keep it stable through shoulder swaps and wall clearance.
 * The permanent-crosshair option bypasses this state entirely.
 */
final class ShoulderCrosshairState {
    private static final float READY_FRACTION = 0.9f;

    private boolean ready;
    private float previousAmount;

    void reset() {
        ready = false;
        previousAmount = 0.0f;
    }

    void beginEntry(boolean fromFirstPerson, boolean fullShoulderRequested, float amount) {
        // First person already has a visible reticle. Carry it through F5's
        // camera slide instead of hiding it and making it pop back in later.
        ready = fromFirstPerson && fullShoulderRequested;
        previousAmount = amount;
    }

    void update(boolean fullShoulderRequested, float amount, float appliedOffset) {
        if (!fullShoulderRequested) {
            reset();
            return;
        }
        // A larger live-configured offset needs to finish its own slide too.
        // A side swap keeps the same amount and deliberately crosses zero.
        if (amount > previousAmount) {
            ready = false;
        }
        previousAmount = amount;
        if (Math.abs(appliedOffset) >= amount * READY_FRACTION) {
            ready = true;
        }
    }

    boolean isReady() {
        return ready;
    }
}
