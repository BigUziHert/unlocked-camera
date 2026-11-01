package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real client requests and server-state oracles for Create/Simulated tools. Disposable QA only. */
public final class CreateInteractionScenario {
    private static final Vec3 PLAYER = new Vec3(1710.5, 200, 190.5);
    private static final BlockPos TARGET = new BlockPos(1711, 201, 193);
    private static final BlockPos SECOND = TARGET.east();
    private static final AABB AREA = new AABB(1704, 199, 184, 1718, 207, 200);
    private static final ArrayDeque<Step> steps = new ArrayDeque<>();
    private static final Map<BlockPos, BlockState> savedBlocks = new LinkedHashMap<>();
    private static final Map<Field, Object> cameraFields = new LinkedHashMap<>();
    private static Consumer<String> output;
    private static boolean running, restoring, fixtureInitialized, registered, freelookMode, oldFreelook, oldFreelookEnabled, oldKeepDirection;
    private static boolean poseEnabled, readinessSent, oldPause, pendingMutation;
    private static String deferredFinish;
    private static volatile boolean serverReady;
    private static String prefix;
    private static KeyMapping freelookKey;
    private static int wait, checks, failures, readinessTicks, pendingTicks;
    private static CompletableFuture<?> pending;
    private static Snapshot snapshot;
    private static CameraType oldCameraType;
    private static boolean oldToggle, oldShoulder, oldShift, oldUse;
    private static double oldAmount, oldMax;
    private static Object glue, honey;
    private static BlockState beforeWrench;
    private static volatile BlockState expectedFixtureState;
    private static Vec3 aimPoint, oldCameraPosition;
    private static float oldCameraYaw, oldCameraPitch, oldCameraRoll;
    private static Method cameraPosition, cameraRotation, dropAim;
    private record Step(int delay, Action action) {}
    @FunctionalInterface private interface Action { void run(Minecraft mc) throws Exception; }
    private record Snapshot(Vec3 position, float yaw, float pitch, GameType mode, boolean flying, int selected,
                            List<ItemStack> inventory) {}

    private CreateInteractionScenario() {}

    public static void run(Minecraft mc, Consumer<String> log, boolean useFreelook) throws Exception {
        if (running) throw new IllegalStateException("Create probe already running");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            throw new IllegalStateException("Create probe requires disposable integrated-server world");
        }
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Create scenario is restricted to the disposable QA copy");
        freelookMode = useFreelook; prefix = useFreelook ? "CREATE_FREELOOK" : "CREATE_INTERACTIONS";
        output = message -> log.accept(message.replace("CREATE_INTERACTIONS", prefix));
        checks = failures = wait = readinessTicks = pendingTicks = 0; restoring = fixtureInitialized = poseEnabled = false;
        pending = null; snapshot = null; savedBlocks.clear(); cameraFields.clear(); steps.clear();
        deferredFinish = null; pendingMutation = false;
        oldCameraType = mc.options.getCameraType();
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldShift = mc.options.keyShift.isDown(); oldUse = mc.options.keyUse.isDown();
        oldPause = mc.options.pauseOnLostFocus; mc.options.pauseOnLostFocus = false;
        Camera camera = mc.gameRenderer.getMainCamera(); oldCameraPosition = camera.getPosition();
        oldCameraYaw = camera.getYRot(); oldCameraPitch = camera.getXRot(); oldCameraRoll = camera.getRoll();
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class); cameraPosition.setAccessible(true);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); cameraRotation.setAccessible(true);
        dropAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); dropAim.setAccessible(true);
        aimPoint = TARGET.getCenter();
        freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        oldFreelook = freelookKey.isDown(); oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "shoulderSide", "freelookYaw", "freelookPitch"}) {
            Field field = field(UnlockedCameraClient.class, name);
            cameraFields.put(field, field.get(null));
        }
        glue = Class.forName("com.simibubi.create.CreateClient").getField("GLUE_HANDLER").get(null);
        honey = manager("HONEY_GLUE_MANAGER");
        if (!registered) { NeoForge.EVENT_BUS.addListener(CreateInteractionScenario::tick); registered = true; }
        running = true;
        output.accept("CREATE_INTERACTIONS START mode=" + (freelookMode ? "first_person_freelook_key_held" : "shoulder")
                + " keepDirection=" + (freelookMode ? false : oldKeepDirection) + " surface=stationary-world");
        add(35, m -> server(m, p -> {
            QaPacketBarrier.install(m.getSingleplayerServer());
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getAbilities().flying, p.getInventory().selected, inventory);
            if (!p.serverLevel().getEntities(p, AREA, e -> true).isEmpty()) {
                throw new IllegalStateException("Fixture contains existing entities");
            }
            for (BlockPos mutable : BlockPos.betweenClosed(1704, 199, 184, 1717, 206, 199)) {
                BlockPos pos = mutable.immutable();
                if (p.serverLevel().getBlockEntity(pos) != null) throw new IllegalStateException("Fixture contains existing block entity " + pos);
                savedBlocks.put(pos, p.serverLevel().getBlockState(pos));
            }
            fixtureInitialized = true;
            for (BlockPos pos : savedBlocks.keySet()) p.serverLevel().setBlock(pos,
                    pos.getY() == 199 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities();
            p.stopUsingItem(); p.closeContainer(); p.getInventory().clearContent();
            p.getInventory().selected = 0; p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
            p.setYRot(0); p.setXRot(0); p.setDeltaMovement(Vec3.ZERO); p.containerMenu.broadcastFullState();
        }));
        add(25, m -> {
            m.setScreen(null); m.player.getInventory().selected = 0;
            ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field(UnlockedCameraClient.class, "active").setBoolean(null, !freelookMode);
            field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
            field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, 0);
            field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
            ClientConfig.ENABLE_FREELOOK.set(true); freelookKey.setDown(freelookMode);
            if (freelookMode) ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
            m.options.setCameraType(freelookMode ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK); shift(m, false);
            poseEnabled = true; dropAim.invoke(null); applyPose(m);
        });
        addWrench("create:gearbox");
        addWrench("create:encased_fan");
        addWrench("create:mechanical_drill");
        addGlue(false);
        addGlue(true);
        addControl("simulated:steering_wheel", "STEERING_WHEEL_MANAGER", true);
        addControl("simulated:throttle_lever", "THROTTLE_LEVER_MANAGER", false);
        add(0, m -> finish(m, "DONE"));
    }

    public static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
                running = false; output.accept("CREATE_INTERACTIONS ABORT world closed; incomplete"); return;
            }
            if (pending != null) {
                if (!pending.isDone()) {
                    if (poseEnabled && !restoring) applyPose(mc);
                    if (++pendingTicks > 1800) {
                        if (!pendingMutation) throw new IllegalStateException("Create packet barrier timeout");
                        if (pendingTicks == 1801) output.accept("CREATE_INTERACTIONS WAIT_MUTATION_DRAIN timeout=true restoring=" + restoring);
                        if (!restoring) deferredFinish = "ABORT";
                    }
                    return;
                }
                pending.join(); pending = null; pendingTicks = 0; pendingMutation = false;
                if (deferredFinish != null) {
                    String deferred = deferredFinish; deferredFinish = null; finish(mc, deferred); return;
                }
            }
            if (restoring) {
                if (snapshot != null) {
                    if (++readinessTicks > 1800) throw new IllegalStateException("Create restoration acknowledgment timeout");
                    if (mc.player.position().distanceToSqr(snapshot.position) > .04) return;
                    if (!readinessSent) {
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(snapshot.selected));
                        readinessSent = true;
                        ordered(mc, p -> serverReady = teleportReady(p) && p.position().distanceToSqr(snapshot.position) < .04
                                && p.gameMode.getGameModeForPlayer() == snapshot.mode && p.getInventory().selected == snapshot.selected);
                        return;
                    }
                    if (!serverReady) { readinessSent = false; return; }
                    mc.player.getInventory().selected = snapshot.selected;
                    mc.player.setYRot(snapshot.yaw); mc.player.setXRot(snapshot.pitch);
                    stabilizeBtp(snapshot.yaw, snapshot.pitch);
                }
                restoring = false; running = false;
                cameraPosition.invoke(mc.gameRenderer.getMainCamera(), oldCameraPosition);
                cameraRotation.invoke(mc.gameRenderer.getMainCamera(), oldCameraYaw, oldCameraPitch, oldCameraRoll);
                mc.options.pauseOnLostFocus = oldPause;
                output.accept("CREATE_INTERACTIONS RESTORED checks=" + checks + " failures=" + failures); return;
            }
            mc.player.setYRot(0); mc.player.yRotO = 0;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            freelookKey.setDown(freelookMode);
            stabilizeBtp();
            if (poseEnabled) applyPose(mc);
            if (wait-- > 0) return;
            Step step = steps.poll();
            if (step == null) { finish(mc, "DONE"); return; }
            step.action.run(mc); wait = step.delay;
        } catch (Throwable error) {
            failures++; output.accept("CREATE_INTERACTIONS ERROR " + error); error.printStackTrace();
            if (restoring) { running = false; output.accept("CREATE_INTERACTIONS RESTORE_ERROR " + error); return; }
            try { finish(mc, "ABORT"); }
            catch (Exception restoreError) { running = false; output.accept("CREATE_INTERACTIONS RESTORE_ERROR " + restoreError); }
        }
    }

    private static void addWrench(String id) {
        add(15, m -> fixture(m, id, "create:wrench"));
        add(0, m -> awaitClientFixture(m, id, "create:wrench"));
        add(12, m -> {
            beforeWrench = m.level.getBlockState(TARGET);
            BlockHitResult hit = aim(m, TARGET, TARGET.getCenter());
            result("WRENCH " + id + " body-ray-distinct", !bodyHits(m, TARGET), "cameraHit=" + hit);
            output.accept("CREATE_INTERACTIONS WRENCH REQUEST " + id + " result="
                    + m.gameMode.useItemOn(m.player, InteractionHand.MAIN_HAND, hit));
        });
        add(3, m -> ordered(m, p -> {
            BlockState after = p.serverLevel().getBlockState(TARGET);
            result("WRENCH " + id + " server-rotation", after.getBlock() == beforeWrench.getBlock() && after != beforeWrench,
                    "before=" + beforeWrench + " after=" + after);
        }));
        add(5, m -> shift(m, true));
        add(12, m -> {
            BlockHitResult hit = aim(m, TARGET, TARGET.getCenter());
            output.accept("CREATE_INTERACTIONS WRENCH_REMOVE REQUEST " + id + " result="
                    + m.gameMode.useItemOn(m.player, InteractionHand.MAIN_HAND, hit));
        });
        add(4, m -> ordered(m, p -> result("WRENCH " + id + " server-removal", p.serverLevel().getBlockState(TARGET).isAir(),
                "after=" + p.serverLevel().getBlockState(TARGET))));
        add(4, m -> shift(m, false));
    }

    private static void addGlue(boolean isHoney) {
        String label = isHoney ? "HONEY_GLUE" : "SUPER_GLUE";
        add(15, m -> {
            stopControls();
            if (!isHoney) glue.getClass().getMethod("discard").invoke(glue);
            fixture(m, "minecraft:stone", isHoney ? "simulated:honey_glue" : "create:super_glue");
        });
        add(0, m -> awaitClientFixture(m, "minecraft:stone", isHoney ? "simulated:honey_glue" : "create:super_glue"));
        add(10, m -> server(m, p -> p.serverLevel().setBlock(SECOND, Blocks.STONE.defaultBlockState(), 3)));
        add(0, m -> awaitSecondBlock(m));
        add(8, m -> glueClick(m, TARGET, isHoney));
        add(12, m -> glueClick(m, SECOND, isHoney));
        add(3, m -> ordered(m, p -> {
            List<Entity> matches = glueEntities(p, isHoney);
            boolean correct = matches.stream().anyMatch(e -> e.getBoundingBox().contains(TARGET.getCenter())
                    && e.getBoundingBox().contains(SECOND.getCenter()));
            result(label + " server-selection", correct, "entities=" + matches.stream()
                    .map(e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " " + e.getBoundingBox()).toList());
        }));
        if (isHoney) {
            add(0, m -> {
                aim(m, TARGET, TARGET.getCenter());
                honey.getClass().getMethod("updateHovered").invoke(honey);
                Object hovered = field(honey.getClass(), "hoveredGlue").get(honey);
                result(label + " hover", hovered instanceof Entity e && e.getBoundingBox().contains(TARGET.getCenter()), "hovered=" + hovered);
                honey.getClass().getMethod("onAttack", int.class, int.class, KeyMapping.class).invoke(honey, 0, 1, m.options.keyAttack);
            });
        } else {
            add(0, m -> {
                aim(m, TARGET, TARGET.getCenter());
                glue.getClass().getMethod("tick").invoke(glue);
                Object selected = field(glue.getClass(), "selected").get(glue);
                result(label + " hover", selected instanceof Entity e && e.getBoundingBox().contains(TARGET.getCenter()), "hovered=" + selected);
                glue.getClass().getMethod("onMouseInput", boolean.class).invoke(glue, true);
            });
        }
        add(12, m -> {});
        add(0, m -> ordered(m, p -> result(label + " server-removal", glueEntities(p, isHoney).isEmpty(),
                "remaining=" + glueEntities(p, isHoney).size())));
    }

    private static void glueClick(Minecraft mc, BlockPos pos, boolean isHoney) throws Exception {
        BlockHitResult hit = aim(mc, pos, pos.getCenter());
        if (isHoney) {
            BlockHitResult ownRay = (BlockHitResult) honey.getClass().getMethod("getHitResult").invoke(honey);
            result("HONEY_GLUE ray " + pos, ownRay.getType() == HitResult.Type.BLOCK && ownRay.getBlockPos().equals(pos), "hit=" + ownRay);
            output.accept("CREATE_INTERACTIONS HONEY_GLUE REQUEST result="
                    + mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
        } else {
            glue.getClass().getMethod("tick").invoke(glue);
            Object hovered = field(glue.getClass(), "hoveredPos").get(glue);
            result("SUPER_GLUE ray " + pos, pos.equals(hovered), "hovered=" + hovered);
            output.accept("CREATE_INTERACTIONS SUPER_GLUE REQUEST result="
                    + glue.getClass().getMethod("onMouseInput", boolean.class).invoke(glue, false));
        }
    }

    private static void addControl(String block, String managerName, boolean wheel) {
        add(20, m -> {
            stopControls(); fixture(m, block, "minecraft:air");
        });
        add(0, m -> awaitClientFixture(m, block, "minecraft:air"));
        add(10, m -> {
            Object handler = manager(managerName);
            BlockHitResult hit;
            if (wheel) {
                BlockState state = m.level.getBlockState(TARGET);
                Method looking = state.getBlock().getClass().getMethod("lookingAtWheel", Player.class,
                        BlockPos.class, float.class, BlockState.class);
                hit = null;
                for (double y : new double[]{.75, .5, .9, .25}) {
                    BlockHitResult candidate = aimMaybe(m, TARGET, new Vec3(TARGET.getX() + .5, TARGET.getY() + y, TARGET.getZ() + .5));
                    if (candidate != null && (boolean) looking.invoke(null, m.player, TARGET, 1f, state)) { hit = candidate; break; }
                }
                if (hit == null) throw new IllegalStateException("Cannot aim wheel fixture: " + state);
            } else {
                BlockState state = m.level.getBlockState(TARGET);
                Vec3 center = state.getShape(m.level, TARGET).bounds().getCenter().add(Vec3.atLowerCornerOf(TARGET));
                hit = aim(m, TARGET, center);
            }
            result(block + " body-ray-distinct", !bodyHits(m, TARGET), "hit=" + hit);
            m.options.keyUse.setDown(true);
            output.accept("CREATE_INTERACTIONS CONTROL REQUEST block=" + block + " result="
                    + m.gameMode.useItemOn(m.player, InteractionHand.MAIN_HAND, hit));
            boolean active = (boolean) handler.getClass().getMethod("isBlockActive", BlockPos.class).invoke(handler, TARGET);
            result(block + " grab", active, "interactionPos=" + handler.getClass().getMethod("getInteractionPos").invoke(handler));
            if (active) {
                // Exercise the real handler's mouse route; its update sends the real packet.
                handler.getClass().getMethod("onMouseMove", double.class, double.class).invoke(handler, 160d, -160d);
            }
        });
        add(4, m -> ordered(m, p -> {
            Object be = p.serverLevel().getBlockEntity(TARGET);
            try {
                double value = wheel ? be.getClass().getField("targetAngle").getFloat(be)
                        : ((Number) be.getClass().getMethod("getState").invoke(be)).doubleValue();
                result(block + " server-movement", Math.abs(value) > .01, "value=" + value);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }));
        add(2, m -> { m.options.keyUse.setDown(false); stopControls(); });
    }

    private static void fixture(Minecraft mc, String block, String item) {
        readinessTicks = 0; readinessSent = serverReady = false; aimPoint = TARGET.getCenter();
        server(mc, p -> {
            ResourceLocation blockId = ResourceLocation.parse(block), itemId = ResourceLocation.parse(item);
            if (!BuiltInRegistries.BLOCK.containsKey(blockId) || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                throw new IllegalStateException("Missing fixture registry entry " + block + " / " + item);
            }
            for (Entity e : p.serverLevel().getEntities((Entity) null, AREA,
                    entity -> entity.getType().toString().contains("glue"))) e.discard();
            p.serverLevel().setBlock(TARGET, Blocks.AIR.defaultBlockState(), 3);
            p.serverLevel().setBlock(SECOND, Blocks.AIR.defaultBlockState(), 3);
            p.serverLevel().setBlock(TARGET.below(), Blocks.STONE.defaultBlockState(), 3);
            BlockState state = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
            // Rotating about a block's own facing axis is intentionally a no-op.
            // Present a side face to the north-facing test ray for these fixtures.
            if (block.equals("create:encased_fan") || block.equals("create:mechanical_drill")) {
                state = state.setValue(BlockStateProperties.FACING, Direction.EAST);
            }
            p.serverLevel().setBlock(TARGET, state, 3);
            expectedFixtureState = state;
            p.stopUsingItem(); p.closeContainer(); p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(itemId)));
            p.getInventory().offhand.set(0, ItemStack.EMPTY); p.getInventory().selected = 0;
            p.containerMenu.broadcastFullState();
        });
    }

    private static void awaitClientFixture(Minecraft mc, String block, String item) throws Exception {
        if (++readinessTicks > 1800) throw new IllegalStateException("Create fixture readiness timeout block=" + block
                + " item=" + item + " position=" + mc.player.position() + " state=" + mc.level.getBlockState(TARGET)
                + " held=" + mc.player.getMainHandItem() + " serverReady=" + serverReady);
        BlockState state = mc.level.getBlockState(TARGET);
        boolean clientReady = mc.player.position().distanceToSqr(PLAYER) < .04
                && state == expectedFixtureState
                && mc.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)))
                && mc.player.getOffhandItem().isEmpty();
        if (!clientReady || freelookMode && UnlockedCameraClient.continuousAimAngles() == null) {
            if (readinessTicks == 1 || readinessTicks % 200 == 0) output.accept("CREATE_INTERACTIONS WAIT_CLIENT block=" + block
                    + " item=" + item + " position=" + mc.player.position() + " state=" + state + " held=" + mc.player.getMainHandItem()
                    + " heldAim=" + java.util.Arrays.toString(UnlockedCameraClient.continuousAimAngles()));
            steps.addFirst(new Step(0, m -> awaitClientFixture(m, block, item))); return;
        }
        if (!readinessSent) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(0));
            // Send a genuine body-rotation packet; the mod's real hold owns any rewrite.
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(0, 0, mc.player.onGround()));
            readinessSent = true;
            ordered(mc, p -> {
                serverReady = teleportReady(p) && p.position().distanceToSqr(PLAYER) < .04
                        && p.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)))
                        && p.serverLevel().getBlockState(TARGET) == expectedFixtureState;
                output.accept("CREATE_INTERACTIONS SERVER_READY block=" + block + " ready=" + serverReady
                        + " held=" + p.getMainHandItem() + " selected=" + p.getInventory().selected + " yaw=" + p.getYRot()
                        + " pitch=" + p.getXRot() + " teleportReady=" + teleportReady(p));
            });
            steps.addFirst(new Step(0, m -> awaitClientFixture(m, block, item))); return;
        }
        if (!serverReady) {
            readinessSent = false; steps.addFirst(new Step(0, m -> awaitClientFixture(m, block, item))); return;
        }
        Method gate = UnlockedCameraClient.class.getDeclaredMethod("crosshairAimActive"); gate.setAccessible(true);
        if (!(boolean) gate.invoke(null) || freelookMode && (!freelookKey.isDown() || mc.options.getCameraType() != CameraType.FIRST_PERSON))
            throw new IllegalStateException("Actual camera interaction gate is inactive");
        output.accept("CREATE_INTERACTIONS CLIENT_READY block=" + block + " item=" + item + " body=" + mc.player.getYRot() + "," + mc.player.getXRot()
                + " camera=" + mc.gameRenderer.getMainCamera().getYRot() + "," + mc.gameRenderer.getMainCamera().getXRot()
                + " freelookKey=" + freelookKey.isDown() + " heldAim=" + java.util.Arrays.toString(UnlockedCameraClient.continuousAimAngles()));
    }

    private static void awaitSecondBlock(Minecraft mc) {
        if (mc.level.getBlockState(SECOND).is(Blocks.STONE)) return;
        if (++readinessTicks > 1800) throw new IllegalStateException("Second glue fixture did not synchronize " + mc.level.getBlockState(SECOND));
        steps.addFirst(new Step(0, CreateInteractionScenario::awaitSecondBlock));
    }

    private static boolean teleportReady(ServerPlayer player) {
        try {
            Field pendingTeleport = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            pendingTeleport.setAccessible(true); return pendingTeleport.get(player.connection) == null;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot verify Create teleport readiness", error); }
    }

    private static BlockHitResult aim(Minecraft mc, BlockPos pos, Vec3 point) throws Exception {
        BlockHitResult hit = aimMaybe(mc, pos, point);
        if (hit == null) throw new IllegalStateException("Fixture not selected " + pos + " hit=" + mc.hitResult
                + " actualBlock=" + mc.level.getBlockState(pos) + " player=" + mc.player.position()
                + " camera=" + mc.gameRenderer.getMainCamera().getPosition() + " point=" + point
                + " held=" + mc.player.getMainHandItem() + " freelookKey=" + freelookKey.isDown());
        return hit;
    }

    private static BlockHitResult aimMaybe(Minecraft mc, BlockPos pos, Vec3 point) throws Exception {
        aimPoint = point; applyPose(mc);
        mc.gameRenderer.pick(1);
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }

    private static void applyPose(Minecraft mc) throws Exception {
        mc.player.getInventory().selected = 0; mc.player.setYRot(0); mc.player.yRotO = 0;
        mc.player.setXRot(0); mc.player.xRotO = 0; stabilizeBtp();
        freelookKey.setDown(freelookMode);
        mc.options.setCameraType(freelookMode ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        field(UnlockedCameraClient.class, "active").setBoolean(null, !freelookMode);
        Vec3 cameraPos = freelookMode ? mc.player.getEyePosition() : mc.player.getEyePosition().add(.95, 0, -4);
        Vec3 d = aimPoint.subtract(cameraPos);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        if (freelookMode) {
            field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, yaw);
            field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, pitch);
            if (!freelookKey.isDown() || mc.options.getCameraType() != CameraType.FIRST_PERSON)
                throw new IllegalStateException("Freelook mode drifted before Create selection");
        }
        cameraPosition.invoke(mc.gameRenderer.getMainCamera(), cameraPos);
        cameraRotation.invoke(mc.gameRenderer.getMainCamera(), yaw, pitch, 0f);
    }

    private static boolean bodyHits(Minecraft mc, BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult body = mc.level.clip(new ClipContext(eye, eye.add(Vec3.directionFromRotation(0, 0).scale(5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return body.getType() == HitResult.Type.BLOCK && body.getBlockPos().equals(pos);
    }

    private static List<Entity> glueEntities(ServerPlayer player, boolean honeyGlue) {
        return player.serverLevel().getEntities((Entity) null, AREA, e -> {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            return honeyGlue ? id.contains("honey_glue") : id.equals("create:super_glue");
        });
    }

    private static void shift(Minecraft mc, boolean down) {
        mc.options.keyShift.setDown(down); mc.player.input.shiftKeyDown = down; mc.player.setShiftKeyDown(down);
    }

    private static Object manager(String name) throws Exception {
        return Class.forName("dev.simulated_team.simulated.index.SimClickInteractions").getField(name).get(null);
    }

    private static void stopControls() throws Exception {
        Class.forName("dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager").getMethod("stop").invoke(null);
    }

    private static void stabilizeBtp() throws Exception {
        stabilizeBtp(0, 0);
    }

    private static void stabilizeBtp(float yaw, float pitch) throws Exception {
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object pose = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, pose);
        field(camera.getClass(), "playerRotation").set(camera, pose);
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        steps.clear();
        if (pendingMutation && pending != null && !pending.isDone()) {
            deferredFinish = status; output.accept("CREATE_INTERACTIONS WAIT_MUTATION_DRAIN finish=" + status); return;
        }
        if (pending != null && !pendingMutation) pending.cancel(false);
        pending = null; poseEnabled = false;
        readinessSent = serverReady = false; readinessTicks = 0;
        stopControls(); glue.getClass().getMethod("discard").invoke(glue);
        Method clearHoney = honey.getClass().getDeclaredMethod("clearAndSwing", Player.class);
        clearHoney.setAccessible(true); clearHoney.invoke(honey, mc.player);
        shift(mc, oldShift); mc.options.keyUse.setDown(oldUse);
        freelookKey.setDown(oldFreelook); ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        for (var entry : cameraFields.entrySet()) entry.getKey().set(null, entry.getValue());
        mc.options.setCameraType(oldCameraType);
        dropAim.invoke(null);
        server(mc, p -> {
            if (fixtureInitialized) {
                for (Entity entity : glueEntities(p, false)) entity.discard();
                for (Entity entity : glueEntities(p, true)) entity.discard();
                for (var entry : savedBlocks.entrySet()) p.serverLevel().setBlock(entry.getKey(), entry.getValue(), 3);
            }
            if (snapshot != null) {
                for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
                p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode);
                p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
                p.teleportTo(p.serverLevel(), snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                p.containerMenu.broadcastFullState();
            }
        });
        restoring = true;
        output.accept("CREATE_INTERACTIONS " + status + " checks=" + checks + " failures=" + failures + " restoration=pending");
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
        pendingTicks = 0; pendingMutation = true;
        pending = server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) throw new IllegalStateException("Player missing");
            action.accept(player);
        });
    }

    private static void ordered(Minecraft mc, Consumer<ServerPlayer> action) {
        pendingTicks = 0; pendingMutation = false; pending = QaPacketBarrier.send(mc, action);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }

    private static void add(int delay, Action action) { steps.add(new Step(delay, action)); }

    private static synchronized void result(String label, boolean pass, String detail) {
        checks++; if (!pass) failures++;
        output.accept("CREATE_INTERACTIONS " + (pass ? "PASS " : "FAIL ") + label + " " + detail);
    }
}
