package com.raiiiden.ragdollified.server;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.compat.CuriosCompat;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import com.raiiiden.ragdollified.entity.ModEntities;
import com.raiiiden.ragdollified.item.CorpseCompassItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraftforge.eventbus.api.EventPriority;
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
 * a nearby client reports its ragdoll has settled, the corpse is spawned <i>directly at the
 * rest position</i> with the settled pose — so it never visibly teleports. A timeout (stuck
 * ragdoll / disconnect) or a server restart (crash recovery) instead spawns it flat at the
 * recorded death position. Either way the loot is safe from the moment of death.
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public class CorpseManager {

    private static final int SETTLE_QUIET_TICKS = 5;

    // LOWEST so the inventory is cleared only AFTER PhysicsHooks (HIGHEST) has read the worn
    // armor into the ragdoll spawn packet — otherwise the ragdoll would render without armor.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();
        if (level.isClientSide) return;
        if (!RagdollifiedConfig.isCorpseEnabled()) return;
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;

        Inventory inv = player.getInventory();

        PendingCorpse p = new PendingCorpse();
        p.owner = player.getUUID();
        p.corpseId = UUID.randomUUID();
        p.name = player.getGameProfile().getName();
        p.dimension = level.dimension();
        p.deathPos = player.position();
        p.deathEntityId = player.getId(); // == the client ragdoll's originalEntityId (see PendingCorpse)

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

        if (!hasLoot) return; // nothing to store; let vanilla handle drops/XP normally (no corpse)

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
        store.lastDeaths.put(p.owner, p.corpseId);

        // Queue a Corpse Compass for this player's next respawn, targeting the death position.
        // If the corpse finishes settling before they respawn, finishSpawn refreshes this to the
        // real resting position (see below).
        if (RagdollifiedConfig.isCorpseCompassEnabled()) {
            store.deathTargets.put(p.owner, buildTarget(p, p.deathPos));
        }
        store.setDirty();

        Ragdollified.LOGGER.debug("Captured pending corpse for {} at {}", p.name, p.deathPos);
    }

    /**
     * On respawn, hand the player the Corpse Compass queued for them at death (if the feature is
     * enabled and a corpse was actually created). Gated on a queued target rather than the respawn
     * reason, so returning from the End (which has no queued target) never triggers it.
     */
    @SubscribeEvent
    public static void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PendingCorpseStore store = PendingCorpseStore.get(player.server.overworld());
        CompoundTag target = store.deathTargets.remove(player.getUUID());
        if (target == null) return;
        store.setDirty();
        if (!RagdollifiedConfig.isCorpseCompassEnabled()) return; // toggled off after death: just drop the queue

        UUID corpseId = target.hasUUID("CorpseId") ? target.getUUID("CorpseId") : null;
        Vec3 pos = new Vec3(target.getDouble("X"), target.getDouble("Y"), target.getDouble("Z"));
        ResourceKey<Level> dim = null;
        if (target.contains("Dim")) {
            ResourceLocation loc = ResourceLocation.tryParse(target.getString("Dim"));
            if (loc != null) dim = ResourceKey.create(Registries.DIMENSION, loc);
        }
        UUID ownerId = target.hasUUID("Owner") ? target.getUUID("Owner") : player.getUUID();
        int ragdollEntityId = target.contains("DeathEntityId") ? target.getInt("DeathEntityId") : -1;
        ItemStack compass = CorpseCompassItem.create(
                corpseId, ownerId, ragdollEntityId, pos, dim, target.getString("Name"),
                readArmor(target, "Helmet"), readArmor(target, "Chest"),
                readArmor(target, "Legs"), readArmor(target, "Boots"));
        if (!player.getInventory().add(compass)) {
            player.drop(compass, false);
        }
    }

    private static CompoundTag buildTarget(PendingCorpse p, Vec3 pos) {
        CompoundTag t = new CompoundTag();
        if (p.corpseId != null) t.putUUID("CorpseId", p.corpseId);
        if (p.owner != null) t.putUUID("Owner", p.owner);
        if (p.deathEntityId >= 0) t.putInt("DeathEntityId", p.deathEntityId);
        t.putDouble("X", pos.x);
        t.putDouble("Y", pos.y);
        t.putDouble("Z", pos.z);
        if (p.dimension != null) t.putString("Dim", p.dimension.location().toString());
        if (p.name != null) t.putString("Name", p.name);
        writeArmor(t, "Helmet", p.helmet);
        writeArmor(t, "Chest", p.chest);
        writeArmor(t, "Legs", p.legs);
        writeArmor(t, "Boots", p.boots);
        return t;
    }

    private static void writeArmor(CompoundTag t, String key, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) t.put(key, stack.save(new CompoundTag()));
    }

    private static ItemStack readArmor(CompoundTag t, String key) {
        return t.contains(key) ? ItemStack.of(t.getCompound(key)) : ItemStack.EMPTY;
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
        double physDist = RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE);
        boolean changed = false;
        Iterator<Map.Entry<UUID, PendingCorpse>> it = store.pending.entrySet().iterator();
        while (it.hasNext()) {
            PendingCorpse p = it.next().getValue();

            // A settle waits a few server ticks before materialization. If a push was already
            // in flight, its newer revision invalidates the candidate before this point.
            if (p.settleOrigin != null) {
                if (now >= p.settleReadyTick && p.settleRevision == p.impulseRevision) {
                    spawnCaptured(server, p, p.settleOrigin, p.settleTransforms);
                    it.remove();
                    changed = true;
                }
                // Never let the ordinary timeout replace a validated candidate with a flat
                // corpse during its short impulse-race quiet window.
                continue;
            }

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
        handleSettleFor(sender, sender.getUUID(), -1, -1, false, ox, oy, oz, transforms);
    }

    /**
     * Apply a settle observed by any nearby player. UUID plus death entity id prevents a stale
     * or unrelated ragdoll from consuming the pending corpse; proximity and dimension checks
     * prevent remote clients from choosing its position.
     */
    public static void handleObservedSettle(ServerPlayer sender, UUID ownerUUID, int ragdollEntityId,
                                            int impulseRevision,
                                            double ox, double oy, double oz,
                                            RagdollTransform[] transforms) {
        handleSettleFor(sender, ownerUUID, ragdollEntityId, impulseRevision,
                true, ox, oy, oz, transforms);
    }

    private static void handleSettleFor(ServerPlayer sender, UUID ownerUUID, int ragdollEntityId,
                                        int impulseRevision, boolean observed, double ox, double oy, double oz,
                                        RagdollTransform[] transforms) {
        PendingCorpseStore store = PendingCorpseStore.get(sender.server.overworld());
        PendingCorpse p = store.pending.get(ownerUUID);
        if (p == null) return; // already spawned or timed out
        if (observed && p.deathEntityId != ragdollEntityId) return;
        if (observed && p.impulseRevision != impulseRevision) return;

        ServerLevel level = sender.server.getLevel(p.dimension);
        if (level == null) return;

        if (!Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)) return;
        Vec3 origin = new Vec3(ox, oy, oz);

        if (observed) {
            if (!sender.level().dimension().equals(p.dimension)) return;
            double reportRange = RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE) + 16.0;
            if (sender.position().distanceToSqr(origin) > reportRange * reportRange) return;
        }

        if (!isSanePose(transforms)) return;

        // Anti-cheat sanity on the client-reported rest position. Be generous vertically: a
        // ragdoll legitimately falls a long way before settling (off a cliff, into a ravine),
        // so only a large horizontal offset or ending up well ABOVE the death point is rejected.
        double maxHoriz = Math.max(64.0, RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE));
        double dx = origin.x - p.deathPos.x;
        double dz = origin.z - p.deathPos.z;
        double dy = origin.y - p.deathPos.y;
        if (dx * dx + dz * dz > maxHoriz * maxHoriz || dy > 16.0 || dy < -512.0) {
            origin = p.deathPos; // reject implausible teleport
        }

        // Do not materialize in the packet handler. A settle from another observer can race an
        // impulse that is already travelling to the server; a short quiet window lets that
        // impulse increment the revision and invalidate this candidate instead of teleporting
        // the pushed ragdoll into a stale corpse pose.
        if (p.settleOrigin == null || p.settleRevision != impulseRevision) {
            p.settleOrigin = origin;
            p.settleTransforms = transforms;
            p.settleRevision = impulseRevision;
            p.settleReadyTick = sender.server.getTickCount() + SETTLE_QUIET_TICKS;
        }
        store.setDirty();
    }

    private static void spawnCaptured(MinecraftServer server, PendingCorpse p, Vec3 origin,
                                      RagdollTransform[] transforms) {
        ServerLevel level = server.getLevel(p.dimension);
        if (level == null) level = server.overworld();
        CorpseEntity corpse = build(level, p, origin);
        finishSpawn(level, corpse, p, origin, transforms);
    }

    private static boolean isSanePose(RagdollTransform[] transforms) {
        if (transforms == null || transforms.length < 6) return false;
        for (int i = 0; i < 6; i++) {
            RagdollTransform t = transforms[i];
            if (t == null) continue;
            if (t.partId != i || !Float.isFinite(t.position.x) || !Float.isFinite(t.position.y)
                    || !Float.isFinite(t.position.z) || Math.abs(t.position.x) > 16.0f
                    || Math.abs(t.position.y) > 16.0f || Math.abs(t.position.z) > 16.0f
                    || !Float.isFinite(t.rotation.x) || !Float.isFinite(t.rotation.y)
                    || !Float.isFinite(t.rotation.z) || !Float.isFinite(t.rotation.w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * OP retrieve command backing: find the corpse with {@code corpseId}, give its contents (+ XP)
     * to {@code target}, and erase it. Checks loaded corpse entities across every dimension first,
     * then the pending store (a corpse whose ragdoll hasn't settled into an entity yet). Returns
     * {@code false} if no match is found (e.g. the corpse is in an unloaded chunk).
     */
    public static boolean retrieveByCorpseId(MinecraftServer server, UUID corpseId, ServerPlayer target) {
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (e instanceof CorpseEntity corpse && corpseId.equals(corpse.getCorpseId())) {
                    corpse.retrieveInto(target);
                    return true;
                }
            }
        }
        // Not yet materialized — the loot still lives in the pending store.
        PendingCorpseStore store = PendingCorpseStore.get(server.overworld());
        UUID owner = null;
        for (Map.Entry<UUID, PendingCorpse> en : store.pending.entrySet()) {
            if (corpseId.equals(en.getValue().corpseId)) { owner = en.getKey(); break; }
        }
        if (owner != null) {
            givePendingTo(store.pending.remove(owner), target);
            store.deathTargets.remove(owner);
            store.setDirty();
            return true;
        }
        // Spawned but its chunk is unloaded: recover from the persistent loot index. The
        // tombstone makes the chunk-stored entity discard itself when that chunk next loads.
        PendingCorpse indexed = store.materialized.remove(corpseId);
        if (indexed != null) {
            givePendingTo(indexed, target);
            store.removedCorpseIds.add(corpseId);
            store.setDirty();
            return true;
        }
        return false;
    }

    public static boolean retrieveLastDeath(MinecraftServer server, UUID owner, ServerPlayer target) {
        PendingCorpseStore store = PendingCorpseStore.get(server.overworld());
        UUID corpseId = store.lastDeaths.get(owner);
        return corpseId != null && retrieveByCorpseId(server, corpseId, target);
    }

    private static void givePendingTo(PendingCorpse p, ServerPlayer target) {
        giveOrDrop(p.items, target);
        giveOrDrop(p.curioStacks, target);
        if (p.storedXp > 0) target.giveExperiencePoints(p.storedXp);
    }

    private static void giveOrDrop(List<ItemStack> stacks, ServerPlayer target) {
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            ItemStack give = s.copy();
            if (!target.getInventory().add(give)) target.drop(give, false);
        }
    }

    // ============================
    // Spawning
    // ============================

    private static void spawnFlat(MinecraftServer server, PendingCorpse p) {
        ServerLevel level = server.getLevel(p.dimension);
        if (level == null) level = server.overworld();
        CorpseEntity corpse = build(level, p, p.deathPos);
        finishSpawn(level, corpse, p, p.deathPos, null);
    }

    private static CorpseEntity build(ServerLevel level, PendingCorpse p, Vec3 pos) {
        CorpseEntity corpse = new CorpseEntity(ModEntities.CORPSE.get(), level);
        corpse.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        corpse.initCorpse(p.owner, p.corpseId, p.name, p.deathEntityId, p.items, p.curioStacks, p.curioIds, p.storedXp,
                p.helmet, p.chest, p.legs, p.boots);
        return corpse;
    }

    private static void finishSpawn(ServerLevel level, CorpseEntity corpse, PendingCorpse p, Vec3 pos,
                                    RagdollTransform[] reportedPose) {
        // The persistent corpse entity now owns late tracking for this player death.
        ServerRagdollSyncManager.remove(p.deathEntityId);

        // Make sure the target chunk is loaded so the entity is accepted and persisted —
        // matters for the restart-recovery path where the death chunk is cold.
        level.getChunkAt(BlockPos.containing(pos.x, pos.y, pos.z));

        // Ground the corpse's interaction/physics anchor before networking it. The captured
        // visual pose is compensated below, so this collision move does not move the body parts.
        corpse.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.0, -4.0, 0.0));
        corpse.setDeltaMovement(Vec3.ZERO);
        corpse.setOldPosAndRot();
        Vec3 settledAnchor = corpse.position();

        // The reported origin is the already-settled torso center, while the corpse entity's
        // position is the bottom of its interaction box. Grounding that box moves its origin
        // downward. Offset the relative pose by the inverse movement so every rendered body
        // part stays at exactly the world position reported by the observing client.
        if (reportedPose != null) {
            corpse.applyPose(translatePose(reportedPose, pos.subtract(settledAnchor)));
        } else {
            corpse.markPosedFlat();
        }

        // If this player hasn't respawned yet, refresh their queued compass target to the corpse's
        // actual resting position (more accurate than the raw death position it was seeded with).
        PendingCorpseStore store = PendingCorpseStore.get(level.getServer().overworld());
        CompoundTag queued = store.deathTargets.get(p.owner);
        if (queued != null && p.corpseId != null && queued.hasUUID("CorpseId")
                && p.corpseId.equals(queued.getUUID("CorpseId"))) {
            store.deathTargets.put(p.owner, buildTarget(p, settledAnchor));
            store.setDirty();
        }

        if (!level.addFreshEntity(corpse)) {
            Ragdollified.LOGGER.warn("Corpse for {} failed to spawn; dropping its loot at {}", p.name, settledAnchor);
            dropPendingLoot(level, p, settledAnchor);
            if (p.corpseId != null) store.removedCorpseIds.add(p.corpseId);
            store.setDirty();
        } else if (p.corpseId != null) {
            PendingCorpse indexed = p.copy();
            indexed.deathPos = settledAnchor;
            store.materialized.put(p.corpseId, indexed);
            store.setDirty();
        }
    }

    private static RagdollTransform[] translatePose(RagdollTransform[] pose, Vec3 offset) {
        RagdollTransform[] translated = new RagdollTransform[6];
        for (int i = 0; i < translated.length && i < pose.length; i++) {
            RagdollTransform t = pose[i];
            if (t == null) continue;
            translated[i] = new RagdollTransform(i,
                    new javax.vecmath.Vector3f(
                            t.position.x + (float) offset.x,
                            t.position.y + (float) offset.y,
                            t.position.z + (float) offset.z),
                    new javax.vecmath.Quat4f(t.rotation));
        }
        return translated;
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
