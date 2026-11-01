package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Repairs only AIR at fixed arena fixture positions; never resets an existing block or player state. */
public final class ManualOffsetArenaRepairProbe {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final int CLIENTS_WITHOUT_NEIGHBOR_SHAPES = 2 | 16;
    private record Fixture(BlockPos pos, BlockState state, String role, String[] sign) {}
    private ManualOffsetArenaRepairProbe() {}

    /** probe ManualOffsetArenaRepairProbe */
    public static void run(Minecraft mc, Consumer<String> log) throws Exception { start(mc, log, true); }

    /** probe ManualOffsetArenaRepairProbe$Inspect is read-only apart from its diagnostic file. */
    public static final class Inspect {
        public static void run(Minecraft mc, Consumer<String> log) throws Exception { start(mc, log, false); }
    }

    private static void start(Minecraft mc, Consumer<String> log, boolean apply) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game")) || !mc.isSameThread()
                || mc.player == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Loaded disposable primary integrated client required");
        var transition = ManualOffsetArenaProbe.class.getDeclaredField("running"); transition.setAccessible(true);
        if (transition.getBoolean(null)) throw new IllegalStateException("Arena setup/restore is still running");
        Path directory = game.resolve("qa/manual-offset-arena");
        String pointer = Files.readString(directory.resolve("active.txt")).trim();
        Path snapshot = directory.resolve(pointer).normalize();
        if (!snapshot.getParent().equals(directory)) throw new IllegalStateException("Invalid arena snapshot path");
        CompoundTag original = TagParser.parseTag(Files.readString(snapshot));
        UUID owner = mc.player.getUUID();
        if (!original.getString("version").equals("manual-offset-arena-v1")
                || !original.getString("player").equals(owner.toString())
                || !original.getString("gameDirectory").equals(game.toString()))
            throw new IllegalStateException("Active snapshot does not describe this player's disposable arena");
        List<Fixture> fixtures = fixtures();
        if (!RUNNING.compareAndSet(false, true)) throw new IllegalStateException("Arena repair/inspection already running");
        String prefix = apply ? "MANUAL_OFFSET_ARENA_REPAIR" : "MANUAL_OFFSET_ARENA_INSPECT";
        var server = mc.getSingleplayerServer();
        log.accept(prefix + " START expected=" + fixtures.size() + " mode=" + (apply ? "AIR_ONLY" : "READ_ONLY"));
        server.execute(() -> {
            Path journal = directory.resolve((apply ? "repair-" : "inspect-") + System.currentTimeMillis() + ".snbt");
            try {
                var player = server.getPlayerList().getPlayer(owner);
                if (player == null || player.serverLevel() != server.overworld()
                        || !Files.readString(directory.resolve("active.txt")).trim().equals(pointer)
                        || transition.getBoolean(null)) throw new IllegalStateException("Arena/player changed before callback");
                ServerLevel level = server.overworld();
                for (Fixture f : fixtures) if (!level.hasChunkAt(f.pos))
                    throw new IllegalStateException("Fixture chunk not loaded: " + f.pos);
                List<Fixture> missing = new ArrayList<>();
                ListTag before = new ListTag(), preserved = new ListTag();
                for (Fixture f : fixtures) {
                    BlockState actual = level.getBlockState(f.pos);
                    if (actual.isAir() && level.getBlockEntity(f.pos) == null) {
                        missing.add(f); before.add(entry(f, actual));
                        log.accept(prefix + " MISSING role=" + f.role + " pos=" + f.pos.toShortString() + " expected=" + f.state);
                    } else if (!actual.equals(f.state)) {
                        preserved.add(entry(f, actual));
                        log.accept(prefix + " PRESERVED_NONAIR role=" + f.role + " pos=" + f.pos.toShortString() + " actual=" + actual);
                    }
                }
                CompoundTag record = new CompoundTag();
                record.putString("arenaSnapshot", snapshot.toString()); record.putString("player", owner.toString());
                record.putBoolean("apply", apply); record.putInt("expectedFixtures", fixtures.size());
                record.put("missingBefore", before); record.put("preservedDifferences", preserved);
                record.putString("scope", "fixed_arena_fixtures_only;existing_nonair_and_sign_text_preserved;no_player_inventory_camera_input_changes");
                writeDurable(journal, record); // Closed and forced to disk before any world mutation.
                ListTag outcomes = new ListTag(); int changed = 0, skipped = 0;
                for (Fixture f : missing) {
                    CompoundTag outcome = entry(f, level.getBlockState(f.pos));
                    if (!apply) outcome.putString("result", "INSPECT_ONLY");
                    else if (!level.getBlockState(f.pos).isAir() || level.getBlockEntity(f.pos) != null) {
                        outcome.putString("result", "PRESERVED_CHANGED_SINCE_SCAN"); skipped++;
                    } else if (!f.state.canSurvive(level, f.pos)) {
                        outcome.putString("result", "SKIPPED_UNSUPPORTED"); skipped++;
                        log.accept(prefix + " SKIPPED_UNSUPPORTED role=" + f.role + " pos=" + f.pos.toShortString());
                    } else {
                        if (!level.setBlock(f.pos, f.state, CLIENTS_WITHOUT_NEIGHBOR_SHAPES)
                                || !level.getBlockState(f.pos).equals(f.state))
                            throw new IllegalStateException("Fixture placement did not persist at " + f.pos);
                        if (f.sign != null) {
                            if (!(level.getBlockEntity(f.pos) instanceof SignBlockEntity sign))
                                throw new IllegalStateException("New sign has no block entity at " + f.pos);
                            SignText text = new SignText();
                            for (int i = 0; i < 4; i++) text = text.setMessage(i, Component.literal(f.sign[i]));
                            sign.setText(text, true); sign.setText(text, false); sign.setChanged();
                            level.sendBlockUpdated(f.pos, f.state, f.state, CLIENTS_WITHOUT_NEIGHBOR_SHAPES);
                        }
                        changed++; outcome.putString("result", "REPAIRED");
                        log.accept(prefix + " REPAIRED role=" + f.role + " pos=" + f.pos.toShortString() + " state=" + f.state);
                    }
                    outcomes.add(outcome);
                }
                CompoundTag result = new CompoundTag(); result.putInt("changed", changed); result.putInt("skipped", skipped);
                result.put("outcomes", outcomes); writeDurable(journal.resolveSibling(journal.getFileName() + ".result.snbt"), result);
                log.accept(prefix + " DONE expected=" + fixtures.size() + " missing=" + missing.size() + " changed=" + changed
                        + " skipped=" + skipped + " preserved=" + preserved.size() + " journal=" + journal
                        + " playerInventoryCameraUntouched=true");
            } catch (Throwable error) {
                log.accept(prefix + " ERROR " + error + " journal=" + journal + " partial_changes_if_any_recorded_by_plan_and_REPAIRED_lines");
            } finally { RUNNING.set(false); }
        });
    }

    private static List<Fixture> fixtures() throws Exception {
        var idsField = ManualOffsetArenaProbe.class.getDeclaredField("IDS"); idsField.setAccessible(true);
        var labelsField = ManualOffsetArenaProbe.class.getDeclaredField("LABELS"); labelsField.setAccessible(true);
        String[] ids = ((String[]) idsField.get(null)).clone(), labels = ((String[]) labelsField.get(null)).clone();
        List<Fixture> list = new ArrayList<>();
        for (int x = 1702; x <= 1723; x++) for (int z = 188; z <= 213; z++)
            add(list, x, 159, z, (x % 5 == 0 && z % 5 == 0 ? Blocks.SEA_LANTERN : Blocks.SMOOTH_STONE).defaultBlockState(), "floor");
        for (int i = 0; i < ids.length; i++) {
            int x = 1705 + i % 4 * 5, z = 194 + i / 4 * 5;
            add(list, x, 160, z + 1, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), "station_" + (i + 1) + "_support_lower");
            add(list, x, 161, z + 1, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), "station_" + (i + 1) + "_support_upper");
            ResourceLocation id = ResourceLocation.parse(ids[i]);
            if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalStateException("Missing registered fixture " + id);
            BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            if (i < 13) state = wallNorth(state);
            add(list, x, i < 13 ? 161 : 160, z, state, "station_" + (i + 1));
            sign(list, x, 162, z + 1, "station_" + (i + 1) + "_label", "Station " + (i + 1), labels[i], i < 13 ? "Linked button" : "Offset test", "Empty / wrench");
        }
        add(list, 1710, 160, 192, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), "control_support_lower");
        add(list, 1710, 161, 192, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), "control_support_upper");
        add(list, 1710, 161, 191, wallNorth(Blocks.STONE_BUTTON.defaultBlockState()), "vanilla_control");
        sign(list, 1710, 162, 192, "control_label", "VANILLA", "Control button", "Compare views", "Empty hand");
        for (int x = 1712; x <= 1715; x++) for (int z = 189; z <= 192; z++)
            if (x == 1712 || x == 1715 || z == 189 || z == 192)
                add(list, x, 160, z, Blocks.STONE_BRICKS.defaultBlockState(), "basin_rim");
        sign(list, 1712, 161, 189, "basin_label", "BUCKET BASIN", "Collect / place", "Swap offhand", "Compare views");
        for (int x = 1718; x <= 1721; x++) for (int y = 160; y <= 162; y++)
            add(list, x, y, 189, Blocks.STONE_BRICKS.defaultBlockState(), "glue_wall");
        for (int z = 190; z <= 192; z++) for (int y = 160; y <= 162; y++)
            add(list, 1721, y, z, Blocks.STONE_BRICKS.defaultBlockState(), "glue_corner");
        add(list, 1718, 160, 192, Blocks.STONE_BRICKS.defaultBlockState(), "glue_step");
        add(list, 1719, 160, 192, Blocks.STONE_BRICK_SLAB.defaultBlockState(), "glue_slab");
        sign(list, 1718, 163, 189, "glue_label", "GLUE / BLOCKS", "Walls + corner", "Floor + step", "Swap offhand");
        return list;
    }
    private static BlockState wallNorth(BlockState state) {
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        return state;
    }
    private static void add(List<Fixture> list, int x, int y, int z, BlockState state, String role) {
        list.add(new Fixture(new BlockPos(x, y, z), state, role, null));
    }
    private static void sign(List<Fixture> list, int x, int y, int z, String role, String... text) {
        list.add(new Fixture(new BlockPos(x, y, z), Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 8), role, text));
    }
    private static CompoundTag entry(Fixture f, BlockState actual) {
        CompoundTag tag = new CompoundTag(); tag.putInt("x", f.pos.getX()); tag.putInt("y", f.pos.getY()); tag.putInt("z", f.pos.getZ());
        tag.putString("role", f.role); tag.put("before", NbtUtils.writeBlockState(actual)); tag.put("expected", NbtUtils.writeBlockState(f.state));
        if (f.sign != null) for (int i = 0; i < 4; i++) tag.putString("signLine" + i, f.sign[i]);
        return tag;
    }
    private static void writeDurable(Path path, CompoundTag tag) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(tag.toString());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
}
