package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.*;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import net.minecraft.client.resources.DefaultPlayerSkin;

import javax.vecmath.Vector3f;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, value = Dist.CLIENT)
public class ClientRagdollRenderer {

    // Player models
    private static PlayerModel<AbstractClientPlayer> normalModel;
    private static PlayerModel<AbstractClientPlayer> slimModel;
    private static HumanoidModel<AbstractClientPlayer> normalArmorInner;
    private static HumanoidModel<AbstractClientPlayer> normalArmorOuter;
    private static HumanoidModel<AbstractClientPlayer> slimArmorInner;
    private static HumanoidModel<AbstractClientPlayer> slimArmorOuter;

    // Mob models
    private static HumanoidModel<?> standardHumanoidModel;
    private static SkeletonModel<?> skeletonModel;
    private static IllagerModel<?> illagerModel;
    private static DrownedModel<?> drownedModel;
    private static CreeperModel<?> creeperModel;
    // PiglinModel extends PlayerModel/HumanoidModel with extra ear/nose/tusk cubes on the
    // head — using it instead of the bare HumanoidModel for piglin/piglin_brute/zombified
    // so the ears actually render. Without this they look like bald zombies.
    private static net.minecraft.client.model.PiglinModel<?> piglinModel;

    // Overlay models — second-layer copies baked from inflated layer definitions.
    // Mirror the base humanoid/quadruped part structure exactly so the same setPos /
    // physics-driven rotations apply unchanged.
    private static DrownedModel<?> drownedOuterModel;     // sea-grass over drowned body
    private static SkeletonModel<?> strayClothingModel;   // tattered clothes over stray
    private static CreeperModel<?> creeperPoweredModel;   // electric swirl on charged creepers
    private static ModelPart pigSaddleRoot;               // saddle on saddled pigs

    // Mob armor
    private static HumanoidModel<?> mobArmorInner;
    private static HumanoidModel<?> mobArmorOuter;

    // Quadruped roots
    private static ModelPart cowRoot;
    private static ModelPart sheepRoot;
    private static ModelPart sheepFurRoot; // wool overlay layer for non-sheared sheep
    private static ModelPart pigRoot;
    private static ModelPart chickenRoot;

    private static final ResourceLocation SHEEP_FUR_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/sheep/sheep_fur.png");
    private static final ResourceLocation DROWNED_OUTER_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/zombie/drowned_outer_layer.png");
    private static final ResourceLocation STRAY_OUTER_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/skeleton/stray_overlay.png");
    private static final ResourceLocation CREEPER_POWERED_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/creeper/creeper_armor.png");
    private static final ResourceLocation PIG_SADDLE_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/pig/pig_saddle.png");

    // Reusable quaternion to avoid per-part allocations
    private static final Quaternionf tempQuat = new Quaternionf();

    private static boolean initialized = false;

    // Render-thread timing exposed for ClientRagdollManager's perf log. Distinguishes
    // FPS drops caused by the render path (this class) vs the tick path (the manager).
    public static volatile long lastRenderFrameNanos = 0;
    public static volatile int lastRenderedCount = 0;
    public static volatile int lastCulledCount = 0;
    private static final long[] renderFrameRing = new long[60]; // ~1s at 60fps
    private static int renderFrameRingIdx = 0;
    private static int renderFrameRingFilled = 0;

    public static long avgRenderFrameNanos() {
        if (renderFrameRingFilled == 0) return 0;
        long sum = 0;
        for (int i = 0; i < renderFrameRingFilled; i++) sum += renderFrameRing[i];
        return sum / renderFrameRingFilled;
    }

    private static void initModels() {
        if (initialized) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            var bakery = mc.getEntityModels();

            // Player models
            normalModel = new PlayerModel<>(bakery.bakeLayer(ModelLayers.PLAYER), false);
            slimModel = new PlayerModel<>(bakery.bakeLayer(ModelLayers.PLAYER_SLIM), true);
            normalArmorInner = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            normalArmorOuter = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
            slimArmorInner = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            slimArmorOuter = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));

            // Mob models
            LayerDefinition standardDef = LayerDefinition.create(
                    HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 64);
            standardHumanoidModel = new HumanoidModel<>(standardDef.bakeRoot());

            skeletonModel = new SkeletonModel<>(SkeletonModel.createBodyLayer().bakeRoot());
            illagerModel = new IllagerModel<>(IllagerModel.createBodyLayer().bakeRoot());
            drownedModel = new DrownedModel<>(DrownedModel.createBodyLayer(CubeDeformation.NONE).bakeRoot());
            creeperModel = new CreeperModel<>(CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot());
            // Use the bakery's already-baked PIGLIN layer so we share the same ModelPart
            // tree (and the same cube inflations) as the live PiglinRenderer.
            piglinModel = new net.minecraft.client.model.PiglinModel<>(
                    bakery.bakeLayer(net.minecraft.client.model.geom.ModelLayers.PIGLIN));

            // Overlay models — baked from the same vanilla layer-definition keys the
            // entity renderer uses, so the per-cube inflation matches exactly.
            drownedOuterModel = new DrownedModel<>(bakery.bakeLayer(ModelLayers.DROWNED_OUTER_LAYER));
            strayClothingModel = new SkeletonModel<>(bakery.bakeLayer(ModelLayers.STRAY_OUTER_LAYER));
            creeperPoweredModel = new CreeperModel<>(bakery.bakeLayer(ModelLayers.CREEPER_ARMOR));

            cowRoot = net.minecraft.client.model.CowModel.createBodyLayer().bakeRoot();
            sheepRoot = net.minecraft.client.model.SheepModel.createBodyLayer().bakeRoot();
            // SheepFurModel is the inflated wool-overlay layer that vanilla SheepFurLayer
            // renders on top of the sheared body. Children mirror SheepModel exactly
            // (body, head, four legs) so the same setPos values overlay correctly.
            sheepFurRoot = net.minecraft.client.model.SheepFurModel.createFurLayer().bakeRoot();
            pigRoot = net.minecraft.client.model.PigModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
            // Pig saddle — vanilla SaddleLayer uses ModelLayers.PIG_SADDLE which is an
            // inflated copy of the pig's body shape. Same child names as the base pig
            // model so the same setPos calls work for both.
            pigSaddleRoot = bakery.bakeLayer(ModelLayers.PIG_SADDLE);
            chickenRoot = ChickenModel.createBodyLayer().bakeRoot();

            mobArmorInner = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            mobArmorOuter = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));

            // Set all model parts visible once — no need to do this every frame
            setAllPartsVisible(normalModel);
            setAllPartsVisible(slimModel);
            setAllPartsVisible(standardHumanoidModel);
            setAllPartsVisible(skeletonModel);
            setAllPartsVisible(drownedModel);
            setAllPartsVisible(drownedOuterModel);
            setAllPartsVisible(strayClothingModel);
            setAllPartsVisible(piglinModel);
            makeAllChildrenVisible(creeperPoweredModel.root());
            makeAllChildrenVisible(cowRoot);
            makeAllChildrenVisible(sheepRoot);
            makeAllChildrenVisible(sheepFurRoot);
            makeAllChildrenVisible(pigRoot);
            makeAllChildrenVisible(pigSaddleRoot);
            makeAllChildrenVisible(chickenRoot);

            initialized = true;
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to initialize ClientRagdollRenderer models", e);
        }
    }

    private static void setAllPartsVisible(HumanoidModel<?> model) {
        makeAllChildrenVisible(model.head);
        makeAllChildrenVisible(model.body);
        makeAllChildrenVisible(model.leftArm);
        makeAllChildrenVisible(model.rightArm);
        makeAllChildrenVisible(model.leftLeg);
        makeAllChildrenVisible(model.rightLeg);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        var ragdolls = ClientRagdollManager.getAll();
        if (ragdolls.isEmpty()) {
            recordRenderFrame(0, 0, 0);
            return;
        }

        initModels();
        if (!initialized) return;

        long renderStart = System.nanoTime();

        PoseStack poseStack = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        float partialTick = event.getPartialTick();

        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();

        int rendered = 0;
        int culled = 0;
        for (ClientRagdoll ragdoll : ragdolls) {
            // Grab the snapshot once per ragdoll. Everything we render off this
            // ragdoll uses the same snapshot for consistency — even if physics
            // publishes a new one mid-frame, our render stays coherent.
            ClientRagdoll.TransformSnapshot snap = ragdoll.getSnapshot();
            if (snap == null || snap.destroyed) continue;

            // Update render-side EMA smoothing for this frame. Damps physics jitter
            // (oscillation under contact pressure) without affecting real motion.
            ragdoll.updateSmoothedRenderState(snap, partialTick);

            // Distance culling — 48 blocks. Use the smoothed torso pos so the cull
            // boundary itself doesn't jitter (caused pop-in artifacts at the edge).
            Vector3f torsoPos = ragdoll.getSmoothedTorsoPos();
            double distSq = camPos.distanceToSqr(torsoPos.x, torsoPos.y, torsoPos.z);
            if (distSq > 2304.0) {
                culled++;
                continue;
            }

            int light = getLightLevel(torsoPos);

            poseStack.pushPose();
            try {
                poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

                if (ragdoll.isPlayer()) {
                    renderPlayerRagdoll(ragdoll, snap, poseStack, buffer, light, partialTick, distSq);
                } else {
                    renderMobRagdoll(ragdoll, snap, poseStack, buffer, light, partialTick, distSq);
                }
                rendered++;
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Error rendering ragdoll {}", ragdoll.getId(), e);
            } finally {
                poseStack.popPose();
            }
        }

        buffer.endBatch();

        recordRenderFrame(System.nanoTime() - renderStart, rendered, culled);
    }

    private static void recordRenderFrame(long nanos, int rendered, int culled) {
        lastRenderFrameNanos = nanos;
        lastRenderedCount = rendered;
        lastCulledCount = culled;
        renderFrameRing[renderFrameRingIdx] = nanos;
        renderFrameRingIdx = (renderFrameRingIdx + 1) % renderFrameRing.length;
        if (renderFrameRingFilled < renderFrameRing.length) renderFrameRingFilled++;
    }

    private static int getLightLevel(Vector3f pos) {
        // Sample light from slightly above the torso to avoid sampling inside the block
        // the ragdoll is resting on (which would return darkness/black overlay)
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y + 0.5f);
        int z = (int) Math.floor(pos.z);
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(x, y, z);
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return 15728880; // full bright fallback
        // If the sampled position is inside a solid block, try one block above
        if (level.getBlockState(blockPos).isSolidRender(level, blockPos)) {
            blockPos = blockPos.above();
        }
        return net.minecraft.client.renderer.LevelRenderer.getLightColor(level, blockPos);
    }

    // ============================
    // Player ragdoll rendering
    // ============================

    /**
     * Y offset for ragdolls that have settled on a fluid surface. The physics body is
     * frozen (removed from the dynamics world), so a ragdoll just floating on water would
     * otherwise be perfectly static. A gentle sin-driven bob applied at render time fakes
     * surface motion without re-running physics. Phase varies per ragdoll so a row of
     * corpses on a pond doesn't bob in lockstep.
     */
    private static float liquidBobOffset(ClientRagdoll ragdoll) {
        if (!ragdoll.isSettledOnLiquid()) return 0f;
        long now = System.currentTimeMillis();
        float phase = (ragdoll.getId() & 0xFF) * 0.0246f; // ~0..2π spread across 256 ids
        return (float) (Math.sin(now * 0.0044 + phase) * 0.06f); // ~0.7 Hz, ±0.06 blocks
    }

    private static void renderPlayerRagdoll(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snap,
                                            PoseStack poseStack,
                                            MultiBufferSource buffer, int light, float partialTick, double distSq) {
        // Resolve the player entity once per render-pass instead of three separate linear
        // scans of mc.level.players() (isSlimModel + getPlayerTexture + findPlayerEntity).
        UUID uuid = ragdoll.getPlayerUUID();
        AbstractClientPlayer playerEntity = findPlayerEntity(uuid);
        boolean isSlim = playerEntity != null
                ? "slim".equals(playerEntity.getModelName())
                : (uuid != null && DefaultPlayerSkin.getSkinModelName(uuid).equals("slim"));
        PlayerModel<AbstractClientPlayer> model = isSlim ? slimModel : normalModel;

        RagdollTransform torso = ragdoll.getSmoothedTransform(RagdollPart.TORSO);
        RagdollTransform head = ragdoll.getSmoothedTransform(RagdollPart.HEAD);
        RagdollTransform larm = ragdoll.getSmoothedTransform(RagdollPart.LEFT_ARM);
        RagdollTransform rarm = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_ARM);
        RagdollTransform lleg = ragdoll.getSmoothedTransform(RagdollPart.LEFT_LEG);
        RagdollTransform rleg = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_LEG);

        if (torso == null) return;

        poseStack.pushPose();
        try {
            // Per-part relative offsets are computed against torso.position (unmodified),
            // so adding the bob only at the outer translate lifts every part uniformly.
            float bob = liquidBobOffset(ragdoll);
            poseStack.translate(torso.position.x, torso.position.y + bob, torso.position.z);

            ResourceLocation skin = playerEntity != null
                    ? playerEntity.getSkinTextureLocation()
                    : (uuid != null ? DefaultPlayerSkin.getDefaultSkin(uuid) : DefaultPlayerSkin.getDefaultSkin());
            VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(skin));

            renderHumanoidPartPhysics(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO);
            renderHumanoidPartPhysics(poseStack, vc, model.head, head, torso, light, RagdollPart.HEAD);
            renderHumanoidPartPhysics(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.leftArm, larm, torso, light, RagdollPart.LEFT_ARM);
            renderHumanoidPartPhysics(poseStack, vc, model.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM);

            // Skip armor at distance — invisible beyond 24 blocks
            if (distSq <= 576.0) {
                renderPlayerVanillaArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim);

                // GeckoLib armor only within 16 blocks. Requires a real player entity —
                // skip if the player has disconnected or isn't loaded.
                if (distSq <= 256.0 && playerEntity != null) {
                    renderPlayerGeckoLibArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim, playerEntity);
                }
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Error rendering player ragdoll", e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderPlayerVanillaArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                                  int light, RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg, boolean isSlim) {
        HumanoidModel<AbstractClientPlayer> innerModel = isSlim ? slimArmorInner : normalArmorInner;
        HumanoidModel<AbstractClientPlayer> outerModel = isSlim ? slimArmorOuter : normalArmorOuter;

        renderVanillaArmorSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel);
        renderVanillaArmorSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel);
        renderVanillaArmorSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel);
        renderVanillaArmorSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel);
    }

    private static void renderVanillaArmorSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                                MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                HumanoidModel<?> innerModel, HumanoidModel<?> outerModel) {
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        if (GeckoLibArmorHelper.isGeckoLibArmor(item)) return; // handled separately
        if (!(item instanceof ArmorItem armorItem)) return;

        ResourceLocation armorTexture = getArmorTexture(armorItem, slot);
        VertexConsumer vc = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

        switch (slot) {
            case HEAD:
                renderHumanoidPartPhysics(poseStack, vc, outerModel.head, head, torso, light, RagdollPart.HEAD);
                break;
            case CHEST:
                renderHumanoidPartPhysics(poseStack, vc, innerModel.body, torso, torso, light, RagdollPart.TORSO);
                renderHumanoidPartPhysics(poseStack, vc, innerModel.leftArm, larm, torso, light, RagdollPart.LEFT_ARM);
                renderHumanoidPartPhysics(poseStack, vc, innerModel.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM);
                break;
            case LEGS:
                renderHumanoidPartPhysics(poseStack, vc, innerModel.body, torso, torso, light, RagdollPart.TORSO);
                renderHumanoidPartPhysics(poseStack, vc, innerModel.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
                renderHumanoidPartPhysics(poseStack, vc, innerModel.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
                break;
            case FEET:
                renderHumanoidPartPhysics(poseStack, vc, outerModel.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
                renderHumanoidPartPhysics(poseStack, vc, outerModel.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
                break;
        }
    }

    private static void renderPlayerGeckoLibArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                                   int light, RagdollTransform torso, RagdollTransform head,
                                                   RagdollTransform larm, RagdollTransform rarm,
                                                   RagdollTransform lleg, RagdollTransform rleg, boolean isSlim,
                                                   AbstractClientPlayer playerEntity) {
        // playerEntity is resolved once in renderPlayerRagdoll and passed in; the caller
        // already null-checked, so no second lookup required.
        HumanoidModel<AbstractClientPlayer> baseModel = isSlim ? slimArmorInner : normalArmorInner;

        renderGeckoLibSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
    }

    private static AbstractClientPlayer findPlayerEntity(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p.getUUID().equals(uuid)) return p;
            }
        }
        return null;
    }

    private static void renderGeckoLibSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                            MultiBufferSource buffer, int light, HumanoidModel<?> baseModel,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg,
                                            net.minecraft.world.entity.LivingEntity entity) {
        if (stack.isEmpty()) return;
        if (!GeckoLibArmorHelper.isGeckoLibArmor(stack.getItem())) return;

        baseModel.setAllVisible(false);
        baseModel.young = false;
        baseModel.crouching = false;
        baseModel.riding = false;

        switch (slot) {
            case HEAD:
                if (head != null) {
                    baseModel.head.visible = true;
                    resetPart(baseModel.head);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, head, torso, RagdollPart.HEAD, entity);
                    baseModel.head.visible = false;
                }
                break;
            case CHEST:
                if (torso != null && larm != null && rarm != null) {
                    baseModel.body.visible = true;
                    resetPart(baseModel.body);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO, entity);
                    baseModel.body.visible = false;

                    baseModel.leftArm.visible = true;
                    resetPart(baseModel.leftArm);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, larm, torso, RagdollPart.LEFT_ARM, entity);
                    baseModel.leftArm.visible = false;

                    baseModel.rightArm.visible = true;
                    resetPart(baseModel.rightArm);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rarm, torso, RagdollPart.RIGHT_ARM, entity);
                    baseModel.rightArm.visible = false;
                }
                break;
            case LEGS:
                if (torso != null && lleg != null && rleg != null) {
                    baseModel.body.visible = true;
                    resetPart(baseModel.body);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO, entity);
                    baseModel.body.visible = false;

                    baseModel.leftLeg.visible = true;
                    resetPart(baseModel.leftLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG, entity);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    resetPart(baseModel.rightLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG, entity);
                    baseModel.rightLeg.visible = false;
                }
                break;
            case FEET:
                if (lleg != null && rleg != null) {
                    baseModel.leftLeg.visible = true;
                    resetPart(baseModel.leftLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG, entity);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    resetPart(baseModel.rightLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG, entity);
                    baseModel.rightLeg.visible = false;
                }
                break;
        }
    }

    private static void renderGeckoLibPartPhysics(ItemStack stack, EquipmentSlot slot,
                                                   PoseStack poseStack, MultiBufferSource buffer, int light,
                                                   HumanoidModel<?> baseModel,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   RagdollPart ragdollPart,
                                                   net.minecraft.world.entity.LivingEntity entity) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);

            float centerX = 0, centerY = 0, centerZ = 0;
            switch (ragdollPart) {
                case HEAD:      centerY = 4f / 16f;   break;
                case TORSO:     centerY = -6f / 16f;   break;
                case LEFT_ARM:  centerX = -1f / 16f; centerY = -4f / 16f; break;
                case RIGHT_ARM: centerX = 1f / 16f;  centerY = -4f / 16f; break;
                case LEFT_LEG:
                case RIGHT_LEG: centerY = -6f / 16f;   break;
            }
            poseStack.translate(centerX, centerY, centerZ);

            GeckoLibArmorHelper.renderGeckoLibArmor(stack, slot, entity, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, baseModel);
        } finally {
            poseStack.popPose();
        }
    }

    // ============================
    // Mob ragdoll rendering
    // ============================

    private static void renderMobRagdoll(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snap,
                                          PoseStack poseStack,
                                          MultiBufferSource buffer, int light, float partialTick, double distSq) {
        MobModelHelper.ModelType modelType = ragdoll.getModelType();

        RagdollTransform torso = ragdoll.getSmoothedTransform(RagdollPart.TORSO);
        RagdollTransform head = ragdoll.getSmoothedTransform(RagdollPart.HEAD);
        RagdollTransform larm = ragdoll.getSmoothedTransform(RagdollPart.LEFT_ARM);
        RagdollTransform rarm = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_ARM);
        RagdollTransform lleg = ragdoll.getSmoothedTransform(RagdollPart.LEFT_LEG);
        RagdollTransform rleg = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_LEG);

        if (torso == null) return;

        poseStack.pushPose();
        try {
            float bob = liquidBobOffset(ragdoll);
            poseStack.translate(torso.position.x, torso.position.y + bob, torso.position.z);

            ResourceLocation texture = getMobTexture(ragdoll);
            VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

            switch (modelType) {
                case CREEPER:
                    renderCreeper(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case QUADRUPED:
                    renderQuadruped(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case CHICKEN:
                    renderChicken(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case ILLAGER:
                    renderIllager(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case HUMANOID_SKELETON:
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, skeletonModel);
                    break;
                case HUMANOID_DROWNED:
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, drownedModel);
                    break;
                default:
                    // Piglins (regular, brute, zombified) render through PiglinModel so the
                    // ear/nose/tusk cubes are present. Everything else humanoid-default
                    // uses the standard model.
                    HumanoidModel<?> humanoidModel = ragdoll.getMobType().contains("piglin")
                            ? piglinModel : standardHumanoidModel;
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidModel);
                    break;
            }

            // Extra overlay layers (drowned outer, stray clothes, charged-creeper swirl,
            // pig saddle, sheep wool, …). Resolved as data via the OverlayRegistry —
            // adding a new layer is one entry in overlaysFor.
            for (MobOverlay overlay : overlaysFor(ragdoll)) {
                renderOverlay(overlay, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);
            }

            // Mob armor — humanoid mobs only, skip beyond 24 blocks
            if (isHumanoidType(modelType) && distSq <= 576.0) {
                renderMobVanillaArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);

                // GeckoLib armor within 16 blocks
                if (distSq <= 256.0) {
                    renderMobGeckoLibArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);
                }
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Error rendering mob ragdoll", e);
        } finally {
            poseStack.popPose();
        }
    }

    private static boolean isHumanoidType(MobModelHelper.ModelType type) {
        return type == MobModelHelper.ModelType.HUMANOID_STANDARD ||
                type == MobModelHelper.ModelType.HUMANOID_SKELETON ||
                type == MobModelHelper.ModelType.HUMANOID_DROWNED ||
                type == MobModelHelper.ModelType.ILLAGER;
    }

    private static void renderHumanoidMob(PoseStack poseStack, VertexConsumer vc, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg,
                                           HumanoidModel<?> model) {
        renderHumanoidPartPhysics(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO);
        renderHumanoidPartPhysics(poseStack, vc, model.head, head, torso, light, RagdollPart.HEAD);
        renderHumanoidPartPhysics(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
        renderHumanoidPartPhysics(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
        renderHumanoidPartPhysics(poseStack, vc, model.leftArm, larm, torso, light, RagdollPart.LEFT_ARM);
        renderHumanoidPartPhysics(poseStack, vc, model.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM);
    }

    private static void renderIllager(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = illagerModel.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftArm = root.getChild("left_arm");
        ModelPart rightArm = root.getChild("right_arm");

        try {
            root.getChild("arms").visible = false;
        } catch (Exception ignored) {}
        leftArm.visible = true;
        rightArm.visible = true;

        renderHumanoidPartPhysics(poseStack, vc, body, torso, torso, light, RagdollPart.TORSO);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head, torso, light, RagdollPart.HEAD);
        renderHumanoidPartPhysics(poseStack, vc, leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
        renderHumanoidPartPhysics(poseStack, vc, rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
        renderHumanoidPartPhysics(poseStack, vc, leftArm, larm, torso, light, RagdollPart.LEFT_ARM);
        renderHumanoidPartPhysics(poseStack, vc, rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM);
    }

    private static void renderCreeper(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc,
                                       MultiBufferSource buffer, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = creeperModel.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");

        renderHumanoidPartPhysics(poseStack, vc, body, torso, torso, light, RagdollPart.TORSO);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head, torso, light, RagdollPart.HEAD);

        renderAnimalPart(poseStack, vc, leftHind, lleg, torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vc, rightHind, rleg, torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vc, leftFront, larm, torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vc, rightFront, rarm, torso, 0, -3, 0, 0, light);
        // Charged-creeper energy swirl handled by the OverlayRegistry dispatch in
        // renderMobRagdoll — no inline rendering here.
    }

    private static void renderQuadruped(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc,
                                         MultiBufferSource buffer, int light,
                                         RagdollTransform torso, RagdollTransform head,
                                         RagdollTransform larm, RagdollTransform rarm,
                                         RagdollTransform lleg, RagdollTransform rleg) {
        String mobType = ragdoll.getMobType();
        boolean isSheep = mobType.contains("sheep");
        ModelPart root;
        if (mobType.contains("cow") || mobType.contains("mooshroom")) root = cowRoot;
        else if (isSheep) root = sheepRoot;
        else if (mobType.contains("pig")) root = pigRoot;
        else root = cowRoot;

        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");

        float halfPI = (float) (Math.PI / 2);
        // setPos values are derived from each ModelPart's cube bbox center (post-bake-time
        // pi/2 X rotation for the body) so the cube's geometric center coincides with
        // its physics body's transform origin. Replaces the previous per-mob hardcoded
        // constants which were eyeballed and noticeably off for sheep + chicken wings.
        org.joml.Vector3f bodyOff       = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff       = setPosForPart(headPart, 0);
        org.joml.Vector3f leftHindOff   = setPosForPart(leftHind, 0);
        org.joml.Vector3f rightHindOff  = setPosForPart(rightHind, 0);
        org.joml.Vector3f leftFrontOff  = setPosForPart(leftFront, 0);
        org.joml.Vector3f rightFrontOff = setPosForPart(rightFront, 0);

        float bodyScale = ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f;
        renderAnimalPart(poseStack, vc, body,       torso, torso, bodyOff.x,       bodyOff.y,       bodyOff.z,       halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart,   head,  torso, headOff.x,       headOff.y,       headOff.z,       0,      light);
        renderAnimalPart(poseStack, vc, leftHind,   lleg,  torso, leftHindOff.x,   leftHindOff.y,   leftHindOff.z,   0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind,  rleg,  torso, rightHindOff.x,  rightHindOff.y,  rightHindOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront,  larm,  torso, leftFrontOff.x,  leftFrontOff.y,  leftFrontOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm,  torso, rightFrontOff.x, rightFrontOff.y, rightFrontOff.z, 0,      light, bodyScale);
        // Pig saddle + sheep wool overlays are handled by the OverlayRegistry dispatch
        // in renderMobRagdoll — no inline rendering here.
    }

    private static void renderChicken(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = chickenRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart beak = root.getChild("beak");
        ModelPart redThing = root.getChild("red_thing");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftWing = root.getChild("left_wing");
        ModelPart rightWing = root.getChild("right_wing");

        float halfPI = (float) (Math.PI / 2);
        // beak and redThing share the head's pivot in the vanilla model — their cubes are
        // designed at offsets relative to the same pivot as head, so they piggyback on
        // the head's setPos rather than computing their own.
        org.joml.Vector3f bodyOff      = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff      = setPosForPart(headPart, 0);
        org.joml.Vector3f leftLegOff   = setPosForPart(leftLeg, 0);
        org.joml.Vector3f rightLegOff  = setPosForPart(rightLeg, 0);
        org.joml.Vector3f leftWingOff  = setPosForPart(leftWing, 0);
        org.joml.Vector3f rightWingOff = setPosForPart(rightWing, 0);

        float bodyScale = ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f;
        renderAnimalPart(poseStack, vc, body,      torso, torso, bodyOff.x,      bodyOff.y,      bodyOff.z,      halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart,  head,  torso, headOff.x,      headOff.y,      headOff.z,      0,      light);
        renderAnimalPart(poseStack, vc, beak,      head,  torso, headOff.x,      headOff.y,      headOff.z,      0,      light);
        renderAnimalPart(poseStack, vc, redThing,  head,  torso, headOff.x,      headOff.y,      headOff.z,      0,      light);
        renderAnimalPart(poseStack, vc, leftLeg,   lleg,  torso, leftLegOff.x,   leftLegOff.y,   leftLegOff.z,   0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightLeg,  rleg,  torso, rightLegOff.x,  rightLegOff.y,  rightLegOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, leftWing,  larm,  torso, leftWingOff.x,  leftWingOff.y,  leftWingOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightWing, rarm,  torso, rightWingOff.x, rightWingOff.y, rightWingOff.z, 0,      light, bodyScale);
    }

    private static void renderMobVanillaArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                               int light, RagdollTransform torso, RagdollTransform head,
                                               RagdollTransform larm, RagdollTransform rarm,
                                               RagdollTransform lleg, RagdollTransform rleg) {
        renderVanillaArmorSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter);
        renderVanillaArmorSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter);
        renderVanillaArmorSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter);
        renderVanillaArmorSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter);
    }

    private static void renderMobGeckoLibArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                                int light, RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg) {
        // GeckoLib uses the proxy ArmorStand inside GeckoLibArmorHelper — no real entity needed
        renderGeckoLibSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null);
        renderGeckoLibSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null);
        renderGeckoLibSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null);
        renderGeckoLibSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null);
    }

    // ============================
    // Core render methods
    // ============================

    /**
     * Tinted variant of {@link #renderHumanoidPartPhysics}, used for the charged-creeper
     * energy-swirl overlay where the part is drawn at half RGB intensity.
     */
    private static void renderHumanoidPartPhysicsTinted(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                         RagdollTransform transform, RagdollTransform torso,
                                                         int light, RagdollPart ragdollPart,
                                                         float r, float g, float b, float a) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);

            switch (ragdollPart) {
                case HEAD:      part.setPos(0, 4, 0);    break;
                case TORSO:     part.setPos(0, -6, 0);   break;
                case LEFT_ARM:  part.setPos(-1, -4, 0);  break;
                case RIGHT_ARM: part.setPos(1, -4, 0);   break;
                case LEFT_LEG:
                case RIGHT_LEG: part.setPos(0, -6, 0);   break;
            }
            part.xRot = 0;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY, r, g, b, a);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderHumanoidPartPhysics(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   int light, RagdollPart ragdollPart) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);

            switch (ragdollPart) {
                case HEAD:      part.setPos(0, 4, 0);    break;
                case TORSO:     part.setPos(0, -6, 0);   break;
                case LEFT_ARM:  part.setPos(-1, -4, 0);  break;
                case RIGHT_ARM: part.setPos(1, -4, 0);   break;
                case LEFT_LEG:
                case RIGHT_LEG: part.setPos(0, -6, 0);   break;
            }
            part.xRot = 0;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderAnimalPart(PoseStack poseStack, VertexConsumer vc,
                                          ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                          float setPosX, float setPosY, float setPosZ,
                                          float defaultXRot, int light) {
        renderAnimalPart(poseStack, vc, part, transform, torso, setPosX, setPosY, setPosZ, defaultXRot, light, 1.0f);
    }

    private static void renderAnimalPart(PoseStack poseStack, VertexConsumer vc,
                                          ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                          float setPosX, float setPosY, float setPosZ,
                                          float defaultXRot, int light, float modelScale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            if (modelScale != 1.0f) {
                poseStack.scale(modelScale, modelScale, modelScale);
            }

            part.setPos(setPosX, setPosY, setPosZ);
            part.xRot = defaultXRot;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Variant of {@link #renderAnimalPart} that tints the part with an RGB multiplier.
     * Used for the sheep wool overlay so each dye color renders without needing a
     * separate texture. ModelPart's 8-arg render pushes the (r,g,b,a) floats to the
     * shader as the per-vertex color, identical to how vanilla SheepFurLayer does it.
     */
    private static void renderAnimalPartTinted(PoseStack poseStack, VertexConsumer vc,
                                                ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                                float setPosX, float setPosY, float setPosZ,
                                                float defaultXRot, int light,
                                                float r, float g, float b) {
        renderAnimalPartTinted(poseStack, vc, part, transform, torso,
                setPosX, setPosY, setPosZ, defaultXRot, light, r, g, b, 1.0f);
    }

    private static void renderAnimalPartTinted(PoseStack poseStack, VertexConsumer vc,
                                                ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                                float setPosX, float setPosY, float setPosZ,
                                                float defaultXRot, int light,
                                                float r, float g, float b, float modelScale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            if (modelScale != 1.0f) {
                poseStack.scale(modelScale, modelScale, modelScale);
            }

            part.setPos(setPosX, setPosY, setPosZ);
            part.xRot = defaultXRot;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY, r, g, b, 1f);
        } finally {
            poseStack.popPose();
        }
    }

    // ============================
    // Helpers
    // ============================

    private static void resetPart(ModelPart part) {
        part.setPos(0, 0, 0);
        part.xRot = 0;
        part.yRot = 0;
        part.zRot = 0;
    }

    private static void makeAllChildrenVisible(ModelPart part) {
        part.visible = true;
        part.getAllParts().forEach(p -> p.visible = true);
    }

    private static ResourceLocation getMobTexture(ClientRagdoll ragdoll) {
        ResourceLocation cached = ragdoll.getCachedTexture();
        if (cached != null) return cached;

        ResourceLocation fromCache = ClientMobTextureCache.getTextureForDeadMob(ragdoll.getOriginalEntityId());
        if (fromCache != null) {
            ragdoll.setCachedTexture(fromCache);
            return fromCache;
        }

        ResourceLocation fallback = getFallbackTexture(ragdoll.getMobType());
        ragdoll.setCachedTexture(fallback);
        return fallback;
    }

    private static ResourceLocation getFallbackTexture(String mobType) {
        if (mobType.contains("zombie") && !mobType.contains("piglin")) {
            if (mobType.contains("husk")) return new ResourceLocation("minecraft", "textures/entity/zombie/husk.png");
            if (mobType.contains("drowned")) return new ResourceLocation("minecraft", "textures/entity/zombie/drowned.png");
            if (mobType.contains("villager")) return new ResourceLocation("minecraft", "textures/entity/zombie_villager/zombie_villager.png");
            return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        }
        if (mobType.contains("skeleton")) {
            if (mobType.contains("wither")) return new ResourceLocation("minecraft", "textures/entity/skeleton/wither_skeleton.png");
            if (mobType.contains("stray")) return new ResourceLocation("minecraft", "textures/entity/skeleton/stray.png");
            return new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");
        }
        if (mobType.contains("piglin")) {
            if (mobType.contains("brute")) return new ResourceLocation("minecraft", "textures/entity/piglin/piglin_brute.png");
            if (mobType.contains("zombified")) return new ResourceLocation("minecraft", "textures/entity/piglin/zombified_piglin.png");
            return new ResourceLocation("minecraft", "textures/entity/piglin/piglin.png");
        }
        if (mobType.contains("pillager")) return new ResourceLocation("minecraft", "textures/entity/illager/pillager.png");
        if (mobType.contains("vindicator")) return new ResourceLocation("minecraft", "textures/entity/illager/vindicator.png");
        if (mobType.contains("evoker")) return new ResourceLocation("minecraft", "textures/entity/illager/evoker.png");
        if (mobType.contains("illusioner")) return new ResourceLocation("minecraft", "textures/entity/illager/illusioner.png");
        if (mobType.contains("villager")) return new ResourceLocation("minecraft", "textures/entity/villager/villager.png");
        if (mobType.contains("wandering_trader")) return new ResourceLocation("minecraft", "textures/entity/wandering_trader.png");
        if (mobType.contains("creeper")) return new ResourceLocation("minecraft", "textures/entity/creeper/creeper.png");
        if (mobType.contains("cow")) return new ResourceLocation("minecraft", "textures/entity/cow/cow.png");
        if (mobType.contains("mooshroom")) return new ResourceLocation("minecraft", "textures/entity/cow/mooshroom.png");
        if (mobType.contains("sheep")) return new ResourceLocation("minecraft", "textures/entity/sheep/sheep.png");
        if (mobType.contains("pig")) return new ResourceLocation("minecraft", "textures/entity/pig/pig.png");
        if (mobType.contains("chicken")) return new ResourceLocation("minecraft", "textures/entity/chicken.png");
        return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
    }

    private static ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot) {
        try {
            String texturePath = item.getArmorTexture(new ItemStack(item), null, slot, null);
            if (texturePath != null && !texturePath.isEmpty()) {
                try { return new ResourceLocation(texturePath); } catch (Exception ignored) {}
            }
            String materialName = item.getMaterial().getName();
            if (materialName.contains(":")) {
                materialName = materialName.substring(materialName.lastIndexOf(":") + 1);
            }
            materialName = switch (materialName.toLowerCase()) {
                case "leather" -> "leather";
                case "chainmail", "chain" -> "chainmail";
                case "iron" -> "iron";
                case "gold", "golden" -> "gold";
                case "diamond" -> "diamond";
                case "netherite" -> "netherite";
                default -> materialName;
            };
            String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
            return new ResourceLocation("minecraft", "textures/models/armor/" + materialName + "_" + layer + ".png");
        } catch (Exception e) {
            return new ResourceLocation("minecraft", "textures/models/armor/leather_layer_1.png");
        }
    }

    // ============================
    // Overlay descriptor system — extra layers (sea-grass on drowned, saddle on pig,
    // wool on sheep, energy swirl on charged creepers, …) drawn on top of the base
    // model. Each MobOverlay subtype targets one body shape (humanoid / quadruped /
    // creeper) so the dispatcher can call the matching render path. Add a new overlay
    // by appending an entry inside overlaysFor; villager profession layers (task 5)
    // will plug in here as several stacked HumanoidOverlay descriptors.
    // ============================

    public sealed interface MobOverlay permits HumanoidOverlay, IllagerOverlay, QuadrupedOverlay, CreeperSwirlOverlay {}

    /** Full HumanoidModel render (drowned outer, stray clothing, zombie-villager profession). */
    public record HumanoidOverlay(ResourceLocation texture, HumanoidModel<?> model) implements MobOverlay {}

    /** Illager-shaped overlay (villager profession layers — IllagerModel extends
     *  HierarchicalModel, not HumanoidModel, so it needs its own dispatch).
     *  {@code limitToHeadBody}: vanilla villager textures are designed for VillagerModel's
     *  UV layout, where arms are at a different texOffs than IllagerModel's separate
     *  left/right arms — so applying them to IllagerModel arms pulls hat/body texture
     *  data onto the arm cubes (the "farmer hat on stick arms" bug). Pass true for
     *  villager profession layers to skip arms+legs and only overlay head+body, where
     *  the texture UVs do match between the two models. */
    public record IllagerOverlay(ResourceLocation texture, ModelPart root, boolean limitToHeadBody) implements MobOverlay {}

    /** Per-part quadruped overlay (pig saddle, sheep wool); pass tint=1,1,1 for plain. */
    public record QuadrupedOverlay(ResourceLocation texture, ModelPart root, float r, float g, float b, float bodyScale) implements MobOverlay {}

    /** Charged-creeper energy swirl — special RenderType + half-RGB tint + UV scroll. */
    public record CreeperSwirlOverlay(ResourceLocation texture, CreeperModel<?> model) implements MobOverlay {}

    /**
     * Resolve all overlays that should render on top of {@code ragdoll}'s base model.
     * Dispatched on (modelType + mobType + per-mob state flags). Order matters: each
     * overlay is drawn after the previous one, so later entries appear on top.
     */
    private static java.util.List<MobOverlay> overlaysFor(ClientRagdoll ragdoll) {
        java.util.List<MobOverlay> result = new java.util.ArrayList<>(2);
        String mobType = ragdoll.getMobType();
        MobModelHelper.ModelType modelType = ragdoll.getModelType();

        if (modelType == MobModelHelper.ModelType.HUMANOID_DROWNED && drownedOuterModel != null) {
            // Always-on sea-grass overlay; no entity state needed.
            result.add(new HumanoidOverlay(DROWNED_OUTER_TEXTURE, drownedOuterModel));
        }
        if (modelType == MobModelHelper.ModelType.HUMANOID_SKELETON
                && mobType.contains("stray") && strayClothingModel != null) {
            result.add(new HumanoidOverlay(STRAY_OUTER_TEXTURE, strayClothingModel));
        }
        if (modelType == MobModelHelper.ModelType.CREEPER && ragdoll.isChargedCreeper()
                && creeperPoweredModel != null) {
            result.add(new CreeperSwirlOverlay(CREEPER_POWERED_TEXTURE, creeperPoweredModel));
        }
        if (modelType == MobModelHelper.ModelType.QUADRUPED) {
            if (mobType.contains("pig") && ragdoll.isSaddledPig() && pigSaddleRoot != null) {
                result.add(new QuadrupedOverlay(PIG_SADDLE_TEXTURE, pigSaddleRoot, 1f, 1f, 1f,
                        ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f));
            }
            if (mobType.contains("sheep") && !ragdoll.wasSheared() && sheepFurRoot != null) {
                net.minecraft.world.item.DyeColor color =
                        net.minecraft.world.item.DyeColor.byId(ragdoll.getDyeColorId() & 0xF);
                float[] rgb = color.getTextureDiffuseColors();
                result.add(new QuadrupedOverlay(SHEEP_FUR_TEXTURE, sheepFurRoot, rgb[0], rgb[1], rgb[2],
                        ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f));
            }
        }
        // Villager / zombie villager profession layers — three stacked textures (biome
        // type → profession → profession level) drawn over the base humanoid model.
        // Empty profession key (level 0 too) means "vanilla nitwit / unknown" — skip.
        if (modelType == MobModelHelper.ModelType.ILLAGER
                && !ragdoll.getVillagerType().isEmpty()
                && !ragdoll.getVillagerProfession().isEmpty()) {
            // Resolve the namespace from the registry-key string. "minecraft:plains" splits
            // to namespace="minecraft", path="plains". Fall back to vanilla namespace if
            // the key was malformed.
            net.minecraft.resources.ResourceLocation typeKey;
            net.minecraft.resources.ResourceLocation profKey;
            try {
                typeKey = new net.minecraft.resources.ResourceLocation(ragdoll.getVillagerType());
                profKey = new net.minecraft.resources.ResourceLocation(ragdoll.getVillagerProfession());
            } catch (Exception e) {
                return result;
            }

            boolean isZombieVillager = mobType.contains("zombie_villager");
            String basePath = isZombieVillager ? "textures/entity/zombie_villager/" : "textures/entity/villager/";
            // Vanilla VillagerProfessionLayer skips the profession overlay entirely when
            // profession == NONE (there is no profession/none.png in the jar — rendering
            // it gives the purple-and-black missing-texture indicator). Level layer also
            // skipped for NONE and NITWIT.
            boolean isNoneProfession = profKey.getNamespace().equals("minecraft")
                    && profKey.getPath().equals("none");
            boolean isNitwit = profKey.getNamespace().equals("minecraft")
                    && profKey.getPath().equals("nitwit");

            ModelPart illagerRoot = illagerModel.root();

            // 1. Biome-type overlay — always rendered, even for "none" profession.
            ResourceLocation typeTex = new ResourceLocation(typeKey.getNamespace(),
                    basePath + "type/" + typeKey.getPath() + ".png");
            result.add(new IllagerOverlay(typeTex, illagerRoot, true));

            // 2. Profession overlay — skip for NONE (matches vanilla, avoids missing texture).
            if (!isNoneProfession) {
                ResourceLocation profTex = new ResourceLocation(profKey.getNamespace(),
                        basePath + "profession/" + profKey.getPath() + ".png");
                result.add(new IllagerOverlay(profTex, illagerRoot, true));

                // 3. Profession-level necklace — skip for NONE and NITWIT.
                int level = ragdoll.getVillagerLevel();
                if (!isNitwit && level >= 1 && level <= 5) {
                    String levelName = switch (level) {
                        case 1 -> "stone";
                        case 2 -> "iron";
                        case 3 -> "gold";
                        case 4 -> "emerald";
                        case 5 -> "diamond";
                        default -> null;
                    };
                    if (levelName != null) {
                        ResourceLocation levelTex = new ResourceLocation("minecraft",
                                "textures/entity/villager/profession_level/" + levelName + ".png");
                        result.add(new IllagerOverlay(levelTex, illagerRoot, true));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Dispatch a single overlay to its matching render path. The transforms passed in are
     * the base model's transforms — overlays share the same physics bodies as the base.
     */
    private static void renderOverlay(MobOverlay overlay, PoseStack poseStack, MultiBufferSource buffer, int light,
                                      RagdollTransform torso, RagdollTransform head,
                                      RagdollTransform larm, RagdollTransform rarm,
                                      RagdollTransform lleg, RagdollTransform rleg) {
        if (overlay instanceof HumanoidOverlay h) {
            VertexConsumer ovc = buffer.getBuffer(RenderType.entityCutoutNoCull(h.texture()));
            renderHumanoidMob(poseStack, ovc, light, torso, head, larm, rarm, lleg, rleg, h.model());
        } else if (overlay instanceof IllagerOverlay i) {
            renderIllagerOverlayParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, i);
        } else if (overlay instanceof QuadrupedOverlay q) {
            renderQuadrupedOverlayParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, q);
        } else if (overlay instanceof CreeperSwirlOverlay c) {
            renderCreeperSwirlParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, c);
        }
    }

    /** Per-part illager-shaped overlay rendering (villager profession layers). Mirrors
     *  renderIllager's part-by-part dispatch but with a different texture per call. */
    private static void renderIllagerOverlayParts(PoseStack poseStack, MultiBufferSource buffer, int light,
                                                  RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg,
                                                  IllagerOverlay overlay) {
        ModelPart root = overlay.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftArm = root.getChild("left_arm");
        ModelPart rightArm = root.getChild("right_arm");

        // IllagerModel default is to render arms together as a single "arms" part — we
        // hide that on each render so only the per-side arms draw, matching the base
        // illager render path.
        try { root.getChild("arms").visible = false; } catch (Exception ignored) {}
        leftArm.visible = true;
        rightArm.visible = true;

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(overlay.texture()));
        renderHumanoidPartPhysics(poseStack, vc, body,     torso, torso, light, RagdollPart.TORSO);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head,  torso, light, RagdollPart.HEAD);
        if (!overlay.limitToHeadBody()) {
            renderHumanoidPartPhysics(poseStack, vc, leftLeg,  lleg,  torso, light, RagdollPart.LEFT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, rightLeg, rleg,  torso, light, RagdollPart.RIGHT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, leftArm,  larm,  torso, light, RagdollPart.LEFT_ARM);
            renderHumanoidPartPhysics(poseStack, vc, rightArm, rarm,  torso, light, RagdollPart.RIGHT_ARM);
        }
    }

    /** Per-part quadruped overlay rendering — same setPos derivation as the base model. */
    private static void renderQuadrupedOverlayParts(PoseStack poseStack, MultiBufferSource buffer, int light,
                                                    RagdollTransform torso, RagdollTransform head,
                                                    RagdollTransform larm, RagdollTransform rarm,
                                                    RagdollTransform lleg, RagdollTransform rleg,
                                                    QuadrupedOverlay overlay) {
        ModelPart root = overlay.root();
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");
        ModelPart rightFront = root.getChild("right_front_leg");

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(overlay.texture()));
        float halfPI = (float) (Math.PI / 2);
        float r = overlay.r(), g = overlay.g(), b = overlay.b();

        org.joml.Vector3f bodyOff      = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff      = setPosForPart(headPart, 0);
        org.joml.Vector3f lHindOff     = setPosForPart(leftHind, 0);
        org.joml.Vector3f rHindOff     = setPosForPart(rightHind, 0);
        org.joml.Vector3f lFrontOff    = setPosForPart(leftFront, 0);
        org.joml.Vector3f rFrontOff    = setPosForPart(rightFront, 0);

        renderAnimalPartTinted(poseStack, vc, body,       torso, torso, bodyOff.x,    bodyOff.y,    bodyOff.z,    halfPI, light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, headPart,   head,  torso, headOff.x,    headOff.y,    headOff.z,    0,      light, r, g, b);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg,  torso, lHindOff.x,   lHindOff.y,   lHindOff.z,   0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg,  torso, rHindOff.x,   rHindOff.y,   rHindOff.z,   0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm,  torso, lFrontOff.x,  lFrontOff.y,  lFrontOff.z,  0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm,  torso, rFrontOff.x,  rFrontOff.y,  rFrontOff.z,  0,      light, r, g, b, overlay.bodyScale());
    }

    /** Charged-creeper energy-swirl overlay. Vanilla EnergySwirlLayer renders the
     *  inflated creeper armor model at half RGB with time-driven UV scrolling — corpses
     *  don't tick so we sub in wall-clock time for the same continuous shimmer. */
    private static void renderCreeperSwirlParts(PoseStack poseStack, MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                CreeperSwirlOverlay overlay) {
        ModelPart root = overlay.model().root();
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");
        ModelPart rightFront = root.getChild("right_front_leg");

        float t = (System.currentTimeMillis() % 100_000L) / 1000.0f;
        float u = (t * 0.01f) % 1.0f;
        float v = (t * 0.01f) % 1.0f;
        VertexConsumer vc = buffer.getBuffer(RenderType.energySwirl(overlay.texture(), u, v));

        renderHumanoidPartPhysicsTinted(poseStack, vc, body,     torso, torso, light, RagdollPart.TORSO, 0.5f, 0.5f, 0.5f, 1f);
        renderHumanoidPartPhysicsTinted(poseStack, vc, headPart, head,  torso, light, RagdollPart.HEAD,  0.5f, 0.5f, 0.5f, 1f);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
    }

    /**
     * Cache: ModelPart → cube bbox center in part-local pixel coords. ModelPart instances
     * live for the renderer's lifetime (held in static fields above) and their cubes never
     * change after bake, so the cached value stays valid. WeakHashMap so reload doesn't leak.
     */
    private static final java.util.Map<ModelPart, org.joml.Vector3f> CUBE_CENTER_CACHE =
            new java.util.WeakHashMap<>();

    /**
     * Geometric center of all of {@code part}'s own cubes (children excluded), in part-local
     * pixel coordinates. The renderer overrides each part's pivot via setPos, so this center
     * is what we need to invert (and rotate) to make the cube coincide with its physics body.
     * Reads {@code ModelPart.cubes} reflectively because it's private; cached after first read.
     */
    private static org.joml.Vector3f cubeBoxCenter(ModelPart part) {
        org.joml.Vector3f cached = CUBE_CENTER_CACHE.get(part);
        if (cached != null) return cached;

        java.util.List<ModelPart.Cube> cubes;
        try {
            cubes = net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                    ModelPart.class, part, "f_104212_");
        } catch (Exception e) {
            Ragdollified.LOGGER.warn("Could not access ModelPart cubes via reflection — falling back to zero offset", e);
            org.joml.Vector3f zero = new org.joml.Vector3f();
            CUBE_CENTER_CACHE.put(part, zero);
            return zero;
        }

        if (cubes == null || cubes.isEmpty()) {
            org.joml.Vector3f zero = new org.joml.Vector3f();
            CUBE_CENTER_CACHE.put(part, zero);
            return zero;
        }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (ModelPart.Cube c : cubes) {
            minX = Math.min(minX, c.minX); maxX = Math.max(maxX, c.maxX);
            minY = Math.min(minY, c.minY); maxY = Math.max(maxY, c.maxY);
            minZ = Math.min(minZ, c.minZ); maxZ = Math.max(maxZ, c.maxZ);
        }
        org.joml.Vector3f center = new org.joml.Vector3f(
                (minX + maxX) * 0.5f,
                (minY + maxY) * 0.5f,
                (minZ + maxZ) * 0.5f);
        CUBE_CENTER_CACHE.put(part, center);
        return center;
    }

    private static org.joml.Vector3f setPosForPart(ModelPart part, float defaultXRot) {
        org.joml.Vector3f c = cubeBoxCenter(part);
        float cosA = (float) Math.cos(defaultXRot);
        float sinA = (float) Math.sin(defaultXRot);
        // Rotate (c.y, c.z) around X by defaultXRot: y' = c.y*cos - c.z*sin, z' = c.y*sin + c.z*cos.
        float ry = c.y * cosA - c.z * sinA;
        float rz = c.y * sinA + c.z * cosA;
        return new org.joml.Vector3f(-c.x, -ry, -rz);
    }
}
