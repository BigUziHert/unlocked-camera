package com.caleb.unlockedcamera.client;

/** Dependency-free transition regressions; run via the shoulderCrosshairTest task. */
public final class ShoulderCrosshairStateTest {
    public static void main(String[] args) {
        testFirstPersonEntry();
        testEnterAndZoomFade();
        testSwapAndWalls();
        testInterruptedZoomAndReset();
        testConfigChanges();
        testFrameRatesAndScales();
        System.out.println("Shoulder crosshair transition regressions passed");
    }

    private static void testFirstPersonEntry() {
        ShoulderCrosshairState state = new ShoulderCrosshairState();
        state.beginEntry(true, true, 0.95f);
        expect(state.isReady(), "direct first-person entry keeps the existing crosshair");
        for (float offset : new float[] {0.0f, 0.1f, 0.44f, 0.8f, 0.95f}) {
            state.update(true, 0.95f, offset);
            expect(state.isReady(), "entry slide must not blink at " + offset);
        }
        state.update(false, 0.95f, 0.95f);
        expect(!state.isReady(), "entry continuity ends when zoom leaves full shoulder range");
        state.update(true, 0.95f, 0.19f);
        expect(!state.isReady(), "later zoom-in still waits for shoulder framing");

        state.beginEntry(true, false, 0.95f);
        expect(!state.isReady(), "first-person entry into a distant view must not force a reticle");
        state.update(true, 0.95f, 0.1f);
        expect(!state.isReady(), "zoom-in after distant entry must not inherit continuity");
        state.beginEntry(false, true, 0.95f);
        state.update(true, 0.95f, 0.1f);
        expect(!state.isReady(), "entry from vanilla third person retains the framing gate");
    }

    private static void testEnterAndZoomFade() {
        ShoulderCrosshairState state = new ShoulderCrosshairState();
        expect(!state.isReady(), "zoom-in starts without a crosshair over the head");
        // User's 0.95 offset / 5.5 max zoom: 6.3 blocks requests only 0.19.
        state.update(false, 0.95f, 0.19f);
        expect(!state.isReady(), "partial fade must not reveal the crosshair");
        state.update(true, 0.95f, 0.0f);
        expect(!state.isReady(), "full target must wait for actual camera movement");
        state.update(true, 0.95f, 0.8f);
        expect(!state.isReady(), "unfinished shoulder slide stays hidden");
        state.update(true, 0.95f, 0.86f);
        expect(state.isReady(), "reveal once the configured framing is established");
        state.update(false, 0.95f, 0.94f);
        expect(!state.isReady(), "zooming back into the fade band hides immediately");
    }

    private static void testSwapAndWalls() {
        ShoulderCrosshairState state = new ShoulderCrosshairState();
        state.update(true, 0.95f, 0.1f);
        expect(!state.isReady(), "initial wall obstruction must not reveal over the head");
        state.update(true, 0.95f, 0.94f);
        for (float offset : new float[] {0.5f, 0.0f, -0.5f, -0.94f}) {
            state.update(true, 0.95f, offset);
            expect(state.isReady(), "intentional shoulder swap must not flicker at " + offset);
        }
        state.update(true, 0.95f, -0.1f);
        expect(state.isReady(), "a nearby wall must not remove an established aiming reticle");
    }

    private static void testInterruptedZoomAndReset() {
        ShoulderCrosshairState state = new ShoulderCrosshairState();
        state.update(true, 0.95f, 0.95f);
        state.update(false, 0.95f, 0.8f);
        state.update(true, 0.95f, 0.8f);
        expect(!state.isReady(), "reversing zoom must not reuse the old reveal latch");
        state.update(true, 0.95f, 0.95f);
        state.reset();
        state.update(true, 0.95f, 0.0f);
        expect(!state.isReady(), "reset clears continuity before the next entry or seat handoff");
    }

    private static void testConfigChanges() {
        ShoulderCrosshairState state = new ShoulderCrosshairState();
        state.update(true, 0.5f, 0.5f);
        expect(state.isReady(), "minimum configured offset can reveal");
        state.update(true, 1.0f, 0.6f);
        expect(!state.isReady(), "increasing offset waits for the new framing");
        state.update(true, 1.0f, 0.91f);
        expect(state.isReady(), "new offset reveals when ready");
        state.update(true, 0.5f, 0.5f);
        expect(state.isReady(), "decreasing offset retains valid framing");
        state.update(false, 0.5f, 0.5f);
        expect(!state.isReady(), "disabling shoulders clears the latch");
    }

    private static void testFrameRatesAndScales() {
        for (int fps : new int[] {15, 30, 60, 144, 240}) {
            for (float scale : new float[] {0.5f, 1.0f, 2.0f}) {
                ShoulderCrosshairState state = new ShoulderCrosshairState();
                float applied = 0.0f;
                float amount = 0.95f;
                double dt = 1.0 / fps;
                int firstVisible = 0;
                for (int frame = 1; frame <= fps; frame++) {
                    applied += (amount * scale - applied) * (float) (1.0 - Math.exp(-8.0 * dt));
                    state.update(true, amount, applied / scale);
                    if (state.isReady()) {
                        firstVisible = frame;
                        expect(Math.abs(applied) >= 0.9f * amount * scale,
                                "crosshair must be physically offset at every FPS/scale");
                        break;
                    }
                }
                double visibleAt = firstVisible * dt;
                double expected = Math.log(10.0) / 8.0;
                expect(firstVisible > 0 && visibleAt >= expected - 1.0E-5
                                && visibleAt <= expected + dt + 1.0E-5,
                        "reveal time must be framerate-independent: " + fps + "/" + scale);
            }
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
