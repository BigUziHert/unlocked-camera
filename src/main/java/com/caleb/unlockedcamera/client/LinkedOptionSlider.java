package com.caleb.unlockedcamera.client;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractOptionSliderButton;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The slider behind {@link LinkedSliderRange}. While dragged past a linked
 * setting's value it pushes that setting along in real time; while idle it
 * re-syncs its knob AND its {@link OptionInstance} from the config each frame,
 * so being pushed is visible live and never leaves a stale instance whose
 * equals-guards and journaled old values would lie.
 *
 * <p>Every change applies to the config immediately (config writes are
 * memory-only until the screen's own close flush, so this also survives the
 * screen closing mid-drag). Only the undo journal waits: a whole
 * press-drag-release gesture lands as ONE composite step through
 * {@link LinkedSliderRange.Commit}, covering the dragged value and every
 * linked setting the gesture pushed. Release doesn't always reach the widget
 * — positional mouseReleased routing skips it when the mouse-up lands
 * elsewhere — so the render loop also closes the gesture as soon as the
 * button is no longer down.
 */
public final class LinkedOptionSlider extends AbstractOptionSliderButton {
    private final OptionInstance<Integer> instance;
    private final LinkedSliderRange range;
    private final OptionInstance.TooltipSupplier<Integer> tooltipSupplier;
    private final Consumer<Integer> onValueChanged;
    /** Per linked setting: its RAW config value when the open gesture first
     * pushed it; null = untouched. Raw, not stepped: Undo must restore what
     * was actually stored, not the step grid's nearest neighbour. */
    private Double[] linkedStarts;
    /** Own config value (slider units) when the open gesture started — what
     * the instance is re-synced to. */
    private int gestureStartOwn;
    /** Own RAW config value when the open gesture started, journaled as the
     * undo target (a hand-edited 12.3 comes back as 12.3, not 12.5). */
    private double gestureStartOwnRaw;
    /** A press/drag gesture is open; it journals once, when it ends. */
    private boolean gestureOpen;

    LinkedOptionSlider(Options options, int x, int y, int width, int height,
            OptionInstance<Integer> instance, LinkedSliderRange range,
            OptionInstance.TooltipSupplier<Integer> tooltipSupplier, Consumer<Integer> onValueChanged) {
        super(options, x, y, width, height, range.toSliderValue(instance.get()));
        this.instance = instance;
        this.range = range;
        this.tooltipSupplier = tooltipSupplier;
        this.onValueChanged = onValueChanged;
        this.linkedStarts = new Double[range.linked().size()];
        updateMessage();
    }

    private void pinKnob() {
        double lo = range.toSliderValue(range.liveMin().getAsInt());
        double hi = range.toSliderValue(range.liveMax().getAsInt());
        this.value = Mth.clamp(this.value, Math.min(lo, hi), Math.max(lo, hi));
    }

    @Override
    protected void updateMessage() {
        pinKnob();
        int current = range.fromSliderValue(this.value);
        setMessage(range.display().apply(current));
        setTooltip(tooltipSupplier.apply(current));
    }

    /**
     * Every value change flows through here (click, drag, keyboard step):
     * push linked settings along, then apply the own value to the instance
     * and config right away — the journal alone waits for the gesture to end.
     */
    @Override
    protected void applyValue() {
        pinKnob();
        openGesture();
        List<LinkedSliderRange.Linked> links = range.linked();
        // Raw config values, so a push that lands on the same step as the
        // original (12.3 -> 12.5) is still seen and journaled.
        double[] before = new double[links.size()];
        for (int i = 0; i < links.size(); i++) {
            before[i] = links.get(i).raw().getAsDouble();
        }
        range.onDragValue().accept(range.fromSliderValue(this.value));
        for (int i = 0; i < links.size(); i++) {
            if (linkedStarts[i] == null && links.get(i).raw().getAsDouble() != before[i]) {
                linkedStarts[i] = before[i];
            }
        }
        Integer newValue = range.fromSliderValue(this.value);
        if (!newValue.equals(instance.get())) {
            instance.set(newValue); // the instance's consumer writes the config and flags the screen changed
        }
    }

    /**
     * Open a journaling gesture if none is open. The pre-gesture own value is
     * read from the CONFIG, not the instance — a sibling's push moves the
     * config underneath a stale instance, and journaling the stale value
     * would let Undo jump the min/max/threshold fences or swallow a change
     * that lands on it. The instance is re-synced to match (the consumer
     * no-ops: the config already holds that value).
     */
    private void openGesture() {
        if (gestureOpen) {
            return;
        }
        gestureOpen = true;
        openGestures.add(this);
        gestureStartOwn = range.ownValue().getAsInt();
        gestureStartOwnRaw = range.rawOwn().getAsDouble();
        if (!instance.get().equals(gestureStartOwn)) {
            instance.set(gestureStartOwn);
        }
    }

    /**
     * Close the open gesture: journal it as one composite undo step. Reached
     * from release on the widget and from the render loop's button-up check,
     * so a release landing off the widget still commits.
     */
    private void finishGesture() {
        if (!gestureOpen) {
            return;
        }
        gestureOpen = false;
        openGestures.remove(this);
        List<LinkedSliderRange.Linked> links = range.linked();
        Double[] starts = linkedStarts;
        linkedStarts = new Double[links.size()];
        double[] nows = new double[links.size()];
        boolean linkedChanged = false;
        for (int i = 0; i < links.size(); i++) {
            nows[i] = links.get(i).raw().getAsDouble();
            if (starts[i] != null && starts[i] != nows[i]) {
                linkedChanged = true;
            } else {
                starts[i] = null;
            }
        }
        // Raw comparison: a drag that returns to its starting step still
        // rewrote an off-grid value (12.3 -> 12.5) and must be undoable.
        double newOwnRaw = range.rawOwn().getAsDouble();
        if (newOwnRaw == gestureStartOwnRaw && !linkedChanged) {
            return;
        }
        // No options.save(): these back ModConfigSpec values (memory-only
        // writes, flushed by the screen's own close), and saving vanilla's
        // options.txt here rewrote it once per arrow-key auto-repeat.
        onValueChanged.accept(instance.get());
        range.commit().commit(gestureStartOwnRaw, newOwnRaw, starts, nows);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        finishGesture();
    }

    private static LinkedOptionSlider hovered;
    /** Every widget with an open journaling gesture, so a screen-level release
     * or the screen closing can commit gestures whose widget stopped
     * rendering (scrolled out of view) before the button came up. */
    private static final java.util.Set<LinkedOptionSlider> openGestures =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Routes an arrow key to the slider under the mouse — hovering is enough,
     * no click-to-focus needed. A hovered slider wins over a focused one; with
     * nothing hovered, the focused slider gets the key through the screen's
     * normal path. Called from the screen's keyPressed override. Freshness:
     * the static is cleared at the top of every screen frame and re-set only
     * by a slider that actually rendered hovered — a row the list culled
     * (scrolled out of view) freezes with its hovered flag stuck true and
     * neither renders nor un-hovers, so only a per-frame reset can stop it
     * from silently eating arrow keys forever.
     */
    public static boolean hoverArrowKey(int keyCode, int scanCode, int modifiers) {
        LinkedOptionSlider target = hovered;
        return (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)
                && target != null && target.isHovered()
                && target.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Called at the head of every screen render — see {@link #hoverArrowKey}. */
    public static void onFrameStart() {
        hovered = null;
    }

    /** Close every gesture still open — see {@link #openGestures}. */
    public static void closeOpenGestures() {
        for (LinkedOptionSlider slider : openGestures.toArray(new LinkedOptionSlider[0])) {
            slider.finishGesture();
        }
    }

    /** The screen is going away: commit open gestures and forget the hovered
     * widget, so a dead slider can never take an arrow key or keep its screen
     * reachable from a static. */
    public static void onScreenClosed() {
        closeOpenGestures();
        hovered = null;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            // Vanilla's arrow nudge (a pixel's worth of the [0,1] track) is
            // smaller than one int step: the floor in fromSliderValue eats an
            // increase and the idle re-sync snaps it back, making arrows
            // decrease-only. Step by exactly one int step per press through
            // the same apply path, journaled as its own gesture.
            boolean mouseGestureOwns = gestureOpen;
            int stepped = range.fromSliderValue(this.value) + (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            this.value = Mth.clamp(range.toSliderValue(stepped), 0.0, 1.0);
            applyValue();
            updateMessage();
            // An arrow tapped mid-drag joins the mouse's gesture instead of
            // splitting one drag into several undo steps — the drag's own
            // release commits both together.
            if (!mouseGestureOwns) {
                finishGesture();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        if (isHovered()) {
            hovered = this;
        }
        // The release landed off the widget (positional mouseReleased routing
        // never calls onRelease then): close the gesture as soon as the
        // button is up.
        if (gestureOpen && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            finishGesture();
        }
        // Follow pushes from linked sliders while idle — in the instance too,
        // not just the knob, so guards and journaled old values read reality.
        if (!gestureOpen) {
            int own = range.ownValue().getAsInt();
            if (!instance.get().equals(own)) {
                instance.set(own); // consumer no-ops: the config already holds this value
            }
            double synced = range.toSliderValue(own);
            if (Math.abs(synced - this.value) > 1.0E-6) {
                this.value = synced;
                updateMessage();
            }
        }
    }
}
