package com.raiiiden.ragdollified.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.compat.CuriosCompat;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Client half of the Curios integration: draws a dead body's worn curios.
//
// Curios renders worn items through CuriosLayer, a RenderLayer on the living entity's renderer,
// which asks CuriosRendererRegistry for a per-item ICurioRenderer. A ragdoll is not an entity and
// a corpse is not a LivingEntity, so neither ever gets that layer — this calls the same
// ICurioRenderer instances by hand instead, with a RenderLayerParent shim standing in for the
// player renderer.
//
// Only items whose mod registered an ICurioRenderer draw anything at all; a curio with no
// renderer is invisible on a live player too, so it is invisible here.
//
// Reflection only, like the rest of the Curios integration — no compile-time dependency, and
// every failure degrades to "no curios drawn" rather than breaking the body.
public final class CuriosRenderCompat {

    private static boolean available = false;

    private static Method getRenderer;   // CuriosRendererRegistry.getRenderer(Item) -> Optional<ICurioRenderer>
    private static Method renderCurio;   // ICurioRenderer.render(...12 args...)

    // Keyed the same way the damage-visual compats are: the source entity's id (an Integer) while
    // a ragdoll is the visible body. Corpses do not appear here — they carry their own persistent
    // curio snapshot in their render data, so they survive relogs and late joiners.
    private static final Map<Object, List<CuriosCompat.WornCurio>> CAPTURED = new ConcurrentHashMap<>();

    // Items whose renderer threw. A curio renderer is third-party code written against a live
    // entity, so some will not survive being called on a dead body — GeckoLib-backed ones in
    // particular reach for render context we cannot give them. Report the first failure loudly
    // (silently drawing nothing is impossible to diagnose from the outside) and then stop calling
    // that renderer, so one bad item neither spams the log nor costs a throw every frame.
    private static final Map<Item, Boolean> FAILED = new ConcurrentHashMap<>();

    private CuriosRenderCompat() {}

    public static void initialize() {
        if (!ModList.get().isLoaded("curios")) {
            Ragdollified.LOGGER.info("Curios not found - ragdolls and corpses will render without curios");
            return;
        }
        try {
            Class<?> registry = Class.forName("top.theillusivec4.curios.api.client.CuriosRendererRegistry");
            Class<?> renderer = Class.forName("top.theillusivec4.curios.api.client.ICurioRenderer");
            Class<?> slotContext = Class.forName("top.theillusivec4.curios.api.SlotContext");

            getRenderer = registry.getMethod("getRenderer", Item.class);
            renderCurio = renderer.getMethod("render",
                    net.minecraft.world.item.ItemStack.class, slotContext, PoseStack.class,
                    RenderLayerParent.class, MultiBufferSource.class, int.class,
                    float.class, float.class, float.class, float.class, float.class, float.class);

            available = true;
            Ragdollified.LOGGER.info("Curios rendering compatibility initialized successfully");
        } catch (Throwable t) {
            Ragdollified.LOGGER.error("Failed to initialize Curios rendering compatibility", t);
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    // Snapshot what the entity is wearing before it dies. The server empties the curio slots when
    // it builds the corpse, and the player may respawn, so reading them at render time is far too
    // late — this is the same reason the blood and damage compats capture at death.
    public static void capture(Object key, LivingEntity entity) {
        if (!available || entity == null) return;
        List<CuriosCompat.WornCurio> worn = CuriosCompat.captureWorn(entity);
        if (worn.isEmpty()) return;
        CAPTURED.put(key, worn);
    }

    public static List<CuriosCompat.WornCurio> wornFor(Object key) {
        if (!available || key == null) return Collections.emptyList();
        List<CuriosCompat.WornCurio> worn = CAPTURED.get(key);
        return worn != null ? worn : Collections.emptyList();
    }

    public static void evict(Object key) {
        if (!available) return;
        CAPTURED.remove(key);
    }

    public static void clearAll() {
        if (!available) return;
        CAPTURED.clear();
        // A renderer that failed against the last world gets another chance in the next one; the
        // cause may have been world state rather than the renderer itself.
        FAILED.clear();
    }

    // Draw one worn curio. The pose stack must already be at the body's model root, with the
    // parent model's parts posed to the physics pose — see ClientRagdollRenderer#renderBodyCurios,
    // which is what makes ICurioRenderer.followBodyRotations land items on the ragdoll rather
    // than on a standing figure.
    // Returns false when nothing was drawn — no registered renderer for this item, or its renderer
    // threw — so the caller can try another way of drawing it.
    public static boolean render(CuriosCompat.WornCurio worn, LivingEntity wearer,
                                 PoseStack poseStack, MultiBufferSource buffer, int light,
                                 RenderLayerParent<?, ?> parent, float partialTick, float ageInTicks) {
        if (!available || worn == null || worn.stack().isEmpty() || wearer == null) return false;
        Item item = worn.stack().getItem();
        if (FAILED.containsKey(item)) return false;

        // A pose-stack imbalance inside a third-party renderer would corrupt everything drawn
        // after it, so the curio gets its own push/pop to absorb one.
        poseStack.pushPose();
        try {
            Object result = getRenderer.invoke(null, item);
            if (!(result instanceof Optional<?> opt) || opt.isEmpty()) return false;

            Object ctx = CuriosCompat.slotContext(worn.slotId(), wearer, worn.index(),
                    worn.cosmetic(), worn.renderStatus());
            if (ctx == null) return false;

            // limbSwing/limbSwingAmount are zero: a dead body has no walk cycle, and the head
            // yaw/pitch a live renderer would pass is already baked into the physics head pose.
            renderCurio.invoke(opt.get(), worn.stack(), ctx, poseStack, parent, buffer, light,
                    0.0F, 0.0F, partialTick, ageInTicks, 0.0F, 0.0F);
            return true;
        } catch (Throwable t) {
            FAILED.put(item, Boolean.TRUE);
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                    ? ite.getCause() : t;
            Ragdollified.LOGGER.warn("Curio renderer for {} failed on a ragdoll/corpse and will be "
                            + "skipped for the rest of this world. Report this with the stack trace below.",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item), cause);
            return false;
        } finally {
            poseStack.popPose();
        }
    }

    // Minimal stand-in for the player renderer. Curio renderers use it for the wearer's model
    // (the one we posed) and, less often, its texture.
    public record Parent(EntityModel<?> model, ResourceLocation texture)
            implements RenderLayerParent<LivingEntity, EntityModel<LivingEntity>> {

        @Override
        @SuppressWarnings("unchecked")
        public EntityModel<LivingEntity> getModel() {
            return (EntityModel<LivingEntity>) model;
        }

        @Override
        public ResourceLocation getTextureLocation(LivingEntity entity) {
            return texture;
        }
    }
}
