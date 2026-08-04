package com.raiiiden.ragdollified.compat;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// All Curios access is isolated here and done by reflection only: nothing else in the mod
// names a Curios type and this file has no compile-time dependency either, so the integration
// is a true soft dependency that builds and runs with no Curios jar. Same approach as
// GeckoLibArmorHelper and the TACZ trackers.
//
// Every public method short-circuits when isLoaded() is false, and every reflective call is
// lenient — failures degrade to a no-op or vanilla behaviour rather than crashing. isLoaded()
// is true only when Curios is present and its API surface actually resolved.
public final class CuriosCompat {

    private CuriosCompat() {}

    // ----- cached reflection handles (resolved once in init()) -----
    private static Boolean loaded;

    private static Method mGetCuriosInventory; // CuriosApi.getCuriosInventory(LivingEntity)
    private static Method mGetCurio;           // CuriosApi.getCurio(ItemStack)         (optional: drop rules)
    private static Method mGetSlotIcon;        // CuriosApi.getSlotIcon(String)
    private static Method mIsStackValid;       // CuriosApi.isStackValid(SlotContext, ItemStack)
    private static Constructor<?> cSlotContext;// new SlotContext(String, LivingEntity, int, boolean, boolean)
    private static Method mGetCurios;          // ICuriosItemHandler.getCurios()
    private static Method mGetStacks;          // ICurioStacksHandler.getStacks()
    private static Method mHandlerGetDropRule; // ICurioStacksHandler.getDropRule()     (optional)
    private static Method mGetSlots;           // IDynamicStackHandler.getSlots()
    private static Method mGetStackInSlot;     // IDynamicStackHandler.getStackInSlot(int)
    private static Method mSetStackInSlot;     // IDynamicStackHandler.setStackInSlot(int, ItemStack)
    private static Method mCurioGetDropRule;   // ICurio.getDropRule(SlotContext, DamageSource, int, boolean) (optional)
    private static Method mGetCosmeticStacks;  // ICurioStacksHandler.getCosmeticStacks()  (rendering)
    private static Method mGetRenders;         // ICurioStacksHandler.getRenders()         (rendering)

    public static boolean isLoaded() {
        return init();
    }

    private static synchronized boolean init() {
        if (loaded != null) return loaded;
        if (!ModList.get().isLoaded("curios")) {
            loaded = false;
            return false;
        }
        try {
            Class<?> cApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> cSlotCtx = Class.forName("top.theillusivec4.curios.api.SlotContext");
            Class<?> cItemHandler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Class<?> cStacksHandler = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
            Class<?> cDynamic = Class.forName("top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler");

            mGetCuriosInventory = cApi.getMethod("getCuriosInventory", LivingEntity.class);
            mGetSlotIcon = cApi.getMethod("getSlotIcon", String.class);
            mIsStackValid = cApi.getMethod("isStackValid", cSlotCtx, ItemStack.class);
            cSlotContext = cSlotCtx.getConstructor(String.class, LivingEntity.class, int.class, boolean.class, boolean.class);
            mGetCurios = cItemHandler.getMethod("getCurios");
            mGetStacks = cStacksHandler.getMethod("getStacks");
            mGetSlots = cDynamic.getMethod("getSlots");
            mGetStackInSlot = cDynamic.getMethod("getStackInSlot", int.class);
            mSetStackInSlot = cDynamic.getMethod("setStackInSlot", int.class, ItemStack.class);
            mGetCosmeticStacks = cStacksHandler.getMethod("getCosmeticStacks");
            mGetRenders = cStacksHandler.getMethod("getRenders");

            // Drop-rule resolution is a refinement: if any of these can't be resolved we simply
            // treat every curio as DropRule.DEFAULT (drops into the corpse), so leave them null.
            try {
                Class<?> cICurio = Class.forName("top.theillusivec4.curios.api.type.capability.ICurio");
                mGetCurio = cApi.getMethod("getCurio", ItemStack.class);
                mHandlerGetDropRule = cStacksHandler.getMethod("getDropRule");
                mCurioGetDropRule = cICurio.getMethod("getDropRule", cSlotCtx, DamageSource.class, int.class, boolean.class);
            } catch (Throwable t) {
                mGetCurio = null;
                mHandlerGetDropRule = null;
                mCurioGetDropRule = null;
                Ragdollified.LOGGER.warn("Curios drop-rule API not found; corpses will capture all droppable curios", t);
            }

            loaded = true;
            Ragdollified.LOGGER.info("Curios detected - corpse curio capture enabled");
        } catch (Throwable t) {
            loaded = false;
            Ragdollified.LOGGER.warn("Curios present but API reflection failed; Curios integration disabled", t);
        }
        return loaded;
    }

    // Captured curios: stacks and ids are parallel, for the corpse and GUI, while indices
    // records each stack's slot within its handler so clearCaptured can empty exactly those.
    public static final class Captured {
        public final List<ItemStack> stacks = new ArrayList<>();
        public final List<String> ids = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
    }

    // Snapshot the curios that belong in the corpse: DropRule DEFAULT or ALWAYS_DROP. Anything
    // marked ALWAYS_KEEP or DESTROY is left for Curios to handle. Does not touch the entity —
    // hand the result to clearCaptured once the corpse exists.
    public static Captured capture(LivingEntity entity, DamageSource source) {
        Captured out = new Captured();
        if (!isLoaded()) return out;
        Map<String, Object> curios = curiosMap(entity);
        if (curios == null) return out;
        List<String> keys = new ArrayList<>(curios.keySet());
        Collections.sort(keys); // stable order across save/load + clients
        for (String id : keys) {
            Object sh = curios.get(id);
            Object stacks = invoke(mGetStacks, sh);
            if (stacks == null) continue;
            int slots = asInt(invoke(mGetSlots, stacks));
            for (int i = 0; i < slots; i++) {
                Object so = invoke(mGetStackInSlot, stacks, i);
                if (!(so instanceof ItemStack s) || s.isEmpty()) continue;
                // Never make a vanishing curio recoverable through a corpse. Curios remains
                // responsible for applying its normal death behavior to the live slot.
                if (EnchantmentHelper.hasVanishingCurse(s)) continue;
                String rule = resolveDropRule(entity, source, sh, id, i, s);
                if ("ALWAYS_KEEP".equals(rule) || "DESTROY".equals(rule)) {
                    continue; // honor the curio's own death rule
                }
                out.stacks.add(s.copy());
                out.ids.add(id);
                out.indices.add(i);
            }
        }
        return out;
    }

    // Effective drop-rule name: per-curio override, falling back to the slot type's rule.
    private static String resolveDropRule(LivingEntity entity, DamageSource source, Object handler,
                                          String id, int index, ItemStack stack) {
        String rule = "DEFAULT";
        if (mGetCurio != null && mCurioGetDropRule != null) {
            Object curio = resolve(invoke(mGetCurio, null, stack));
            if (curio != null) {
                Object ctx = newSlotContext(id, entity, index);
                Object r = invoke(mCurioGetDropRule, curio, ctx, source, 0, true);
                if (r instanceof Enum<?> e) rule = e.name();
            }
        }
        if ("DEFAULT".equals(rule) && mHandlerGetDropRule != null) {
            Object r = invoke(mHandlerGetDropRule, handler);
            if (r instanceof Enum<?> e) rule = e.name();
        }
        return rule;
    }

    // Empty exactly the slots that capture pulled, so Curios drops them nowhere.
    public static void clearCaptured(LivingEntity entity, Captured captured) {
        if (captured.ids.isEmpty() || !isLoaded()) return;
        Map<String, Object> curios = curiosMap(entity);
        if (curios == null) return;
        for (int k = 0; k < captured.ids.size(); k++) {
            Object sh = curios.get(captured.ids.get(k));
            if (sh == null) continue;
            Object stacks = invoke(mGetStacks, sh);
            if (stacks != null) invoke(mSetStackInSlot, stacks, captured.indices.get(k), ItemStack.EMPTY);
        }
    }

    // One worn curio as the renderers need to see it: which slot type and index it sits in,
    // whether the stack came from the cosmetic overlay, and whether the wearer has rendering
    // switched on for that slot. Kept free of Curios types so it can be stored and passed around
    // (corpse render data, ragdoll snapshots) with no Curios jar present.
    public record WornCurio(String slotId, int index, boolean cosmetic, boolean renderStatus, ItemStack stack) {}

    // The curios a wearer is currently showing, resolved exactly the way Curios' own render layer
    // resolves them: a cosmetic stack wins over the real one, and the real one is only shown when
    // that slot's render toggle is on. Order follows the slot ids so repeated captures agree.
    public static List<WornCurio> captureWorn(LivingEntity entity) {
        List<WornCurio> out = new ArrayList<>();
        if (entity == null || !isLoaded()) return out;
        Map<String, Object> curios = curiosMap(entity);
        if (curios == null) return out;
        List<String> keys = new ArrayList<>(curios.keySet());
        Collections.sort(keys);
        for (String id : keys) {
            Object sh = curios.get(id);
            Object stacks = invoke(mGetStacks, sh);
            Object cosmetics = invoke(mGetCosmeticStacks, sh);
            if (stacks == null) continue;
            Object rendersO = invoke(mGetRenders, sh);
            List<?> renders = (rendersO instanceof List<?> l) ? l : Collections.emptyList();
            int slots = asInt(invoke(mGetSlots, stacks));
            for (int i = 0; i < slots; i++) {
                boolean renderStatus = renders.size() > i && Boolean.TRUE.equals(renders.get(i));
                Object co = cosmetics != null ? invoke(mGetStackInSlot, cosmetics, i) : null;
                ItemStack stack = (co instanceof ItemStack cs) ? cs : ItemStack.EMPTY;
                boolean cosmetic = true;
                if (stack.isEmpty() && renderStatus) {
                    Object so = invoke(mGetStackInSlot, stacks, i);
                    stack = (so instanceof ItemStack s) ? s : ItemStack.EMPTY;
                    cosmetic = false;
                }
                if (stack.isEmpty()) continue;
                out.add(new WornCurio(id, i, cosmetic, renderStatus, stack.copy()));
            }
        }
        return out;
    }

    // A Curios SlotContext as an opaque Object, for callers that hand it straight back to a
    // Curios API method. Null when Curios is absent or the constructor call failed.
    public static Object slotContext(String id, LivingEntity wearer, int index, boolean cosmetic, boolean visible) {
        if (!isLoaded()) return null;
        try {
            return cSlotContext.newInstance(id, wearer, index, cosmetic, visible);
        } catch (Throwable t) {
            return null;
        }
    }

    // Slot-type icon for empty curio slots (client). Null on server / on any failure.
    public static ResourceLocation getSlotIcon(String identifier) {
        if (!isLoaded()) return null;
        Object r = invoke(mGetSlotIcon, null, identifier);
        return (r instanceof ResourceLocation rl) ? rl : null;
    }

    // Equip stack into the wearer's first empty curio slot of that id which accepts it, true
    // if placed so the caller can clear the source. Used by the corpse Take All button.
    public static boolean equipInEmpty(LivingEntity wearer, String id, ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) return false;
        Map<String, Object> curios = curiosMap(wearer);
        if (curios == null) return false;
        Object sh = curios.get(id);
        if (sh == null) return false;
        Object stacks = invoke(mGetStacks, sh);
        if (stacks == null) return false;
        int slots = asInt(invoke(mGetSlots, stacks));
        for (int i = 0; i < slots; i++) {
            Object so = invoke(mGetStackInSlot, stacks, i);
            if (so instanceof ItemStack es && !es.isEmpty()) continue;
            if (!isValid(id, wearer, stack)) break;
            invoke(mSetStackInSlot, stacks, i, stack.copy());
            return true;
        }
        return false;
    }

    // Swap worn curios with a corpse's parallel curio bag: each corpse stack at base + k, of
    // slot type ids.get(k), trades with the wearer's next worn slot of that type. Entries with
    // no matching slot stay on the corpse. Used by the corpse Swap button.
    public static void swapWorn(LivingEntity wearer, Container corpse, int base, List<String> ids) {
        if (!isLoaded()) return;
        Map<String, Object> curios = curiosMap(wearer);
        if (curios == null) return;
        Map<String, Integer> cursor = new HashMap<>();
        for (int k = 0; k < ids.size(); k++) {
            String id = ids.get(k);
            Object sh = curios.get(id);
            if (sh == null) continue;
            Object stacks = invoke(mGetStacks, sh);
            if (stacks == null) continue;
            int slots = asInt(invoke(mGetSlots, stacks));
            int idx = cursor.getOrDefault(id, 0);
            if (idx >= slots) continue;
            cursor.put(id, idx + 1);
            Object wornO = invoke(mGetStackInSlot, stacks, idx);
            ItemStack worn = (wornO instanceof ItemStack w) ? w.copy() : ItemStack.EMPTY;
            ItemStack corpseStack = corpse.getItem(base + k).copy();
            invoke(mSetStackInSlot, stacks, idx, corpseStack);
            corpse.setItem(base + k, worn);
        }
    }

    // Whether stack may be placed into a curio slot of identifier. Lenient on error.
    public static boolean isValid(String identifier, LivingEntity wearer, ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) return false;
        Object ctx = newSlotContext(identifier, wearer, 0);
        if (ctx == null) return true;
        Object r = invoke(mIsStackValid, null, ctx, stack);
        return !(r instanceof Boolean) || (Boolean) r; // null/error -> lenient true
    }

    // ============================
    // Reflection plumbing
    // ============================

    // CuriosApi.getCuriosInventory(entity).getCurios() as a raw Map<id, ICurioStacksHandler>.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> curiosMap(LivingEntity entity) {
        Object handler = resolve(invoke(mGetCuriosInventory, null, entity));
        if (handler == null) return null;
        Object map = invoke(mGetCurios, handler);
        return (map instanceof Map) ? (Map<String, Object>) map : null;
    }

    private static Object newSlotContext(String id, LivingEntity entity, int index) {
        try {
            return cSlotContext.newInstance(id, entity, index, false, true);
        } catch (Throwable t) {
            return null;
        }
    }

    // Unwrap a Curios API return that may be a Optional or a LazyOptional.
    private static Object resolve(Object opt) {
        if (opt == null) return null;
        if (opt instanceof Optional<?> o) return o.orElse(null);
        if (opt instanceof LazyOptional<?> lo) return lo.resolve().orElse(null);
        return null;
    }

    private static Object invoke(Method m, Object target, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int asInt(Object o) {
        return (o instanceof Integer i) ? i : 0;
    }
}
