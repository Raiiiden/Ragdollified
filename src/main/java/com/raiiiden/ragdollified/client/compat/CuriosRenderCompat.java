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

// Client half of the Curios integration: a ragdoll gets no CuriosLayer, so the same ICurioRenderer
// instances are called by hand through a shim. Reflection only, and any failure draws nothing.
public final class CuriosRenderCompat {

    private static boolean available = false;

    private static Method getRenderer;   // CuriosRendererRegistry.getRenderer(Item) -> Optional<ICurioRenderer>
    private static Method renderCurio;   // ICurioRenderer.render(...12 args...)

    // Keyed like the damage-visual compats, by the source entity id while a ragdoll is the visible
    // body. Corpses carry their own persistent curio snapshot instead, so they survive relogs.
    private static final Map<Object, List<CuriosCompat.WornCurio>> CAPTURED = new ConcurrentHashMap<>();

    // Items whose renderer threw. Curio renderers are third-party code written against a live entity,
    // so the first failure is logged loudly and that renderer is never called again.
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

    // Snapshot what the entity wears before it dies: the server empties the curio slots when building
    // the corpse, so reading at render time is far too late, exactly as for the blood compats.
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

    // Draw one worn curio, with the pose stack at the model root and the parent model already posed,
    // which is what lands items on the ragdoll. False when nothing was drawn, so the caller can retry.
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
