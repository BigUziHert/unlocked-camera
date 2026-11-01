package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;

/** Bounded startup check using the full regression fixture and real server oracles. */
public final class AeroworksModuleSmokeProbe {
    private static final String MODULE = "aeroworks:button_module";
    private static final String MARKER = "AEROWORKS_MODULE_SMOKE";

    private AeroworksModuleSmokeProbe() {}

    @SuppressWarnings("unchecked")
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Only disposable QA copy may run this fixture");
        if (!mc.isSameThread()) throw new IllegalStateException("Client-thread startup required");
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(MODULE));
        if (!BuiltInRegistries.ITEM.getKey(item).toString().equals(MODULE))
            throw new IllegalStateException("Missing smoke module " + MODULE);
        Field itemsField = AeroworksModuleProbe.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        List<Item> items = (List<Item>) itemsField.get(null);
        Path file = game.resolve("qa/aeroworks-regression-smoke.log");
        Files.createDirectories(file.getParent());
        Consumer<String> smokeLog = line -> {
            String scoped = line.replaceFirst("^AEROWORKS_MODULE_REGRESSION", MARKER);
            if (scoped.startsWith(MARKER + " START "))
                scoped = scoped.replaceFirst("modules=\\d+", "modules=1")
                        .replaceFirst("cases=\\d+", "cases=24") + " smokeModule=" + MODULE;
            output.accept(scoped);
            try { Files.writeString(file, scoped + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception error) { output.accept(MARKER + " LOG_WARNING " + error); }
        };
        AeroworksModuleProbe.runRegression(mc, smokeLog);
        // runRegression initializes the registry and queues only the world snapshot.
        // Case preparation begins on a later ClientTickEvent.Post, so narrowing here
        // on the client thread cannot race a case. A later full run re-enumerates it.
        items.clear();
        items.add(item);
    }
}
