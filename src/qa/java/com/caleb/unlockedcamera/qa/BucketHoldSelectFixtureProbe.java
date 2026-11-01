package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.Items;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Selects the prepared slot through the normal packet in one stalled QA case. */
public final class BucketHoldSelectFixtureProbe {
    private BucketHoldSelectFixtureProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (mc.player == null || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Fixture helper requires disposable client");
        if (!(boolean) read("running") || (int) read("index") != 3 || (int) read("phase") != 1 || (boolean) read("restoring")
                || !mc.player.getInventory().getItem(0).is(Items.BUCKET))
            throw new IllegalStateException("Expected only the prepared freelook hold fixture");
        int old = mc.player.getInventory().selected;
        mc.player.getInventory().selected = 0;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(0));
        log.accept("BUCKET_HOLD_SLOT_FIXTURE SELECTED previous=" + old + " selected=0 via=normal_carried_item_packet;existing_prepared_stack_only=true");
    }
    private static Object read(String name) throws Exception {
        Field field = BucketHoldRegressionProbe.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
}
