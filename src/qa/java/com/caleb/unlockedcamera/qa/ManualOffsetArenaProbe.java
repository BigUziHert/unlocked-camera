package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** User-operated disposable arena. No tick/input/camera maintenance after READY. */
public final class ManualOffsetArenaProbe {
    private static final BlockPos MIN = new BlockPos(1702, 159, 188), MAX = new BlockPos(1723, 164, 213);
    private static final Vec3 START = new Vec3(1706.45, 160, 190.5);
    private static final AABB AREA = new AABB(Vec3.atLowerCornerOf(MIN), Vec3.atLowerCornerOf(MAX.offset(1, 1, 1)));
    private static final String[] IDS = {
            "create_connected:linked_stone_button", "create_connected:linked_polished_blackstone_button",
            "create_connected:linked_oak_button", "create_connected:linked_spruce_button", "create_connected:linked_birch_button",
            "create_connected:linked_acacia_button", "create_connected:linked_cherry_button", "create_connected:linked_jungle_button",
            "create_connected:linked_dark_oak_button", "create_connected:linked_crimson_button", "create_connected:linked_warped_button",
            "create_connected:linked_mangrove_button", "create_connected:linked_bamboo_button", "create:mechanical_arm",
            "hotstuff:copycat_link_casing", "powergrid:spark_gap"
    };
    private static final String[] LABELS = {"Stone", "Pol. blackstone", "Oak", "Spruce", "Birch", "Acacia", "Cherry", "Jungle",
            "Dark oak", "Crimson", "Warped", "Mangrove", "Bamboo", "Mechanical arm", "Copycat link", "Spark gap"};
    private static final String[] CAMERA_FIELDS = {"active", "targetDistance", "smoothedDistance", "shoulderSide", "freelookYaw", "freelookPitch"};
    private static boolean registered, running, restoring;
    private static int phase, age;
    private static CompletableFuture<Void> pending;
    private static Consumer<String> log;
    private static CompoundTag saved;
    private static Path directory, snapshotPath, optionsBackup;

    private ManualOffsetArenaProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        requireCopy(mc);
        if (running) throw new IllegalStateException("Manual arena transition already running");
        directory = mc.gameDirectory.toPath().resolve("qa/manual-offset-arena").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        log = output;
        if (Files.exists(directory.resolve("active.txt"))) {
            enqueue(mc, p -> registerCommands(mc, p));
            output.accept("MANUAL_OFFSET_ARENA EXISTING snapshot=" + Files.readString(directory.resolve("active.txt")).trim()
                    + " restore_command=/uc_arena_restore no_world_changes=true");
            return;
        }
        for (String id : IDS) if (!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(id))) throw new IllegalStateException("Missing arena block " + id);
        saved = new CompoundTag();
        saved.put("client", captureClient(mc));
        String stamp = Long.toString(System.currentTimeMillis());
        snapshotPath = directory.resolve("snapshot-" + stamp + ".snbt");
        optionsBackup = directory.resolve("options-" + stamp + ".txt");
        Path options = mc.gameDirectory.toPath().resolve("options.txt");
        if (Files.exists(options)) Files.copy(options, optionsBackup);
        saved.putString("optionsBackup", optionsBackup.getFileName().toString());
        saved.putString("gameDirectory", mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
        saved.putString("version", "manual-offset-arena-v1");
        running = true; restoring = false; phase = age = 0;
        if (!registered) { NeoForge.EVENT_BUS.addListener(ManualOffsetArenaProbe::tick); registered = true; }
        log.accept("MANUAL_OFFSET_ARENA START snapshot=" + snapshotPath + " bounds=" + MIN + ".." + MAX);
        standDown(mc);
        enqueue(mc, p -> {
            registerCommands(mc, p);
            if (p.serverLevel() != p.getServer().overworld() || p.isDeadOrDying() || p.isPassenger())
                throw new IllegalStateException("Live unmounted overworld player required");
            saved.putString("player", p.getUUID().toString());
            saved.putDouble("x", p.getX()); saved.putDouble("y", p.getY()); saved.putDouble("z", p.getZ());
            saved.putFloat("yaw", p.getYRot()); saved.putFloat("pitch", p.getXRot());
            saved.putString("mode", p.gameMode.getGameModeForPlayer().getName());
            saved.putBoolean("flying", p.getAbilities().flying); saved.putInt("selected", p.getInventory().selected);
            saved.put("inventory", p.getInventory().save(new ListTag()));
            ListTag blocks = new ListTag(), entities = new ListTag();
            for (Entity e : p.serverLevel().getEntities(p, AREA.inflate(2))) entities.add(net.minecraft.nbt.StringTag.valueOf(e.getUUID().toString()));
            saved.put("existingEntities", entities);
            for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) {
                CompoundTag record = new CompoundTag(); record.putInt("x", pos.getX()); record.putInt("y", pos.getY()); record.putInt("z", pos.getZ());
                record.put("state", NbtUtils.writeBlockState(p.serverLevel().getBlockState(pos)));
                BlockEntity be = p.serverLevel().getBlockEntity(pos);
                if (be != null) record.put("blockEntity", be.saveWithFullMetadata(p.registryAccess()));
                blocks.add(record);
            }
            saved.put("blocks", blocks);
            // Both durable files precede the first world/player mutation.
            Files.writeString(snapshotPath, saved.toString(), StandardOpenOption.CREATE_NEW);
            Files.writeString(directory.resolve("active.txt"), snapshotPath.getFileName().toString(), StandardOpenOption.CREATE_NEW);
            buildArena(p.serverLevel());
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities();
            p.getInventory().clearContent();
            String[] hotbar = {"minecraft:air", "minecraft:stone", "create:super_glue", "create:wrench", "minecraft:shield",
                    "minecraft:bucket", "minecraft:water_bucket", "minecraft:bread", "minecraft:oak_planks"};
            for (int i = 1; i < hotbar.length; i++) p.getInventory().setItem(i, stack(hotbar[i], i == 1 || i >= 7 ? 64 : 1));
            for (int i = 0; i < IDS.length; i++) p.getInventory().setItem(9 + i, stack(IDS[i], 16));
            p.getInventory().selected = 0; p.containerMenu.broadcastFullState();
            p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
            p.teleportTo(p.getServer().overworld(), START.x, START.y, START.z, 0, 2);
            log.accept("MANUAL_OFFSET_ARENA BUILT stations=16 snapshotDurable=true hotbar=empty,stone,glue,wrench,shield,bucket,water_bucket,bread,planks");
        });
    }

    public static void restore(Minecraft mc, Consumer<String> output) throws Exception {
        requireCopy(mc);
        if (running) throw new IllegalStateException("Arena setup/restore must finish first");
        directory = mc.gameDirectory.toPath().resolve("qa/manual-offset-arena").toAbsolutePath().normalize();
        String name = Files.readString(directory.resolve("active.txt")).trim();
        snapshotPath = directory.resolve(name).normalize();
        if (!snapshotPath.getParent().equals(directory)) throw new IllegalStateException("Invalid snapshot path");
        saved = TagParser.parseTag(Files.readString(snapshotPath));
        if (!saved.getString("player").equals(mc.player.getUUID().toString())) throw new IllegalStateException("Snapshot belongs to another player");
        optionsBackup = directory.resolve(saved.getString("optionsBackup")).normalize();
        if (!optionsBackup.getParent().equals(directory)) throw new IllegalStateException("Invalid options backup path");
        log = output; running = restoring = true; phase = age = 0;
        if (!registered) { NeoForge.EVENT_BUS.addListener(ManualOffsetArenaProbe::tick); registered = true; }
        standDown(mc);
        log.accept("MANUAL_OFFSET_ARENA RESTORE_START snapshot=" + snapshotPath);
        enqueue(mc, p -> {
            QaPacketBarrier.install(p.getServer());
            ListTag records = saved.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < records.size(); i++) p.serverLevel().setBlock(position(records.getCompound(i)), Blocks.AIR.defaultBlockState(), 2);
            for (int i = 0; i < records.size(); i++) {
                CompoundTag entry = records.getCompound(i); BlockPos pos = position(entry);
                p.serverLevel().setBlock(pos, decodeState(entry.getCompound("state")), 2);
                if (entry.contains("blockEntity")) {
                    BlockEntity be = p.serverLevel().getBlockEntity(pos);
                    if (be != null) { be.loadWithComponents(entry.getCompound("blockEntity"), p.registryAccess()); be.setChanged(); }
                }
            }
            ListTag oldEntities = saved.getList("existingEntities", Tag.TAG_STRING);
            List<String> keep = new ArrayList<>(); for (int i = 0; i < oldEntities.size(); i++) keep.add(oldEntities.getString(i));
            for (Entity e : p.serverLevel().getEntities(p, AREA.inflate(2))) if (!keep.contains(e.getUUID().toString())) e.discard();
            p.getInventory().load(saved.getList("inventory", Tag.TAG_COMPOUND)); p.getInventory().selected = saved.getInt("selected");
            p.setGameMode(GameType.byName(saved.getString("mode"))); p.getAbilities().flying = saved.getBoolean("flying"); p.onUpdateAbilities();
            p.containerMenu.broadcastFullState(); p.setDeltaMovement(Vec3.ZERO);
            p.teleportTo(p.getServer().overworld(), saved.getDouble("x"), saved.getDouble("y"), saved.getDouble("z"), saved.getFloat("yaw"), saved.getFloat("pitch"));
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (pending != null) { if (!pending.isDone()) return; pending.join(); pending = null; }
            if (phase == 0) {
                Vec3 destination = restoring ? new Vec3(saved.getDouble("x"), saved.getDouble("y"), saved.getDouble("z")) : START;
                if (mc.player.position().distanceToSqr(destination) > .01) {
                    if (++age > 1200) throw new IllegalStateException("Arena teleport did not synchronize; durable snapshot retained");
                    return;
                }
                phase = 1;
                pending = QaPacketBarrier.send(mc, p -> {
                    try {
                        Field awaiting = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
                        awaiting.setAccessible(true);
                        if (awaiting.get(p.connection) != null) throw new IllegalStateException("Arena teleport not acknowledged");
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                });
                return;
            }
            if (restoring) {
                if (Files.exists(optionsBackup)) { Files.write(mc.gameDirectory.toPath().resolve("options.txt"), Files.readAllBytes(optionsBackup)); mc.options.load(); }
                restoreClient(mc, saved.getCompound("client"));
                Files.writeString(snapshotPath.resolveSibling(snapshotPath.getFileName() + ".restored"), "Restored " + System.currentTimeMillis());
                Files.delete(directory.resolve("active.txt"));
                log.accept("MANUAL_OFFSET_ARENA RESTORED snapshot=" + snapshotPath + " scope=saved_arena_bounds_player_inventory_pose_options;outside_manual_edits_not_undone");
                mc.player.displayClientMessage(Component.literal("Arena snapshot restored. Changes outside the arena were not reset."), false);
            } else {
                KeyMapping.releaseAll(); mc.setScreen(null); mc.options.pauseOnLostFocus = false;
                ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_UNLOCKED_CAMERA.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
                ClientConfig.ENABLE_FREELOOK.set(true);
                field("shoulderSide").setInt(null, 1); field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.player.getInventory().selected = 0; mc.player.setYRot(0); mc.player.yRotO = 0; mc.player.setXRot(2); mc.player.xRotO = 2;
                alignBtp(0, 2);
                var enter = UnlockedCameraClient.class.getDeclaredMethod("enterCamera", Minecraft.class); enter.setAccessible(true); enter.invoke(null, mc);
                mc.player.displayClientMessage(Component.literal("Offset test arena ready. You control the camera and movement now."), false);
                mc.player.displayClientMessage(Component.literal("1 empty, 2 stone, 3 glue, 4 wrench, 5 shield, 6 bucket, 7 water. Swap an item to offhand to compare."), false);
                mc.player.displayClientMessage(Component.literal("16 labeled stations south; vanilla button, bucket basin and glue walls near the entrance. /uc_arena_restore restores the saved setup."), false);
                log.accept("MANUAL_OFFSET_ARENA READY player=" + START + " stations=16 naturalCameraEntry=true inputMaintenance=false restoreCommand=/uc_arena_restore snapshot=" + snapshotPath);
            }
            running = false; // No later tick writes player pose, camera, keys, or inventory.
        } catch (Throwable error) {
            running = false; log.accept("MANUAL_OFFSET_ARENA ERROR " + error + " durable_snapshot_retained=" + snapshotPath);
        }
    }

    private static void buildArena(ServerLevel level) throws Exception {
        for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        for (int x = MIN.getX(); x <= MAX.getX(); x++) for (int z = MIN.getZ(); z <= MAX.getZ(); z++)
            level.setBlock(new BlockPos(x, 159, z), (x % 5 == 0 && z % 5 == 0 ? Blocks.SEA_LANTERN : Blocks.SMOOTH_STONE).defaultBlockState(), 3);
        StringBuilder layout = new StringBuilder("Floor x1702..1723 z188..213 atY159. Start1706.45,160,190.5; yaw0 pitch2.\n");
        for (int i = 0; i < IDS.length; i++) {
            int x = 1705 + (i % 4) * 5, z = 194 + (i / 4) * 5;
            level.setBlock(new BlockPos(x, 160, z + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, 161, z + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
            BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(IDS[i])).defaultBlockState();
            if (i < 13) {
                if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) state = state.setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL);
                if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            }
            BlockPos target = new BlockPos(x, i < 13 ? 161 : 160, z);
            level.setBlock(target, state, 3);
            sign(level, new BlockPos(x, 162, z + 1), "Station " + (i + 1), LABELS[i], i < 13 ? "Linked button" : "Offset test", "Empty / wrench");
            layout.append(i + 1).append('\t').append(IDS[i]).append('\t').append(target).append('\n');
        }
        BlockPos control = new BlockPos(1710, 161, 191);
        level.setBlock(control.south(), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
        level.setBlock(control.south().below(), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
        level.setBlock(control, Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), 3);
        sign(level, control.south().above(), "VANILLA", "Control button", "Compare views", "Empty hand");
        for (int x = 1712; x <= 1715; x++) for (int z = 189; z <= 192; z++) if (x == 1712 || x == 1715 || z == 189 || z == 192)
            level.setBlock(new BlockPos(x, 160, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        sign(level, new BlockPos(1712, 161, 189), "BUCKET BASIN", "Collect / place", "Swap offhand", "Compare views");
        for (int x = 1718; x <= 1721; x++) for (int y = 160; y <= 162; y++) level.setBlock(new BlockPos(x, y, 189), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        for (int z = 190; z <= 192; z++) for (int y = 160; y <= 162; y++) level.setBlock(new BlockPos(1721, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(new BlockPos(1718, 160, 192), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(new BlockPos(1719, 160, 192), Blocks.STONE_BRICK_SLAB.defaultBlockState(), 3);
        sign(level, new BlockPos(1718, 163, 189), "GLUE / BLOCKS", "Walls + corner", "Floor + step", "Swap offhand");
        Files.writeString(directory.resolve("layout.txt"), layout + "Hotbar:1empty2stone3glue4wrench5shield6bucket7water8bread9planks. Inventory also contains16 station blocks.\nRestore: /uc_arena_restore\n");
    }

    private static void sign(ServerLevel level, BlockPos pos, String... lines) {
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 8), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText(); for (int i = 0; i < 4; i++) text = text.setMessage(i, Component.literal(lines[i]));
            sign.setText(text, true); sign.setText(text, false); sign.setChanged(); level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        }
    }

    private static CompoundTag captureClient(Minecraft mc) throws Exception {
        CompoundTag tag = new CompoundTag(), config = new CompoundTag(), camera = new CompoundTag();
        tag.putString("cameraType", mc.options.getCameraType().name()); tag.putBoolean("pauseOnLostFocus", mc.options.pauseOnLostFocus);
        for (Field f : ClientConfig.class.getFields()) if (f.get(null) instanceof ModConfigSpec.ConfigValue<?> value) {
            Object v = value.get(); if (v instanceof Boolean b) config.putBoolean(f.getName(), b); else if (v instanceof Number n) config.putDouble(f.getName(), n.doubleValue());
        }
        for (String name : CAMERA_FIELDS) { Field f = field(name); if (f.getType() == boolean.class) camera.putBoolean(name, f.getBoolean(null)); else camera.putDouble(name, ((Number) f.get(null)).doubleValue()); }
        tag.put("config", config); tag.put("camera", camera); return tag;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreClient(Minecraft mc, CompoundTag tag) throws Exception {
        CompoundTag config = tag.getCompound("config"), camera = tag.getCompound("camera");
        for (String name : config.getAllKeys()) {
            ModConfigSpec.ConfigValue value = (ModConfigSpec.ConfigValue) ClientConfig.class.getField(name).get(null);
            value.set(value.get() instanceof Boolean ? config.getBoolean(name) : config.getDouble(name));
        }
        for (String name : CAMERA_FIELDS) {
            Field f = field(name); if (f.getType() == boolean.class) f.setBoolean(null, camera.getBoolean(name));
            else if (f.getType() == int.class) f.setInt(null, (int) camera.getDouble(name)); else f.setFloat(null, (float) camera.getDouble(name));
        }
        mc.options.setCameraType(CameraType.valueOf(tag.getString("cameraType"))); mc.options.pauseOnLostFocus = tag.getBoolean("pauseOnLostFocus");
        alignBtp(saved.getFloat("yaw"), saved.getFloat("pitch"));
    }

    private static void registerCommands(Minecraft mc, ServerPlayer player) {
        QaPacketBarrier.install(player.getServer()); UUID owner = player.getUUID();
        player.getServer().getCommands().getDispatcher().register(Commands.literal("uc_arena_restore").executes(context -> {
            if (!context.getSource().getPlayerOrException().getUUID().equals(owner)) return 0;
            mc.execute(() -> { try { restore(mc, log); } catch (Exception e) { log.accept("MANUAL_OFFSET_ARENA RESTORE_ERROR " + e); } }); return 1;
        }));
    }
    private static void requireCopy(Minecraft mc) {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game"))
                || mc.player == null || mc.getSingleplayerServer() == null || !mc.isSameThread()) throw new IllegalStateException("Disposable primary integrated client required");
    }
    private static void standDown(Minecraft mc) throws Exception {
        field("active").setBoolean(null, false); ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(false);
        var drop = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); drop.setAccessible(true); drop.invoke(null);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
    }
    private static void alignBtp(float yaw, float pitch) throws Exception {
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager); if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation"); Object value = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, value);
        Field body = camera.getClass().getDeclaredField("playerRotation"); body.setAccessible(true); body.set(camera, value);
    }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static ItemStack stack(String id, int count) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count); }
    private static BlockPos position(CompoundTag tag) { return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState decodeState(CompoundTag tag) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(tag.getString("Name"))).defaultBlockState();
        CompoundTag props = tag.getCompound("Properties");
        for (String name : props.getAllKeys()) { Property property = state.getBlock().getStateDefinition().getProperty(name);
            if (property != null) { var value = property.getValue(props.getString(name)); if (value.isPresent()) state = state.setValue(property, (Comparable) value.get()); } }
        return state;
    }
    @FunctionalInterface private interface ServerAction { void run(ServerPlayer player) throws Exception; }
    private static void enqueue(Minecraft mc, ServerAction action) {
        CompletableFuture<Void> complete = new CompletableFuture<>(); pending = complete; UUID player = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            try { action.run(mc.getSingleplayerServer().getPlayerList().getPlayer(player)); complete.complete(null); }
            catch (Throwable error) { complete.completeExceptionally(error); }
        });
    }
}
