package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
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
    // Villagers/wandering traders render on the vanilla VillagerModel (correct UVs for their
    // textures). Its single combined "arms" part (both stubs + the connecting bar) is anchored
    // to the torso body so the crossed-arms silhouette survives — see renderVillagerParts.
    private static ModelPart villagerRoot;
    // Zombie villagers use the vanilla ZombieVillagerModel (a HumanoidModel with real
    // separate arms and matching UVs) so they render through the standard humanoid path.
    private static ZombieVillagerModel<?> zombieVillagerModel;
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
    // Cat/ocelot share the OcelotModel geometry (quadruped + tail). Bat and bee are winged.
    // Baked ModelPart trees are used directly (parts are driven per-body by the renderer), so
    // the EntityModel wrappers aren't needed — and BeeModel (AgeableListModel) has no root().
    private static ModelPart catRoot;
    private static ModelPart catCollarRoot; // dyed collar overlay for tamed cats
    private static ModelPart batRoot;
    private static ModelPart beeRoot;

    private static final ResourceLocation CAT_COLLAR_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/cat/cat_collar.png");

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
            // Villager base model (vanilla, correct UVs); its combined arms ride the torso.
            // Zombie villagers get their own vanilla model which has real separate arms.
            villagerRoot = bakery.bakeLayer(ModelLayers.VILLAGER);
            zombieVillagerModel = new ZombieVillagerModel<>(bakery.bakeLayer(ModelLayers.ZOMBIE_VILLAGER));
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
            catRoot = bakery.bakeLayer(ModelLayers.CAT);
            catCollarRoot = bakery.bakeLayer(ModelLayers.CAT_COLLAR);
            batRoot = bakery.bakeLayer(ModelLayers.BAT);
            beeRoot = bakery.bakeLayer(ModelLayers.BEE);

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
            makeAllChildrenVisible(catRoot);
            makeAllChildrenVisible(catCollarRoot);
            makeAllChildrenVisible(batRoot);
            makeAllChildrenVisible(beeRoot);

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

        // Free wound textures owned by ragdolls destroyed since last frame (render thread only).
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.releasePending();

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
            double renderDistance = RagdollifiedConfig.RENDER_DISTANCE.get();
            if (distSq > renderDistance * renderDistance) {
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
        UUID uuid = ragdoll.getPlayerUUID();
        ResourceLocation skin = ragdoll.getCachedPlayerSkin();
        boolean isSlim = ragdoll.isCachedSlim();
        if (skin == null) {
            skin = uuid != null
                    ? DefaultPlayerSkin.getDefaultSkin(uuid)
                    : DefaultPlayerSkin.getDefaultSkin();
        }
        // May be null if the player has despawned or moved out of render distance.
        // Vanilla armor is cached on the ragdoll so it renders regardless.
        // GeckoLib armor falls back to its internal proxy ArmorStand when null,
        // same as the mob path — so armor stays visible at any distance.
        AbstractClientPlayer playerEntity = findPlayerEntity(uuid);

        RagdollTransform torso = ragdoll.getSmoothedTransform(RagdollPart.TORSO);
        RagdollTransform head  = ragdoll.getSmoothedTransform(RagdollPart.HEAD);
        RagdollTransform larm  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_ARM);
        RagdollTransform rarm  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_ARM);
        RagdollTransform lleg  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_LEG);
        RagdollTransform rleg  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_LEG);

        renderPlayerBody(poseStack, buffer, light, distSq, torso, head, larm, rarm, lleg, rleg,
                skin, isSlim,
                ragdoll.getHelmet(), ragdoll.getChestplate(), ragdoll.getLeggings(), ragdoll.getBoots(),
                playerEntity, liquidBobOffset(ragdoll), ragdoll.getOriginalEntityId());
    }

    /**
     * Render a humanoid (player) body + armor at the given part transforms. Shared by the
     * live ragdoll path and the corpse renderer. Coordinates follow the ragdoll convention:
     * the poseStack must already be camera-relative; this method translates to the torso and
     * draws each part using its position-relative-to-torso (rotation absolute). Pass distSq=0
     * to always render armor. skin must be non-null. {@code bloodRagdollId} keys the Better
     * Blood Overlay capture for the live-ragdoll path; pass -1 (e.g. the corpse renderer) to
     * skip the blood pass.
     */
    static void renderPlayerBody(PoseStack poseStack, MultiBufferSource buffer, int light, double distSq,
                                 RagdollTransform torso, RagdollTransform head,
                                 RagdollTransform larm, RagdollTransform rarm,
                                 RagdollTransform lleg, RagdollTransform rleg,
                                 ResourceLocation skin, boolean isSlim,
                                 ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                                 AbstractClientPlayer playerEntity, float bob, int bloodRagdollId) {
        if (torso == null) return;
        // The corpse renderer calls this directly and can run before any live ragdoll has — e.g. a
        // corpse loaded from disk on world (re)load with no ragdoll around, in which case
        // onRenderLevel's lazy init never fired. Bake the models here too, and bail if they still
        // aren't ready, so we never dereference a null model (was: NPE spam + invisible corpse).
        initModels();
        PlayerModel<AbstractClientPlayer> model = isSlim ? slimModel : normalModel;
        if (model == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(torso.position.x, torso.position.y + bob, torso.position.z);

            VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(skin));

            renderHumanoidPartPhysics(poseStack, vc, model.body,     torso, torso, light, RagdollPart.TORSO);
            renderHumanoidPartPhysics(poseStack, vc, model.head,     head,  torso, light, RagdollPart.HEAD);
            renderHumanoidPartPhysics(poseStack, vc, model.leftLeg,  lleg,  torso, light, RagdollPart.LEFT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.rightLeg, rleg,  torso, light, RagdollPart.RIGHT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.leftArm,  larm,  torso, light, RagdollPart.LEFT_ARM);
            renderHumanoidPartPhysics(poseStack, vc, model.rightArm, rarm,  torso, light, RagdollPart.RIGHT_ARM);

            // Second skin layer (hat / jacket / sleeves / pants). PlayerModel keeps these as
            // separate sibling parts, so the base-part passes above don't draw them; render
            // each over its matching base part so the player's outer skin layer shows.
            renderHumanoidPartPhysics(poseStack, vc, model.hat,         head,  torso, light, RagdollPart.HEAD);
            renderHumanoidPartPhysics(poseStack, vc, model.jacket,      torso, torso, light, RagdollPart.TORSO);
            renderHumanoidPartPhysics(poseStack, vc, model.leftPants,   lleg,  torso, light, RagdollPart.LEFT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.rightPants,  rleg,  torso, light, RagdollPart.RIGHT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.leftSleeve,  larm,  torso, light, RagdollPart.LEFT_ARM);
            renderHumanoidPartPhysics(poseStack, vc, model.rightSleeve, rarm,  torso, light, RagdollPart.RIGHT_ARM);

            // Procedural blood carried over from the live player (under armor). No-op unless
            // Better Blood Overlay is installed and the player was bleeding at death; -1 (corpse
            // renderer) skips it.
            if (bloodRagdollId != -1) {
                renderHumanoidBlood(bloodRagdollId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, model, HumanoidScale.ADULT);
            }

            double armorDistSq = RagdollifiedConfig.getArmorRenderDistanceSq();
            double geckoDistSq = RagdollifiedConfig.getGeckoLibArmorRenderDistanceSq();

            if (distSq <= armorDistSq) {
                renderPlayerVanillaArmor(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim, playerEntity, helmet, chestplate, leggings, boots);

                if (distSq <= geckoDistSq) {
                    // Pass playerEntity which may be null — GeckoLibArmorHelper uses its
                    // internal proxy ArmorStand as fallback, identical to the mob armor path.
                    renderPlayerGeckoLibArmor(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim, playerEntity, helmet, chestplate, leggings, boots);
                }
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Error rendering player body", e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderPlayerVanillaArmor(PoseStack poseStack, MultiBufferSource buffer,
                                                  int light, RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg, boolean isSlim,
                                                  net.minecraft.world.entity.LivingEntity entity,
                                                  ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        HumanoidModel<AbstractClientPlayer> innerModel = isSlim ? slimArmorInner : normalArmorInner;
        HumanoidModel<AbstractClientPlayer> outerModel = isSlim ? slimArmorOuter : normalArmorOuter;

        renderVanillaArmorSlot(helmet, EquipmentSlot.HEAD, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
        renderVanillaArmorSlot(chestplate, EquipmentSlot.CHEST, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
        renderVanillaArmorSlot(leggings, EquipmentSlot.LEGS, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
        renderVanillaArmorSlot(boots, EquipmentSlot.FEET, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
    }

    private static void renderVanillaArmorSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                                MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                HumanoidModel<?> innerModel, HumanoidModel<?> outerModel,
                                                net.minecraft.world.entity.LivingEntity entity) {
        renderVanillaArmorSlot(stack, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg,
                innerModel, outerModel, entity, HumanoidScale.ADULT);
    }

    private static void renderVanillaArmorSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                                MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                HumanoidModel<?> innerModel, HumanoidModel<?> outerModel,
                                                net.minecraft.world.entity.LivingEntity entity, HumanoidScale modelScale) {
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        if (GeckoLibArmorHelper.isGeckoLibArmor(item)) return; // handled separately
        if (!(item instanceof ArmorItem armorItem)) return;

        // Mirror vanilla HumanoidArmorLayer: legs use the inner (less-inflated) model so
        // leggings sit under the chestplate; everything else uses the outer model.
        HumanoidModel<?> base = (slot == EquipmentSlot.LEGS) ? innerModel : outerModel;
        // Resolve the actual model to render. Modded (non-GeckoLib) armor ships textures
        // UV-mapped for its own custom armor model, exposed via the Forge
        // IClientItemExtensions.getHumanoidArmorModel hook. Rendering its texture on the
        // vanilla model is what made modded armor map to the wrong faces — so we ask the
        // item for its model here, exactly like vanilla's HumanoidArmorLayer does.
        HumanoidModel<?> model = resolveArmorModel(stack, slot, entity, base);

        // Leather (and any DyeableLeatherItem) renders in two passes, like vanilla:
        // a base layer tinted by the dye color, then an untinted overlay (straps/buckles).
        // Skipping the dye tint is what made undyed leather render as flat grey — i.e.
        // look like iron/chainmail. Non-dyeable armor uses a single untinted pass.
        boolean dyeable = item instanceof net.minecraft.world.item.DyeableLeatherItem;
        float r = 1f, g = 1f, b = 1f;
        if (dyeable) {
            int color = ((net.minecraft.world.item.DyeableLeatherItem) item).getColor(stack);
            r = (color >> 16 & 255) / 255f;
            g = (color >> 8 & 255) / 255f;
            b = (color & 255) / 255f;
        }

        ResourceLocation baseTex = getArmorTexture(armorItem, slot, stack, entity, null);
        renderArmorSlotParts(model, baseTex, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, r, g, b, modelScale);

        if (dyeable) {
            ResourceLocation overlayTex = getArmorTexture(armorItem, slot, stack, entity, "overlay");
            renderArmorSlotParts(model, overlayTex, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, 1f, 1f, 1f, modelScale);
        }
    }

    // Render the body parts relevant to one armor slot from {@code model}, tinted (r,g,b).
    private static void renderArmorSlotParts(HumanoidModel<?> model, ResourceLocation texture, EquipmentSlot slot,
                                             PoseStack poseStack, MultiBufferSource buffer, int light,
                                             RagdollTransform torso, RagdollTransform head,
                                             RagdollTransform larm, RagdollTransform rarm,
                                             RagdollTransform lleg, RagdollTransform rleg,
                                             float r, float g, float b, HumanoidScale modelScale) {
        VertexConsumer vc = buffer.getBuffer(RenderType.armorCutoutNoCull(texture));
        switch (slot) {
            case HEAD:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.head, head, torso, light, RagdollPart.HEAD, r, g, b, 1f, modelScale);
                break;
            case CHEST:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.leftArm, larm, torso, light, RagdollPart.LEFT_ARM, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM, r, g, b, 1f, modelScale);
                break;
            case LEGS:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, r, g, b, 1f, modelScale);
                break;
            case FEET:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, r, g, b, 1f, modelScale);
                break;
        }
    }

    /**
     * Ask the armor item for the model it wants rendered (Forge
     * {@link net.minecraftforge.client.extensions.common.IClientItemExtensions#getHumanoidArmorModel}).
     * Returns {@code base} unchanged for vanilla armor; modded armor with a custom model
     * returns its own, so its texture UVs line up. Falls back to {@code base} on any error.
     */
    private static HumanoidModel<?> resolveArmorModel(ItemStack stack, EquipmentSlot slot,
                                                      net.minecraft.world.entity.LivingEntity entity,
                                                      HumanoidModel<?> base) {
        try {
            // Most mods key off a non-null LivingEntity; reuse GeckoLib's proxy ArmorStand
            // when we don't have the real one (despawned player / mob path).
            net.minecraft.world.entity.LivingEntity e = entity != null ? entity : GeckoLibArmorHelper.getProxyEntity();
            HumanoidModel<?> custom = net.minecraftforge.client.extensions.common.IClientItemExtensions.of(stack)
                    .getHumanoidArmorModel(e, stack, slot, base);
            if (custom != null && custom != base) {
                // We drive part pose/visibility ourselves, so make sure the custom model's
                // parts are all visible before we pick the ones this slot needs.
                setAllPartsVisible(custom);
                return custom;
            }
        } catch (Exception ex) {
            Ragdollified.LOGGER.debug("Custom armor model lookup failed, using vanilla model: {}", ex.getMessage());
        }
        return base;
    }

    private static void renderPlayerGeckoLibArmor(PoseStack poseStack, MultiBufferSource buffer,
                                                  int light, RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg, boolean isSlim,
                                                  AbstractClientPlayer playerEntity,
                                                  ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        // playerEntity may be null if the player has despawned — GeckoLibArmorHelper
        // falls back to its internal proxy ArmorStand in that case, same as mobs.
        HumanoidModel<AbstractClientPlayer> baseModel = isSlim ? slimArmorInner : normalArmorInner;

        renderGeckoLibSlot(helmet,     EquipmentSlot.HEAD,  poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(chestplate, EquipmentSlot.CHEST, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(leggings,   EquipmentSlot.LEGS,  poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(boots,      EquipmentSlot.FEET,  poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
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
        renderGeckoLibSlot(stack, slot, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, entity, HumanoidScale.ADULT);
    }

    private static void renderGeckoLibSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                            MultiBufferSource buffer, int light, HumanoidModel<?> baseModel,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg,
                                            net.minecraft.world.entity.LivingEntity entity, HumanoidScale modelScale) {
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
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, head, torso, RagdollPart.HEAD, entity, modelScale);
                    baseModel.head.visible = false;
                }
                break;
            case CHEST:
                if (torso != null && larm != null && rarm != null) {
                    baseModel.body.visible = true;
                    resetPart(baseModel.body);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO, entity, modelScale);
                    baseModel.body.visible = false;

                    baseModel.leftArm.visible = true;
                    resetPart(baseModel.leftArm);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, larm, torso, RagdollPart.LEFT_ARM, entity, modelScale);
                    baseModel.leftArm.visible = false;

                    baseModel.rightArm.visible = true;
                    resetPart(baseModel.rightArm);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rarm, torso, RagdollPart.RIGHT_ARM, entity, modelScale);
                    baseModel.rightArm.visible = false;
                }
                break;
            case LEGS:
                if (torso != null && lleg != null && rleg != null) {
                    baseModel.body.visible = true;
                    resetPart(baseModel.body);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO, entity, modelScale);
                    baseModel.body.visible = false;

                    baseModel.leftLeg.visible = true;
                    resetPart(baseModel.leftLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG, entity, modelScale);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    resetPart(baseModel.rightLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG, entity, modelScale);
                    baseModel.rightLeg.visible = false;
                }
                break;
            case FEET:
                if (lleg != null && rleg != null) {
                    baseModel.leftLeg.visible = true;
                    resetPart(baseModel.leftLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG, entity, modelScale);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    resetPart(baseModel.rightLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG, entity, modelScale);
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
        renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, transform, torso, ragdollPart, entity, HumanoidScale.ADULT);
    }

    private static void renderGeckoLibPartPhysics(ItemStack stack, EquipmentSlot slot,
                                                   PoseStack poseStack, MultiBufferSource buffer, int light,
                                                   HumanoidModel<?> baseModel,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   RagdollPart ragdollPart,
                                                   net.minecraft.world.entity.LivingEntity entity, HumanoidScale scale) {
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
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

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
        RagdollTransform head  = ragdoll.getSmoothedTransform(RagdollPart.HEAD);
        RagdollTransform larm  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_ARM);
        RagdollTransform rarm  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_ARM);
        RagdollTransform lleg  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_LEG);
        RagdollTransform rleg  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_LEG);

        if (torso == null) return;

        poseStack.pushPose();
        try {
            float bob = liquidBobOffset(ragdoll);
            poseStack.translate(torso.position.x, torso.position.y + bob, torso.position.z);

            ResourceLocation texture = getMobTexture(ragdoll);
            VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

            // Baby humanoids get their model + armor scaled to match the physics bodies built
            // in RagdollBodyFactory.buildHumanoid. Whether the head is enlarged is decided
            // per-mob to mirror vanilla (see ClientRagdoll#babyScalesHead) — big head for
            // zombies/piglins/zombie-villagers, uniform shrink for plain villagers.
            HumanoidScale humanoidScale = !ragdoll.isBabyHumanoid() ? HumanoidScale.ADULT
                    : (ragdoll.babyScalesHead() ? HumanoidScale.BABY : HumanoidScale.BABY_UNIFORM);

            // Better Blood Overlay: set to the HumanoidModel actually drawn (it shares vanilla
            // UVs, which BBO's wound atlas is authored against), or flag the villager/illager
            // paths which render on their own (non-HumanoidModel) trees. All left unset for
            // non-humanoid families BBO doesn't cover (creeper, animals, chicken, bat, bee).
            HumanoidModel<?> bloodModel = null;
            boolean villagerBlood = false;
            boolean illagerBlood = false;

            switch (modelType) {
                case CREEPER:
                    renderCreeper(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case QUADRUPED: {
                    String mt = ragdoll.getMobType();
                    if (mt.contains("cat") || mt.contains("ocelot")) {
                        renderCat(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                        // wasSheared() carries "tamed" for cats (see PhysicsHooks); tamed cats
                        // wear a dyed collar. Ocelots are never tamed so this stays off for them.
                        if (mt.contains("cat") && ragdoll.wasSheared() && catCollarRoot != null) {
                            renderCatCollar(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);
                        }
                    } else {
                        renderQuadruped(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    }
                    break;
                }
                case CHICKEN:
                    renderChicken(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case BAT:
                    renderBat(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case BEE:
                    renderBee(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case ILLAGER: {
                    // ILLAGER covers three texture/UV families: zombie villagers (vanilla
                    // ZombieVillagerModel — a humanoid), plain villagers + wandering traders
                    // (VillagerModel), and true illagers (pillager/vindicator/…). Route each
                    // to the model its texture is actually drawn for.
                    String mt = ragdoll.getMobType();
                    if (mt.contains("zombie_villager")) {
                        renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, zombieVillagerModel, humanoidScale);
                        bloodModel = zombieVillagerModel;
                    } else if (!mt.contains("zombie") && (mt.contains("villager") || mt.contains("wandering_trader"))) {
                        renderVillager(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                        villagerBlood = true;
                    } else {
                        renderIllager(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                        illagerBlood = true;
                    }
                    break;
                }
                case HUMANOID_SKELETON:
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, skeletonModel, humanoidScale);
                    bloodModel = skeletonModel;
                    break;
                case HUMANOID_DROWNED:
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, drownedModel, humanoidScale);
                    bloodModel = drownedModel;
                    break;
                default:
                    HumanoidModel<?> humanoidModel = ragdoll.getMobType().contains("piglin")
                            ? piglinModel : standardHumanoidModel;
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidModel, humanoidScale);
                    bloodModel = humanoidModel;
                    break;
            }

            // Procedural blood carried over from the live mob, drawn on the same physics-posed
            // parts (under armor/overlays). No-op unless Better Blood Overlay is installed and
            // the mob was bleeding at death.
            int bloodId = ragdoll.getOriginalEntityId();
            if (bloodModel != null) {
                renderHumanoidBlood(bloodId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, bloodModel, humanoidScale);
            } else if (villagerBlood) {
                renderVillagerBlood(bloodId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
            } else if (illagerBlood) {
                renderIllagerBlood(bloodId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
            }

            for (MobOverlay overlay : overlaysFor(ragdoll)) {
                renderOverlay(overlay, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
            }

            if (isHumanoidType(modelType)) {
                double armorDistSq = RagdollifiedConfig.getArmorRenderDistanceSq();
                double geckoDistSq = RagdollifiedConfig.getGeckoLibArmorRenderDistanceSq();

                if (distSq <= armorDistSq) {
                    renderMobVanillaArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);

                    if (distSq <= geckoDistSq) {
                        renderMobGeckoLibArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                    }
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
        renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, model, HumanoidScale.ADULT);
    }

    private static void renderHumanoidMob(PoseStack poseStack, VertexConsumer vc, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg,
                                           HumanoidModel<?> model, HumanoidScale modelScale) {
        renderHumanoidPartPhysics(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.head, head, torso, light, RagdollPart.HEAD, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.leftArm, larm, torso, light, RagdollPart.LEFT_ARM, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM, modelScale);
        // Second/overlay skin layer. HumanoidModel's "hat" is a sibling of "head" (not a
        // child), so the head pass above never draws it — render it explicitly to match
        // vanilla, which draws the hat for every humanoid (transparent on most mob textures).
        renderHumanoidPartPhysics(poseStack, vc, model.hat, head, torso, light, RagdollPart.HEAD, modelScale);
    }

    /**
     * Better Blood Overlay pass for a humanoid ragdoll. Draws the procedural blood decals
     * captured from the live mob onto each physics-posed part, exactly how BBO's own
     * {@code renderWounds} draws them on a live mob (same model part, translucent, scaled
     * 1.001 to sit just above the skin). No-op when BBO is absent or the mob wasn't bleeding.
     */
    private static void renderHumanoidBlood(int id, PoseStack poseStack, MultiBufferSource buffer,
                                            int light, RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg,
                                            HumanoidModel<?> model, HumanoidScale scale) {
        if (!BetterBloodOverlayCompat.hasBlood(id)) return;

        renderBloodPart(id, "body",      model.body,     poseStack, buffer, torso, torso, light, RagdollPart.TORSO,     scale);
        renderBloodPart(id, "head",      model.head,     poseStack, buffer, head,  torso, light, RagdollPart.HEAD,      scale);
        renderBloodPart(id, "left_leg",  model.leftLeg,  poseStack, buffer, lleg,  torso, light, RagdollPart.LEFT_LEG,  scale);
        renderBloodPart(id, "right_leg", model.rightLeg, poseStack, buffer, rleg,  torso, light, RagdollPart.RIGHT_LEG, scale);
        renderBloodPart(id, "left_arm",  model.leftArm,  poseStack, buffer, larm,  torso, light, RagdollPart.LEFT_ARM,  scale);
        renderBloodPart(id, "right_arm", model.rightArm, poseStack, buffer, rarm,  torso, light, RagdollPart.RIGHT_ARM, scale);

        // Second skin layer. Only the player profile maps wounds to these overlay parts, and
        // BBO only draws them when overlay-blood is enabled — so this is a no-op for mobs
        // (decalsForPart returns empty) and gated to match BBO for players.
        if (model instanceof PlayerModel<?> pm && BetterBloodOverlayCompat.isOverlayBloodEnabled()) {
            renderBloodPart(id, "hat",          pm.hat,         poseStack, buffer, head,  torso, light, RagdollPart.HEAD,      scale);
            renderBloodPart(id, "jacket",       pm.jacket,      poseStack, buffer, torso, torso, light, RagdollPart.TORSO,     scale);
            renderBloodPart(id, "left_sleeve",  pm.leftSleeve,  poseStack, buffer, larm,  torso, light, RagdollPart.LEFT_ARM,  scale);
            renderBloodPart(id, "right_sleeve", pm.rightSleeve, poseStack, buffer, rarm,  torso, light, RagdollPart.RIGHT_ARM, scale);
            renderBloodPart(id, "left_pants",   pm.leftPants,   poseStack, buffer, lleg,  torso, light, RagdollPart.LEFT_LEG,  scale);
            renderBloodPart(id, "right_pants",  pm.rightPants,  poseStack, buffer, rleg,  torso, light, RagdollPart.RIGHT_LEG, scale);
        }
    }

    /**
     * Better Blood Overlay pass for an illager (pillager/vindicator/evoker/illusioner). Illagers
     * render on the non-HumanoidModel {@link IllagerModel} but with the same per-part physics
     * posing as humanoids, so we resolve the parts by name and reuse {@link #renderBloodPart}.
     * BBO's illager profile also paints a combined "arms" site, but our model shows the split
     * arms (that part is hidden), so drawing left_arm/right_arm reproduces it correctly.
     */
    private static void renderIllagerBlood(int id, PoseStack poseStack, MultiBufferSource buffer, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        if (!BetterBloodOverlayCompat.hasBlood(id)) return;
        ModelPart root = illagerModel.root();
        renderBloodPart(id, "body",      root.getChild("body"),      poseStack, buffer, torso, torso, light, RagdollPart.TORSO,     scale);
        renderBloodPart(id, "head",      root.getChild("head"),      poseStack, buffer, head,  torso, light, RagdollPart.HEAD,      scale);
        renderBloodPart(id, "left_leg",  root.getChild("left_leg"),  poseStack, buffer, lleg,  torso, light, RagdollPart.LEFT_LEG,  scale);
        renderBloodPart(id, "right_leg", root.getChild("right_leg"), poseStack, buffer, rleg,  torso, light, RagdollPart.RIGHT_LEG, scale);
        renderBloodPart(id, "left_arm",  root.getChild("left_arm"),  poseStack, buffer, larm,  torso, light, RagdollPart.LEFT_ARM,  scale);
        renderBloodPart(id, "right_arm", root.getChild("right_arm"), poseStack, buffer, rarm,  torso, light, RagdollPart.RIGHT_ARM, scale);
    }

    /**
     * Better Blood Overlay pass for a villager / wandering trader. These render on the vanilla
     * {@link net.minecraft.client.model.VillagerModel} via the animal-part path, and BBO's
     * villager profile maps both arms to a single combined "arms" site — so we mirror
     * {@link #renderVillagerParts} exactly, drawing blood on the same physics-posed parts.
     */
    private static void renderVillagerBlood(int id, PoseStack poseStack, MultiBufferSource buffer, int light,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        if (!BetterBloodOverlayCompat.hasBlood(id)) return;
        ModelPart headPart = villagerRoot.getChild("head");
        ModelPart body     = villagerRoot.getChild("body");
        ModelPart arms     = villagerRoot.getChild("arms");
        ModelPart leftLeg  = villagerRoot.getChild("left_leg");
        ModelPart rightLeg = villagerRoot.getChild("right_leg");

        renderBloodVillagerPart(id, "body",      body,     poseStack, buffer, torso, torso, light, scale.body());
        renderBloodVillagerPart(id, "head",      headPart, poseStack, buffer, head,  torso, light, scale.head());
        renderBloodVillagerPart(id, "left_leg",  leftLeg,  poseStack, buffer, lleg,  torso, light, scale.body());
        renderBloodVillagerPart(id, "right_leg", rightLeg, poseStack, buffer, rleg,  torso, light, scale.body());
        // Combined "arms" part, anchored to the torso with the same offsets/pitch as the base
        // pass (see renderVillagerParts). Both arm-limb wounds map here.
        var armDecals = BetterBloodOverlayCompat.decalsForPart(id, "arms");
        if (!armDecals.isEmpty()) {
            renderBloodAnimalPart(arms, poseStack, buffer, torso, torso, 0.0F, -3.0F, -1.0F, -0.75F, light, scale.body(), armDecals);
        }
    }

    /** Blood on one villager body part, centred on its physics body exactly like
     *  {@link #renderVillagerPart}. */
    private static void renderBloodVillagerPart(int id, String partName, ModelPart part,
                                                PoseStack poseStack, MultiBufferSource buffer,
                                                RagdollTransform transform, RagdollTransform torso,
                                                int light, float modelScale) {
        var decals = BetterBloodOverlayCompat.decalsForPart(id, partName);
        if (decals.isEmpty()) return;
        org.joml.Vector3f off = setPosForPart(part, 0);
        renderBloodAnimalPart(part, poseStack, buffer, transform, torso, off.x, off.y, off.z, 0, light, modelScale, decals);
    }

    /**
     * Pose {@code part} to its physics transform exactly like {@link #renderAnimalPart}, then
     * draw the given blood decals over it (1.001 scale, translucent) — the animal-path
     * counterpart of {@link #renderBloodPart}.
     */
    private static void renderBloodAnimalPart(ModelPart part, PoseStack poseStack, MultiBufferSource buffer,
                                              RagdollTransform transform, RagdollTransform torso,
                                              float setPosX, float setPosY, float setPosZ,
                                              float defaultXRot, int light, float modelScale,
                                              java.util.List<BetterBloodOverlayCompat.Decal> decals) {
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

            poseStack.pushPose();
            poseStack.scale(1.001f, 1.001f, 1.001f);
            for (BetterBloodOverlayCompat.Decal d : decals) {
                VertexConsumer bvc = buffer.getBuffer(RenderType.entityTranslucent(d.texture()));
                part.render(poseStack, bvc, light, OverlayTexture.NO_OVERLAY, d.r(), d.g(), d.b(), d.alpha());
            }
            poseStack.popPose();
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Pose {@code part} to its physics transform (identically to
     * {@link #renderHumanoidPartPhysics}) and draw any blood decals mapped to {@code partName}.
     */
    private static void renderBloodPart(int ragdollId, String partName, ModelPart part,
                                        PoseStack poseStack, MultiBufferSource buffer,
                                        RagdollTransform transform, RagdollTransform torso,
                                        int light, RagdollPart ragdollPart, HumanoidScale scale) {
        if (transform == null) return;
        var decals = BetterBloodOverlayCompat.decalsForPart(ragdollId, partName);
        if (decals.isEmpty()) return;

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
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

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

            // Nudge just above the skin so blood never z-fights with the base texture — same
            // 1.001 scale BBO uses in renderWounds.
            poseStack.pushPose();
            poseStack.scale(1.001f, 1.001f, 1.001f);
            for (BetterBloodOverlayCompat.Decal d : decals) {
                VertexConsumer bvc = buffer.getBuffer(RenderType.entityTranslucent(d.texture()));
                part.render(poseStack, bvc, light, OverlayTexture.NO_OVERLAY, d.r(), d.g(), d.b(), d.alpha());
            }
            poseStack.popPose();
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderIllager(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg, HumanoidScale modelScale) {
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

        renderHumanoidPartPhysics(poseStack, vc, body, torso, torso, light, RagdollPart.TORSO, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head, torso, light, RagdollPart.HEAD, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, leftArm, larm, torso, light, RagdollPart.LEFT_ARM, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM, modelScale);
    }

    /**
     * Regular villagers / wandering traders. Rendered on the vanilla VillagerModel (whose UVs
     * the villager textures are drawn for) with our separate short arms replacing its single
     * combined "arms" part, so every part — including arms and legs — textures correctly and
     * the profession/type overlays can cover the whole body.
     */
    private static void renderVillager(PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        renderVillagerParts(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, scale);
    }

    /** Draws the villager body with whatever texture {@code vc} targets — reused for the base
     *  skin and for each profession/type/level overlay layer. */
    private static void renderVillagerParts(PoseStack poseStack, VertexConsumer vc, int light,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        ModelPart headPart = villagerRoot.getChild("head");
        ModelPart body     = villagerRoot.getChild("body");
        ModelPart arms     = villagerRoot.getChild("arms");
        ModelPart leftLeg  = villagerRoot.getChild("left_leg");
        ModelPart rightLeg = villagerRoot.getChild("right_leg");

        renderVillagerPart(poseStack, vc, body,     torso, torso, light, scale.body());
        renderVillagerPart(poseStack, vc, headPart, head,  torso, light, scale.head());
        renderVillagerPart(poseStack, vc, leftLeg,  lleg,  torso, light, scale.body());
        renderVillagerPart(poseStack, vc, rightLeg, rleg,  torso, light, scale.body());
        // Villager arms are the vanilla crossed-arms unit (two stubs + the bar connecting
        // them). A ragdoll's two separate arm bodies can't carry a rigid connector, so — per
        // the chosen design — we anchor the whole arms part to the torso at its natural
        // body-relative pose and let it tumble with the body, keeping the folded-arms look.
        // Placement: arms pivot (0,3,-1) minus the body-cube centre (0,6,0) → (0,-3,-1), with
        // the model's -0.75 rad forward pitch preserved via defaultXRot.
        renderAnimalPart(poseStack, vc, arms, torso, torso, 0.0F, -3.0F, -1.0F, -0.75F, light, scale.body());
    }

    /** One villager part, centred on its physics body via setPosForPart (handles the villager's
     *  tall head without hand-tuned offsets, same as the animal path). */
    private static void renderVillagerPart(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                           RagdollTransform transform, RagdollTransform torso,
                                           int light, float modelScale) {
        org.joml.Vector3f off = setPosForPart(part, 0);
        renderAnimalPart(poseStack, vc, part, transform, torso, off.x, off.y, off.z, 0, light, modelScale);
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

    /**
     * Cat / ocelot. Same six-body quadruped layout as cows/pigs (body, head, four legs) but on
     * the OcelotModel geometry, plus the two tail segments anchored to the torso so the tail
     * tumbles with the body. Front and hind legs differ in length in the model; the renderer
     * centres each rendered leg cube on its own physics body via setPosForPart, so the visuals
     * follow the real cube sizes regardless of the (uniform-ish) physics boxes.
     */
    private static void renderCat(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = catRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");
        ModelPart tail1 = root.getChild("tail1");
        ModelPart tail2 = root.getChild("tail2");

        float halfPI = (float) (Math.PI / 2);
        org.joml.Vector3f bodyOff       = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff       = setPosForPart(headPart, 0);
        org.joml.Vector3f leftHindOff   = setPosForPart(leftHind, 0);
        org.joml.Vector3f rightHindOff  = setPosForPart(rightHind, 0);
        org.joml.Vector3f leftFrontOff  = setPosForPart(leftFront, 0);
        org.joml.Vector3f rightFrontOff = setPosForPart(rightFront, 0);

        // CatRenderer draws the model at 0.8×. Kittens are NOT a uniform shrink: OcelotModel has
        // scaleHead=true (babyHeadScale=2), so the head renders at 1.5/2 = 0.75× and the body at
        // 1.0/2 = 0.5× (AgeableListModel). Adults use 1.0× for both. Everything then ×0.8.
        boolean baby = ragdoll.usesBabyBodyScale();
        float bodyScale = (baby ? 0.5f  : 1.0f) * 0.8f;
        float headScale = (baby ? 0.75f : 1.0f) * 0.8f;
        renderAnimalPart(poseStack, vc, body,       torso, torso, bodyOff.x,       bodyOff.y,       bodyOff.z,       halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart,   head,  torso, headOff.x,       headOff.y,       headOff.z,       0,      light, headScale);
        renderAnimalPart(poseStack, vc, leftHind,   lleg,  torso, leftHindOff.x,   leftHindOff.y,   leftHindOff.z,   0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind,  rleg,  torso, rightHindOff.x,  rightHindOff.y,  rightHindOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront,  larm,  torso, leftFrontOff.x,  leftFrontOff.y,  leftFrontOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm,  torso, rightFrontOff.x, rightFrontOff.y, rightFrontOff.z, 0,      light, bodyScale);
        // Tail segments ride the torso. setPos = part pivot − body-cube centre (root coords),
        // matching how the villager arms unit is anchored; their resting xRot mirrors vanilla.
        renderAnimalPart(poseStack, vc, tail1, torso, torso, 0, -2, 7,  0.9f,      light, bodyScale);
        renderAnimalPart(poseStack, vc, tail2, torso, torso, 0,  3, 13, 1.7278761f, light, bodyScale);
    }

    /**
     * Dyed collar overlay for a tamed cat. Mirrors {@link #renderCat}'s body/head/leg placement
     * (same 0.8× scale) on the CAT_COLLAR geometry, tinted by the collar dye colour. The tail is
     * skipped — the collar texture is empty there. Collar colour rides the cat's dyeColorId.
     */
    private static void renderCatCollar(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer, int light,
                                        RagdollTransform torso, RagdollTransform head,
                                        RagdollTransform larm, RagdollTransform rarm,
                                        RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = catCollarRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");

        net.minecraft.world.item.DyeColor color =
                net.minecraft.world.item.DyeColor.byId(ragdoll.getDyeColorId() & 0xF);
        float[] rgb = color.getTextureDiffuseColors();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(CAT_COLLAR_TEXTURE));

        float halfPI = (float) (Math.PI / 2);
        org.joml.Vector3f bodyOff       = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff       = setPosForPart(headPart, 0);
        org.joml.Vector3f leftHindOff   = setPosForPart(leftHind, 0);
        org.joml.Vector3f rightHindOff  = setPosForPart(rightHind, 0);
        org.joml.Vector3f leftFrontOff  = setPosForPart(leftFront, 0);
        org.joml.Vector3f rightFrontOff = setPosForPart(rightFront, 0);

        boolean baby = ragdoll.usesBabyBodyScale();
        float bodyScale = (baby ? 0.5f  : 1.0f) * 0.8f;
        float headScale = (baby ? 0.75f : 1.0f) * 0.8f;
        renderAnimalPartTinted(poseStack, vc, body,       torso, torso, bodyOff.x,       bodyOff.y,       bodyOff.z,       halfPI, light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, headPart,   head,  torso, headOff.x,       headOff.y,       headOff.z,       0,      light, rgb[0], rgb[1], rgb[2], headScale);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg,  torso, leftHindOff.x,   leftHindOff.y,   leftHindOff.z,   0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg,  torso, rightHindOff.x,  rightHindOff.y,  rightHindOff.z,  0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm,  torso, leftFrontOff.x,  leftFrontOff.y,  leftFrontOff.z,  0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm,  torso, rightFrontOff.x, rightFrontOff.y, rightFrontOff.z, 0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
    }

    /**
     * Bat. Torso = main body (the lower membrane hangs below it, as in vanilla), head above,
     * the two wings in the arm slots, and the two membrane tips in the leg slots. Each wing and
     * tip is drawn detached from the vanilla parent/child hierarchy so it can ride its own
     * physics body — visibility is toggled so nothing draws twice.
     */
    private static void renderBat(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = batRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftWing = body.getChild("left_wing");
        ModelPart rightWing = body.getChild("right_wing");
        ModelPart leftTip = leftWing.getChild("left_wing_tip");
        ModelPart rightTip = rightWing.getChild("right_wing_tip");

        // BatRenderer draws the model at 0.35×; the physics bodies are authored to match.
        final float bs = 0.35f;
        org.joml.Vector3f headOff = setPosForPart(headPart, 0);
        org.joml.Vector3f lwOff = setPosForPart(leftWing, 0);
        org.joml.Vector3f rwOff = setPosForPart(rightWing, 0);

        try {
            // Draw the body (main cube + membrane) without the wings, which ride their own bodies.
            // The membrane is part of "body"; centre the MAIN 6x12x6 cube (centre y=10) so it
            // hangs below exactly like vanilla — setPosForPart can't be used as it would average
            // in the membrane cube.
            leftWing.visible = false; rightWing.visible = false;
            renderAnimalPart(poseStack, vc, body, torso, torso, 0, -10, 0, 0, light, bs);
            renderAnimalPart(poseStack, vc, headPart, head, torso, headOff.x, headOff.y, headOff.z, 0, light, bs);

            // Each wing is one rigid body: draw the wing with its tip (the wing's child) still
            // visible so wing + membrane tip move together.
            leftWing.visible = true; rightWing.visible = true;
            renderAnimalPart(poseStack, vc, leftWing, larm, torso, lwOff.x, lwOff.y, lwOff.z, 0, light, bs);
            renderAnimalPart(poseStack, vc, rightWing, rarm, torso, rwOff.x, rwOff.y, rwOff.z, 0, light, bs);
        } finally {
            leftWing.visible = true; rightWing.visible = true;
            leftTip.visible = true; rightTip.visible = true;
        }
    }

    /**
     * Bee. Torso = body box (draws the antennae + stinger as its children), the two flat wings
     * in the arm slots, and the leg strips grouped into the leg slots (front+middle on the left,
     * back on the right). The bee has no separate head model part, so the head physics stub is
     * not drawn.
     */
    private static void renderBee(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart bone = beeRoot.getChild("bone");
        ModelPart body = bone.getChild("body");
        ModelPart leftWing = bone.getChild("left_wing");
        ModelPart rightWing = bone.getChild("right_wing");
        ModelPart frontLegs = bone.getChild("front_legs");
        ModelPart middleLegs = bone.getChild("middle_legs");
        ModelPart backLegs = bone.getChild("back_legs");

        org.joml.Vector3f bodyOff = setPosForPart(body, 0);
        org.joml.Vector3f lwOff = setPosForPart(leftWing, 0);
        org.joml.Vector3f rwOff = setPosForPart(rightWing, 0);

        // Baby bees render at half scale (vanilla). Wings ride their own bodies; the tiny flat
        // leg strips stay glued to the body, so they're anchored to the torso via
        // setPos = part pivot − body-cube centre (bone-space), like the cat tail / villager arms.
        float bodyScale = ragdoll.isBabyBee() ? 0.5f : 1.0f;
        renderAnimalPart(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, leftWing, larm, torso, lwOff.x, lwOff.y, lwOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightWing, rarm, torso, rwOff.x, rwOff.y, rwOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, frontLegs,  torso, torso, 1.5f, 3.5f, -2f, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, middleLegs, torso, torso, 1.5f, 3.5f,  0f, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, backLegs,   torso, torso, 1.5f, 3.5f,  2f, 0, light, bodyScale);
    }

    private static void renderMobVanillaArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                               int light, RagdollTransform torso, RagdollTransform head,
                                               RagdollTransform larm, RagdollTransform rarm,
                                               RagdollTransform lleg, RagdollTransform rleg, HumanoidScale modelScale) {
        // No real entity for mobs here; resolveArmorModel falls back to the proxy ArmorStand.
        renderVanillaArmorSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
        renderVanillaArmorSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
        renderVanillaArmorSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
        renderVanillaArmorSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
    }

    private static void renderMobGeckoLibArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                                int light, RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg, HumanoidScale modelScale) {
        // GeckoLib uses the proxy ArmorStand inside GeckoLibArmorHelper — no real entity needed
        renderGeckoLibSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
        renderGeckoLibSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
        renderGeckoLibSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
        renderGeckoLibSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
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
        renderHumanoidPartPhysicsTinted(poseStack, vc, part, transform, torso, light, ragdollPart, r, g, b, a, HumanoidScale.ADULT);
    }

    private static void renderHumanoidPartPhysicsTinted(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                         RagdollTransform transform, RagdollTransform torso,
                                                         int light, RagdollPart ragdollPart,
                                                         float r, float g, float b, float a, HumanoidScale scale) {
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
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

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
        renderHumanoidPartPhysics(poseStack, vc, part, transform, torso, light, ragdollPart, HumanoidScale.ADULT);
    }

    private static void renderHumanoidPartPhysics(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   int light, RagdollPart ragdollPart, HumanoidScale scale) {
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
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

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

    /**
     * Body-vs-head scale for a humanoid ragdoll. Babies are not a uniform shrink: vanilla's
     * young HumanoidModel draws the head at 0.75 and the body/arms/legs at 0.5, giving baby
     * zombies their oversized head. Since we render each ModelPart individually (bypassing
     * AgeableListModel#renderToBuffer), we reproduce that split by scaling the head part by
     * {@code head} and every other part by {@code body}.
     */
    private record HumanoidScale(float body, float head) {
        static final HumanoidScale ADULT = new HumanoidScale(1.0f, 1.0f);
        // Big-head baby (zombie/husk/piglin/drowned/zombie-villager): head 0.75, body 0.5.
        static final HumanoidScale BABY  = new HumanoidScale(0.5f, 0.75f);
        // Uniform baby (plain villager/wandering trader): everything at 0.5, no big head.
        static final HumanoidScale BABY_UNIFORM = new HumanoidScale(0.5f, 0.5f);
        float forPart(RagdollPart part) {
            return part == RagdollPart.HEAD ? head : body;
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
        // Ocelot before cat: "ocelot" doesn't contain "cat", but keep the specific case first.
        if (mobType.contains("ocelot")) return new ResourceLocation("minecraft", "textures/entity/cat/ocelot.png");
        if (mobType.contains("cat")) return new ResourceLocation("minecraft", "textures/entity/cat/tabby.png");
        if (mobType.contains("chicken")) return new ResourceLocation("minecraft", "textures/entity/chicken.png");
        if (mobType.contains("bat")) return new ResourceLocation("minecraft", "textures/entity/bat.png");
        if (mobType.contains("bee")) return new ResourceLocation("minecraft", "textures/entity/bee/bee.png");
        return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
    }

    /**
     * Resolve the armor texture for a slot, optionally for a specific layer {@code type}
     * ("overlay" for the dyeable leather overlay pass, null for the base layer). Honors the
     * Forge per-item override (so modded armor with a custom texture path works), then falls
     * back to the vanilla {@code textures/models/armor/<material>_layer_<n>[_<type>].png}.
     */
    private static ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot, ItemStack stack,
                                                    net.minecraft.world.entity.Entity entity, String type) {
        try {
            String texturePath = item.getArmorTexture(stack, entity, slot, type);
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
            String suffix = (type == null || type.isEmpty()) ? "" : "_" + type;
            return new ResourceLocation("minecraft", "textures/models/armor/" + materialName + "_" + layer + suffix + ".png");
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

    public sealed interface MobOverlay permits HumanoidOverlay, IllagerOverlay, QuadrupedOverlay, VillagerOverlay, CreeperSwirlOverlay {}

    /** Full HumanoidModel render (drowned outer, stray clothing, zombie-villager profession). */
    public record HumanoidOverlay(ResourceLocation texture, HumanoidModel<?> model) implements MobOverlay {}

    /** Villager profession/type/level layer — drawn on the VillagerModel + split arms so it
     *  covers the whole body (used for plain villagers; zombie villagers use HumanoidOverlay). */
    public record VillagerOverlay(ResourceLocation texture) implements MobOverlay {}

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

            // 1. Biome-type overlay — always rendered, even for "none" profession.
            ResourceLocation typeTex = new ResourceLocation(typeKey.getNamespace(),
                    basePath + "type/" + typeKey.getPath() + ".png");
            result.add(villagerLayer(typeTex, isZombieVillager));

            // 2. Profession overlay — skip for NONE (matches vanilla, avoids missing texture).
            if (!isNoneProfession) {
                ResourceLocation profTex = new ResourceLocation(profKey.getNamespace(),
                        basePath + "profession/" + profKey.getPath() + ".png");
                result.add(villagerLayer(profTex, isZombieVillager));

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
                        result.add(villagerLayer(levelTex, isZombieVillager));
                    }
                }
            }
        }
        return result;
    }

    /** Build the right overlay carrier for a villager layer: zombie villagers render on the
     *  humanoid ZombieVillagerModel, plain villagers on the VillagerModel + split arms. */
    private static MobOverlay villagerLayer(ResourceLocation texture, boolean zombieVillager) {
        return zombieVillager ? new HumanoidOverlay(texture, zombieVillagerModel) : new VillagerOverlay(texture);
    }

    /**
     * Dispatch a single overlay to its matching render path. The transforms passed in are
     * the base model's transforms — overlays share the same physics bodies as the base.
     */
    private static void renderOverlay(MobOverlay overlay, PoseStack poseStack, MultiBufferSource buffer, int light,
                                      RagdollTransform torso, RagdollTransform head,
                                      RagdollTransform larm, RagdollTransform rarm,
                                      RagdollTransform lleg, RagdollTransform rleg, HumanoidScale humanoidScale) {
        if (overlay instanceof HumanoidOverlay h) {
            VertexConsumer ovc = buffer.getBuffer(RenderType.entityCutoutNoCull(h.texture()));
            renderHumanoidMob(poseStack, ovc, light, torso, head, larm, rarm, lleg, rleg, h.model(), humanoidScale);
        } else if (overlay instanceof IllagerOverlay i) {
            renderIllagerOverlayParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, i, humanoidScale);
        } else if (overlay instanceof VillagerOverlay vil) {
            VertexConsumer ovc = buffer.getBuffer(RenderType.entityCutoutNoCull(vil.texture()));
            renderVillagerParts(poseStack, ovc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
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
                                                  IllagerOverlay overlay, HumanoidScale modelScale) {
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
        renderHumanoidPartPhysics(poseStack, vc, body,     torso, torso, light, RagdollPart.TORSO, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head,  torso, light, RagdollPart.HEAD, modelScale);
        if (!overlay.limitToHeadBody()) {
            renderHumanoidPartPhysics(poseStack, vc, leftLeg,  lleg,  torso, light, RagdollPart.LEFT_LEG, modelScale);
            renderHumanoidPartPhysics(poseStack, vc, rightLeg, rleg,  torso, light, RagdollPart.RIGHT_LEG, modelScale);
            renderHumanoidPartPhysics(poseStack, vc, leftArm,  larm,  torso, light, RagdollPart.LEFT_ARM, modelScale);
            renderHumanoidPartPhysics(poseStack, vc, rightArm, rarm,  torso, light, RagdollPart.RIGHT_ARM, modelScale);
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

        // The sheep wool model (SheepFurModel) doesn't sit flush on the physics-aligned body
        // it rides: the head wool is 1px too far forward and the leg wool 3px too low. Correct
        // only the wool — the pig saddle shares this path and must not move. Offsets are in
        // model-pixel space (setPos units): +Z is toward the back of the head (face is -Z) and
        // -Y is up (model +Y points down), and they scale with the part so baby sheep line up too.
        boolean isWool = overlay.texture().equals(SHEEP_FUR_TEXTURE);
        float woolHeadZ = isWool ? 1.0f  : 0.0f;
        float woolLegY  = isWool ? -3.0f : 0.0f;

        renderAnimalPartTinted(poseStack, vc, body,       torso, torso, bodyOff.x,    bodyOff.y,             bodyOff.z,               halfPI, light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, headPart,   head,  torso, headOff.x,    headOff.y,             headOff.z + woolHeadZ,   0,      light, r, g, b);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg,  torso, lHindOff.x,   lHindOff.y + woolLegY, lHindOff.z,              0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg,  torso, rHindOff.x,   rHindOff.y + woolLegY, rHindOff.z,              0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm,  torso, lFrontOff.x,  lFrontOff.y + woolLegY, lFrontOff.z,            0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm,  torso, rFrontOff.x,  rFrontOff.y + woolLegY, rFrontOff.z,            0,      light, r, g, b, overlay.bodyScale());
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
