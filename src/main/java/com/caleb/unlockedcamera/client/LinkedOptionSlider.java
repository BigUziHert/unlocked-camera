package com.caleb.unlockedcamera.client;

import java.util.function.Consumer;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractOptionSliderButton;
import net.minecraft.util.Mth;

/**
 * The slider behind {@link LinkedSliderRange}. While dragged past the sibling's
 * value it pushes the sibling's config value along in real time; while idle it
 * re-syncs its knob from the config each frame, so being pushed is visible
 * live. A drag applies once on release — applying on a delay like vanilla
 * loses the value when the screen closes first, since the screen's flush only
 * knows vanilla's widget class.
 */
final class LinkedOptionSlider extends AbstractOptionSliderButton {
    private final OptionInstance<Integer> instance;
    private final LinkedSliderRange range;
    private final OptionInstance.TooltipSupplier<Integer> tooltipSupplier;
    private final Consumer<Integer> onValueChanged;
    private boolean dragging;

    LinkedOptionSlider(Options options, int x, int y, int width, int height,
            OptionInstance<Integer> instance, LinkedSliderRange range,
            OptionInstance.TooltipSupplier<Integer> tooltipSupplier, Consumer<Integer> onValueChanged) {
        super(options, x, y, width, height, range.toSliderValue(instance.get()));
        this.instance = instance;
        this.range = range;
        this.tooltipSupplier = tooltipSupplier;
        this.onValueChanged = onValueChanged;
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

    @Override
    protected void applyValue() {
        pinKnob();
        range.onDragValue().accept(range.fromSliderValue(this.value));
        if (!dragging) {
            applyNow();
        }
    }

    private void applyNow() {
        Integer newValue = range.fromSliderValue(this.value);
        if (!newValue.equals(instance.get())) {
            instance.set(newValue);
            options.save();
            onValueChanged.accept(instance.get());
        }
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        dragging = true;
        super.onDrag(mouseX, mouseY, dragX, dragY);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        dragging = false;
        super.onRelease(mouseX, mouseY);
        applyNow();
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        // Follow pushes from the sibling while idle.
        if (!dragging) {
            double synced = range.toSliderValue(range.ownValue().getAsInt());
            if (Math.abs(synced - this.value) > 1.0E-6) {
                this.value = synced;
                updateMessage();
            }
        }
    }
}
