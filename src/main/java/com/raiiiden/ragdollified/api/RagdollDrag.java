package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

// A client-local no-impulse drag session created by beginDrag.
@OnlyIn(Dist.CLIENT)
public final class RagdollDrag implements AutoCloseable {
    private enum Mode { SINGLE_PART, PART_GROUP, END_GROUP }

    private final int entityId;
    private final Mode mode;
    private boolean closed;

    private RagdollDrag(int entityId, Mode mode) {
        this.entityId = entityId;
        this.mode = mode;
    }

    static RagdollDrag singlePart(int entityId, com.raiiiden.ragdollified.RagdollPart ignored) {
        return new RagdollDrag(entityId, Mode.SINGLE_PART);
    }

    static RagdollDrag partGroup(int entityId) {
        return new RagdollDrag(entityId, Mode.PART_GROUP);
    }

    static RagdollDrag endGroup(int entityId) {
        return new RagdollDrag(entityId, Mode.END_GROUP);
    }

    // Queue a new target position. Returns false when this drag has ended or its ragdoll is gone.
    public boolean moveTo(Vec3 target) {
        return !closed && mode == Mode.SINGLE_PART && ClientRagdollManager.updateDrag(entityId, target);
    }

    // Update all direct limb targets as one atomic physics-worker operation.
    public boolean moveTo(Map<com.raiiiden.ragdollified.RagdollPart, Vec3> targets) {
        return !closed && mode == Mode.PART_GROUP && ClientRagdollManager.updateDrag(entityId, targets);
    }

    // Update the anchor of an ARMS/LEGS drag.
    public boolean moveTo(DragTarget target) {
        return !closed && mode == Mode.END_GROUP && ClientRagdollManager.updateDrag(entityId, target);
    }

    public boolean isActive() {
        return !closed && ClientRagdollManager.isDragging(entityId);
    }

    // End the drag and restore normal physics for the selected part.
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ClientRagdollManager.endDrag(entityId);
    }
}
