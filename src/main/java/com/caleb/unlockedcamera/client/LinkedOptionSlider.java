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
final class LinkedOptionSlider extends AbstractOptionSliderButton {
    private final OptionInstance<Integer> instance;
    private final LinkedSliderRange range;
    private final OptionInstance.TooltipSupplier<Integer> tooltipSupplier;
    private final Consumer<Integer> onValueChanged;
    /** Per linked setting: its value when the open gesture first pushed it; null = untouched. */
    private Integer[] linkedStarts;
    /** Own config value when the open gesture started, journaled as the undo target. */
    private int gestureStartOwn;
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
        this.linkedStarts = new Integer[range.linked().size()];
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
        int[] before = new int[links.size()];
        for (int i = 0; i < links.size(); i++) {
            before[i] = links.get(i).value().getAsInt();
        }
        range.onDragValue().accept(range.fromSliderValue(this.value));
        for (int i = 0; i < links.size(); i++) {
            if (linkedStarts[i] == null && links.get(i).value().getAsInt() != before[i]) {
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
        gestureStartOwn = range.ownValue().getAsInt();
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
        List<LinkedSliderRange.Linked> links = range.linked();
        Integer[] starts = linkedStarts;
        linkedStarts = new Integer[links.size()];
        int[] nows = new int[links.size()];
        boolean linkedChanged = false;
        for (int i = 0; i < links.size(); i++) {
            nows[i] = links.get(i).value().getAsInt();
            if (starts[i] != null && starts[i] != nows[i]) {
                linkedChanged = true;
            } else {
                starts[i] = null;
            }
        }
        int newOwn = range.ownValue().getAsInt();
        if (newOwn == gestureStartOwn && !linkedChanged) {
            return;
        }
        options.save();
        onValueChanged.accept(instance.get());
        range.commit().commit(gestureStartOwn, newOwn, starts, nows);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        finishGesture();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            // Vanilla's arrow nudge (a pixel's worth of the [0,1] track) is
            // smaller than one int step: the floor in fromSliderValue eats an
            // increase and the idle re-sync snaps it back, making arrows
            // decrease-only. Step by exactly one int step per press through
            // the same apply path, journaled as its own gesture.
            int stepped = range.fromSliderValue(this.value) + (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            this.value = Mth.clamp(range.toSliderValue(stepped), 0.0, 1.0);
            applyValue();
            updateMessage();
            finishGesture();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
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
