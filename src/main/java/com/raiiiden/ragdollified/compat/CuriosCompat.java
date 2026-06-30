package com.raiiiden.ragdollified.compat;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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

/**
 * All Curios API access is isolated in this class via <b>reflection only</b> — the rest of
 * the mod never references Curios types, and this file has no compile-time dependency on the
 * Curios API either. That keeps the integration a true soft dependency: the mod builds and
 * runs with no Curios jar present, mirroring how {@code GeckoLibArmorHelper} and the TACZ
 * trackers handle their optional mods.
 *
 * <p>Every public method short-circuits when {@link #isLoaded()} is false, and every
 * reflective call is lenient (failures degrade to no-op / vanilla behavior rather than
 * crashing). {@code isLoaded()} returns true only when Curios is present <i>and</i> its API
 * surface resolved successfully.
 */
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

    /**
     * Captured curios. {@code stacks}/{@code ids} are parallel (for the corpse + GUI);
     * {@code indices} records each stack's slot index within its handler so exactly those
     * slots can be cleared later via {@link #clearCaptured}.
     */
    public static final class Captured {
        public final List<ItemStack> stacks = new ArrayList<>();
        public final List<String> ids = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
    }

    /**
     * Snapshot the curios that should drop into the corpse — i.e. those whose effective
     * {@link ICurio.DropRule} is DEFAULT or ALWAYS_DROP. Curios flagged ALWAYS_KEEP or
     * DESTROY are skipped (left for Curios to keep on the player / destroy as usual).
     * Does NOT modify the entity; pass the result to {@link #clearCaptured} after the
     * corpse spawns.
     */
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

    /** Effective drop-rule name: per-curio override, falling back to the slot type's rule. */
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

    /** Empty exactly the slots that {@link #capture} pulled, so Curios drops them nowhere. */
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

    /** Slot-type icon for empty curio slots (client). Null on server / on any failure. */
    public static ResourceLocation getSlotIcon(String identifier) {
        if (!isLoaded()) return null;
        Object r = invoke(mGetSlotIcon, null, identifier);
        return (r instanceof ResourceLocation rl) ? rl : null;
    }

    /**
     * Try to equip {@code stack} into the wearer's first EMPTY curio slot of {@code id} that
     * accepts it. Returns true if it was placed (so the caller can clear the source slot).
     * Used by the corpse "Take All" button to auto-equip looted curios.
     */
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

    /**
     * Swap the wearer's worn curios with a corpse's parallel curio bag. For each entry {@code k}
     * the corpse stack at {@code base + k} (whose slot type is {@code ids.get(k)}) is exchanged
     * with the wearer's next worn slot of that same type. Entries the wearer has no matching slot
     * for are left in the corpse. Used by the corpse "Swap" button.
     */
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

    /** Whether {@code stack} may be placed into a curio slot of {@code identifier}. Lenient on error. */
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

    /** {@code CuriosApi.getCuriosInventory(entity).getCurios()} as a raw {@code Map<id, ICurioStacksHandler>}. */
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

    /** Unwrap a Curios API return that may be a {@link Optional} or a {@link LazyOptional}. */
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
