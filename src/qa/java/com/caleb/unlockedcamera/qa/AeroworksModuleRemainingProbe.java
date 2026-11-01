package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Resume a strictly restored secondary run at its last complete module boundary. */
public final class AeroworksModuleRemainingProbe {
    private static final String RELEASE_SHA = "1efe9e8e96db8e0b83090f4e9ffca354fe377cc4ab08952e914dce7a1b29d57b";
    private static final String ORIGINAL = "AEROWORKS_MODULE_REGRESSION";
    private static final String MARKER = "AEROWORKS_MODULE_REMAINING";

    private AeroworksModuleRemainingProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!mc.isSameThread() || !game.endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Idle primary disposable client thread required");
        if (field("running").getBoolean(null)) throw new IllegalStateException("Aero fixture already active");
        Path root = game.getParent().getParent().getParent();
        Path secondary = root.resolve("build/modpack-block-test/game");
        byte[] recorded = Files.readAllBytes(secondary.resolve("qa/results.log"));
        int releaseStart = Integer.parseInt(Files.readString(root.resolve("build/compat-fix-qa-2026-11-01/modpack-block-test-release-results-start-offset.txt")).trim());
        if (releaseStart < 0 || releaseStart >= recorded.length) throw new IllegalStateException("Invalid final-release evidence offset");
        String all = new String(recorded, releaseStart, recorded.length - releaseStart, StandardCharsets.UTF_8);
        int start = all.lastIndexOf(ORIGINAL + " START ");
        if (start < 0) throw new IllegalStateException("No secondary regression start");
        String body = all.substring(start);
        String restored = body.lines().filter(line -> line.startsWith(ORIGINAL + " RESTORED ")).reduce((a, b) -> b).orElse("");
        if (!body.contains("AEROWORKS_CHECKPOINT_STOP START ") || !body.contains(ORIGINAL + " ABORT ")
                || !body.contains("AEROWORKS_CHECKPOINT_STOP DONE ") || body.contains(ORIGINAL + " DONE ")
                || body.contains("NOT_RESTORED") || !restored.contains("stateRestored=true poseRestored=true teleportAcknowledged=true")
                || !restored.contains("differentInventorySlots=0 differentBlocks=0 remainingFixtureEntities=0"))
            throw new IllegalStateException("Secondary must checkpoint-stop and strictly restore first");

        Class<?> moduleClass = Class.forName("com.mred231.aeroworks.content.controls.module.ModuleItem");
        List<Item> modules = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) if (moduleClass.isInstance(item)) modules.add(item);
        modules.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        String[] consoles = (String[]) field("CONSOLES").get(null);
        String[] modes = (String[]) field("MODES").get(null);
        int perModule = consoles.length * 2 * modes.length, total = modules.size() * perModule;
        if (total != 816 || perModule != 24 || !body.lines().findFirst().orElse("").contains("modules=34 consoles=4 cases=816"))
            throw new IllegalStateException("Unexpected registry/case mapping");
        List<String> rows = body.lines().filter(line -> line.startsWith(ORIGINAL + " PASS ")
                || line.startsWith(ORIGINAL + " FAIL ") || line.startsWith(ORIGINAL + " INCONCLUSIVE ")).toList();
        for (int i = 0; i < rows.size(); i++) {
            String[] words = rows.get(i).split(" ");
            String expected = BuiltInRegistries.ITEM.getKey(modules.get(i / perModule)) + " "
                    + consoles[(i / 6) % consoles.length] + " " + ((i / 3) % 2 == 0 ? "attach" : "remove") + " " + modes[i % 3];
            if (words.length < 6 || !String.join(" ", Arrays.copyOfRange(words, 2, 6)).equals(expected))
                throw new IllegalStateException("Noncontiguous or mismatched secondary row " + i);
        }
        int completed = rows.size(), resumeAt = perModule * (completed / perModule), pending = total - resumeAt;
        if (resumeAt <= 0 || resumeAt >= total) throw new IllegalStateException("No partial module-boundary remainder");

        // Both clients run the same shared production class directory. Verify it
        // against the final release, and compare the copied mod jars byte-for-byte.
        Path release = root.resolve("build/libs/unlockedcamera-1.0.3.jar");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(release)));
        if (!RELEASE_SHA.equals(sha)) throw new IllegalStateException("Release identity changed");
        int classes = 0;
        try (ZipFile zip = new ZipFile(release.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                if (!Arrays.equals(zip.getInputStream(entry).readAllBytes(), Files.readAllBytes(root.resolve("build/classes/java/main").resolve(entry.getName()))))
                    throw new IllegalStateException("Production class identity changed: " + entry.getName());
                classes++;
            }
        }
        if (classes != 52) throw new IllegalStateException("Unexpected production class count " + classes);
        List<String> primaryMods, secondaryMods;
        try (var files = Files.list(game.resolve("mods"))) { primaryMods = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList(); }
        try (var files = Files.list(secondary.resolve("mods"))) { secondaryMods = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList(); }
        if (!primaryMods.equals(secondaryMods)) throw new IllegalStateException("Copied mod lists differ");
        for (String name : primaryMods) if (Files.mismatch(game.resolve("mods").resolve(name), secondary.resolve("mods").resolve(name)) != -1)
            throw new IllegalStateException("Copied mod contents differ: " + name);

        output.accept(MARKER + " PREFLIGHT releaseSha256=" + sha + " productionClasses=" + classes + " identicalCopiedModFiles=" + primaryMods.size()
                + " originalRows=" + completed + " originalRestored=true sourceReleaseByteOffset=" + releaseStart + " resumeAt=" + resumeAt + " total=" + total + " pending=" + pending);
        Consumer<String> remainingLog = line -> {
            String scoped = line.replaceFirst("^" + ORIGINAL, MARKER);
            if (scoped.startsWith(MARKER + " START ")) scoped += " resumeAt=" + resumeAt + " total=" + total + " pending=" + pending + " originalRows=" + completed;
            output.accept(scoped);
        };
        AeroworksModuleProbe.runRegression(mc, remainingLog);
        // Startup queues the snapshot only; the first case is prepared on a later
        // client tick. Starting at a complete module retains every paired control.
        field("index").setInt(null, resumeAt);
    }

    private static Field field(String name) throws Exception {
        Field field = AeroworksModuleProbe.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
