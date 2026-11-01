package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Separate class so an already-loaded resume helper need not be redefined. */
public final class BlockReviewIdentity {
    private BlockReviewIdentity() {}

    /** Adopt the just-completed, continuously loaded review after verifying its evidence. */
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-block-test", "game")) || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Identity adoption restricted to the running secondary QA instance");
        Path qa = game.resolve("qa"), marker = qa.resolve("block-interaction-recheck-fixture-version.txt");
        String expected = fingerprint();
        String previous = Files.exists(marker) ? Files.readString(marker).trim() : "";
        if (previous.equals(expected)) { log.accept("BLOCK_RECHECK PROFILE_ADOPT already_verified=true"); return; }
        if (!previous.equals(BlockInteractionRecheck.FIXTURE_VERSION)) throw new IllegalStateException("Not the known bare hold-release fixture marker");
        String history = Files.readString(qa.resolve("results.log"));
        int start = history.lastIndexOf("BLOCK_RECHECK START ");
        int done = history.lastIndexOf("BLOCK_RECHECK DONE ");
        int restored = history.lastIndexOf("BLOCK_RECHECK RESTORED");
        if (start < 0 || done < start || restored < done) throw new IllegalStateException("Review is not complete and restored");
        String run = history.substring(start, restored);
        if (!run.contains("BLOCK_RECHECK FIXTURE_RELEASE")) throw new IllegalStateException("No executed hold-release evidence in that review");
        var countMatch = Pattern.compile("BLOCK_RECHECK START blocks=(\\d+)").matcher(run);
        if (!countMatch.find()) throw new IllegalStateException("Review inventory absent");
        int count = Integer.parseInt(countMatch.group(1));
        Path outcomes = qa.resolve("block-interaction-recheck.tsv");
        Map<String, Set<String>> pairs = new LinkedHashMap<>();
        int rows = 0;
        for (String line : Files.readAllLines(outcomes)) {
            String[] cells = line.split("\t", -1);
            if (cells.length > 0 && cells[0].equals("block")) continue;
            if (cells.length < 16 || !cells[15].contains("teleportReady=true/true")
                    || !cells[15].contains("freshFixture=true;settleTicks=20"))
                throw new IllegalStateException("Unverified fixture row: " + line);
            pairs.computeIfAbsent(cells[0], ignored -> new LinkedHashSet<>()).add(cells[1]); rows++;
        }
        if (pairs.size() != count || rows != count * 3 || pairs.values().stream().anyMatch(p -> !p.equals(Set.of("0", "1", "2"))))
            throw new IllegalStateException("Incomplete/duplicate review pairs");
        Map<String, String> census = new LinkedHashMap<>(), live = new LinkedHashMap<>();
        Path modsPath = qa.resolve("census/mods.json");
        for (var value : JsonParser.parseString(Files.readString(modsPath)).getAsJsonArray()) {
            JsonObject mod = value.getAsJsonObject(); census.put(mod.get("id").getAsString(), mod.get("version").getAsString());
        }
        ModList.get().getMods().forEach(mod -> live.put(mod.getModId(), mod.getVersion().toString()));
        if (!census.equals(live)) throw new IllegalStateException("Runtime mod list differs from this instance's census");
        Path root = game.getParent().getParent().getParent();
        JsonObject identity = JsonParser.parseString(Files.readString(root.resolve("build/exhaustive-qa/production-identity.json"))).getAsJsonObject();
        if (!identity.getAsJsonArray("differences").isEmpty()
                || identity.get("installedClasses").getAsInt() != identity.get("testedClasses").getAsInt())
            throw new IllegalStateException("Production identity has differences");
        Path installed = Path.of(identity.get("installedJar").getAsString());
        String jarHash = sha(Files.readAllBytes(installed));
        if (!jarHash.equals(identity.get("sha256").getAsString())) throw new IllegalStateException("Installed JAR hash changed");
        int verified = 0;
        try (ZipFile jar = new ZipFile(installed.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); if (!entry.getName().endsWith(".class")) continue;
                if (entry.getName().contains("/qa/")) throw new IllegalStateException("QA class in installed release");
                try (var release = jar.getInputStream(entry); var tested = UnlockedCameraClient.class.getResourceAsStream("/" + entry.getName())) {
                    if (tested == null || !Arrays.equals(release.readAllBytes(), tested.readAllBytes()))
                        throw new IllegalStateException("Production bytes differ for " + entry.getName());
                }
                verified++;
            }
        }
        if (verified != identity.get("testedClasses").getAsInt() || verified == 0) throw new IllegalStateException("Production class count mismatch");
        JsonObject adoption = new JsonObject();
        adoption.addProperty("verifiedAt", Instant.now().toString()); adoption.addProperty("fixtureIdentity", expected);
        adoption.addProperty("reviewBlocks", count); adoption.addProperty("reviewRows", rows);
        adoption.addProperty("reviewSha256", sha(Files.readAllBytes(outcomes)));
        adoption.addProperty("censusModsSha256", sha(Files.readAllBytes(modsPath)));
        adoption.addProperty("runtimeMods", live.size()); adoption.addProperty("productionClasses", verified);
        adoption.addProperty("installedJarSha256", jarHash);
        adoption.addProperty("basis", "Same continuously loaded secondary instance; completed three-pair hold-release review; live census and every production resource byte verified. Metadata adoption, no rerun and no promotion of differences to passes.");
        Files.writeString(qa.resolve("block-interaction-recheck-identity-adoption.json"), adoption.toString());
        Files.writeString(marker, expected);
        log.accept("BLOCK_RECHECK PROFILE_ADOPT verified=true blocks=" + count + " rows=" + rows
                + " productionClasses=" + verified + " liveMods=" + live.size() + " installedSha256=" + jarHash);
    }

    public static String fingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(BlockInteractionRecheck.FIXTURE_VERSION.getBytes(StandardCharsets.UTF_8));
        Class<?> camera = UnlockedCameraClient.class;
        try (var stream = camera.getResourceAsStream("/" + camera.getName().replace('.', '/') + ".class")) {
            if (stream == null) throw new IllegalStateException("Camera class identity unavailable");
            digest.update(stream.readAllBytes());
        }
        String mods = ModList.get().getMods().stream().map(m -> m.getModId() + "=" + m.getVersion())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        digest.update(mods.getBytes(StandardCharsets.UTF_8));
        return BlockInteractionRecheck.FIXTURE_VERSION + "|sha256=" + HexFormat.of().formatHex(digest.digest());
    }

    private static String sha(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
}
