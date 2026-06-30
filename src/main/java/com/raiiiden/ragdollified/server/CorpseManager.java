package com.raiiiden.ragdollified.server;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.compat.CuriosCompat;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import com.raiiiden.ragdollified.entity.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side corpse lifecycle.
 *
 * <p>On death the player's inventory (and optionally XP) is captured into a
 * {@link PendingCorpse}, the inventory is cleared so vanilla drops nothing, and the pending
 * is persisted via {@link PendingCorpseStore}. <b>No corpse entity exists yet.</b> When the
 * owner's client reports its ragdoll has settled, the corpse is spawned <i>directly at the
 * rest position</i> with the settled pose — so it never visibly teleports. A timeout (stuck
 * ragdoll / disconnect) or a server restart (crash recovery) instead spawns it flat at the
 * recorded death position. Either way the loot is safe from the moment of death.
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public class CorpseManager {

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();
        if (level.isClientSide) return;
        if (!RagdollifiedConfig.isCorpseEnabled()) return;
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;

        Inventory inv = player.getInventory();

        PendingCorpse p = new PendingCorpse();
        p.owner = player.getUUID();
        p.name = player.getGameProfile().getName();
        p.dimension = level.dimension();
        p.deathPos = player.position();

        // Worn armor copies for rendering (the real items also live in items[36..39]).
        p.boots  = inv.getItem(36).copy();
        p.legs   = inv.getItem(37).copy();
        p.chest  = inv.getItem(38).copy();
        p.helmet = inv.getItem(39).copy();

        boolean hasLoot = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i).copy();
            p.items.add(s);
            if (!s.isEmpty()) hasLoot = true;
        }

        // Curios (optional) — honoring each curio's DropRule (ALWAYS_KEEP / DESTROY skipped).
        CuriosCompat.Captured captured = null;
        if (CuriosCompat.isLoaded()) {
            captured = CuriosCompat.capture(player, event.getSource());
            p.curioStacks.addAll(captured.stacks);
            p.curioIds.addAll(captured.ids);
            if (!p.curioStacks.isEmpty()) hasLoot = true;
        }

        if (!hasLoot) return; // nothing to store; let vanilla handle drops/XP normally

        // Suppress vanilla drops: copies are already held in the pending. Persisting the
        // pending IS the safety net — the store autosaves with the world, so the cleared
        // inventory and the captured loot are written together (no loss window on crash).
        inv.clearContent();
        if (captured != null) CuriosCompat.clearCaptured(player, captured);

        p.deadlineTick = player.server.getTickCount() + RagdollifiedConfig.getCorpseSettleTimeoutTicks();

        PendingCorpseStore store = PendingCorpseStore.get(player.server.overworld());
        // Double-death within the settle window: materialize the older pending now so its
        // loot isn't silently dropped from the map.
        PendingCorpse old = store.pending.remove(p.owner);
        if (old != null) spawnFlat(player.server, old);
        store.pending.put(p.owner, p);
        store.setDirty();

        Ragdollified.LOGGER.debug("Captured pending corpse for {} at {}", p.name, p.deathPos);
    }

    @SubscribeEvent
    public static void onXpDrop(LivingExperienceDropEvent event) {
        if (!RagdollifiedConfig.isCorpseEnabled() || !RagdollifiedConfig.shouldStoreCorpseXp()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PendingCorpseStore store = PendingCorpseStore.get(player.server.overworld());
        PendingCorpse p = store.pending.get(player.getUUID());
        if (p == null) return;
        p.storedXp += event.getDroppedExperience();
        store.setDirty();
        event.setCanceled(true); // XP is stored in the corpse instead
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        PendingCorpseStore store = PendingCorpseStore.get(server.overworld());
        if (store.pending.isEmpty()) return;
        long now = server.getTickCount();
        double physDist = RagdollifiedConfig.PHYSICS_DISTANCE.get();
        boolean changed = false;
        Iterator<Map.Entry<UUID, PendingCorpse>> it = store.pending.entrySet().iterator();
        while (it.hasNext()) {
            PendingCorpse p = it.next().getValue();

            // While the owner is online but too far from the death position, their ragdoll is
            // distance-frozen (paused) on their client and physically cannot report a settle
            // yet. Keep pushing the deadline so we don't time out and freeze the corpse flat at
            // the death position — once they return, the ragdoll finishes falling and the real
            // resting place is reported. Only same-dimension owners count: a dimension change
            // unloads the client ragdoll entirely, so there we must fall back to the timeout.
            ServerPlayer owner = server.getPlayerList().getPlayer(p.owner);
            if (owner != null && owner.level().dimension().equals(p.dimension)
                    && owner.position().distanceToSqr(p.deathPos) > physDist * physDist) {
                p.deadlineTick = now + RagdollifiedConfig.getCorpseSettleTimeoutTicks();
                continue;
            }

            if (now >= p.deadlineTick) {
                spawnFlat(server, p); // settle never reported — freeze flat at death pos
                it.remove();
                changed = true;
            }
        }
        if (changed) store.setDirty();
    }

    /**
     * Crash/shutdown recovery: any pending that survived to the next start never got its
     * settle report, so spawn it flat at the death position. Cleared from the store after.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        PendingCorpseStore store = PendingCorpseStore.get(server.overworld());
        if (store.pending.isEmpty()) return;
        List<PendingCorpse> restore = new ArrayList<>(store.pending.values());
        store.pending.clear();
        store.setDirty();
        for (PendingCorpse p : restore) spawnFlat(server, p);
        Ragdollified.LOGGER.info("Restored {} corpse(s) from a previous session", restore.size());
    }

    /**
     * Apply a client-reported settle: spawn the corpse at the rest position with the settled
     * pose. Validates the reporter owns a pending and clamps the reported origin to a sane
     * radius of the death position (anti-cheat).
     */
    public static void handleSettle(ServerPlayer sender, double ox, double oy, double oz,
                                    RagdollTransform[] transforms) {
        PendingCorpseStore store = PendingCorpseStore.get(sender.server.overworld());
        PendingCorpse p = store.pending.get(sender.getUUID());
        if (p == null) return; // already spawned or timed out

        // Anti-cheat sanity on the client-reported rest position. Be generous vertically: a
        // ragdoll legitimately falls a long way before settling (off a cliff, into a ravine),
        // so only a large horizontal offset or ending up well ABOVE the death point is rejected.
        Vec3 origin = new Vec3(ox, oy, oz);
        double maxHoriz = Math.max(64.0, RagdollifiedConfig.PHYSICS_DISTANCE.get());
        double dx = origin.x - p.deathPos.x;
        double dz = origin.z - p.deathPos.z;
        double dy = origin.y - p.deathPos.y;
        if (dx * dx + dz * dz > maxHoriz * maxHoriz || dy > 16.0 || dy < -512.0) {
            origin = p.deathPos; // reject implausible teleport
        }

        ServerLevel level = sender.server.getLevel(p.dimension);
        if (level == null) level = sender.serverLevel();

        CorpseEntity corpse = build(level, p, origin);
        corpse.applyPose(transforms);
        finishSpawn(level, corpse, p, origin);

        store.pending.remove(sender.getUUID());
        store.setDirty();
    }

    // ============================
    // Spawning
    // ============================

    private static void spawnFlat(MinecraftServer server, PendingCorpse p) {
        ServerLevel level = server.getLevel(p.dimension);
        if (level == null) level = server.overworld();
        CorpseEntity corpse = build(level, p, p.deathPos);
        corpse.markPosedFlat();
        finishSpawn(level, corpse, p, p.deathPos);
    }

    private static CorpseEntity build(ServerLevel level, PendingCorpse p, Vec3 pos) {
        CorpseEntity corpse = new CorpseEntity(ModEntities.CORPSE.get(), level);
        corpse.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        corpse.initCorpse(p.owner, p.name, p.items, p.curioStacks, p.curioIds, p.storedXp,
                p.helmet, p.chest, p.legs, p.boots);
        return corpse;
    }

    private static void finishSpawn(ServerLevel level, CorpseEntity corpse, PendingCorpse p, Vec3 pos) {
        // Make sure the target chunk is loaded so the entity is accepted and persisted —
        // matters for the restart-recovery path where the death chunk is cold.
        level.getChunkAt(BlockPos.containing(pos.x, pos.y, pos.z));
        if (!level.addFreshEntity(corpse)) {
            Ragdollified.LOGGER.warn("Corpse for {} failed to spawn; dropping its loot at {}", p.name, pos);
            dropPendingLoot(level, p, pos);
        }
    }

    /** Last-resort fallback if the corpse entity can't be added: drop everything on the ground. */
    private static void dropPendingLoot(ServerLevel level, PendingCorpse p, Vec3 pos) {
        for (ItemStack s : p.items) {
            if (s != null && !s.isEmpty()) Containers.dropItemStack(level, pos.x, pos.y, pos.z, s);
        }
        for (ItemStack s : p.curioStacks) {
            if (s != null && !s.isEmpty()) Containers.dropItemStack(level, pos.x, pos.y, pos.z, s);
        }
        if (p.storedXp > 0) ExperienceOrb.award(level, pos, p.storedXp);
    }
}
