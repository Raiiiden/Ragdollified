package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Optional;

// Stable client-local control handle for an active or queued ragdoll.
@OnlyIn(Dist.CLIENT)
public final class RagdollHandle {
    private final int entityId;
    private final boolean ownsEntityHide;

    RagdollHandle(int entityId, boolean ownsEntityHide) {
        this.entityId = entityId;
        this.ownsEntityHide = ownsEntityHide;
    }

    public int entityId() { return entityId; }
    public boolean exists() { return RagdollifiedApi.hasRagdoll(entityId); }
    public Optional<RagdollState> getState() { return RagdollifiedApi.getState(entityId); }

    public RagdollHandle setPersistent(boolean persistent) {
        ClientRagdollManager.setPersistent(entityId, persistent);
        return this;
    }

    public boolean isPersistent() { return ClientRagdollManager.isPersistent(entityId); }

    // Treat this body as a fresh death ragdoll: pose kept, lifetime restarted, rest pose
    // reported again for the corpse. For bodies an integration kept alive before the death.
    public RagdollHandle restartDeathLifetime() {
        ClientRagdollManager.restartDeathLifetime(entityId);
        return this;
    }

    public boolean remove() {
        if (ownsEntityHide) RagdollifiedApi.setEntityHidden(entityId, false);
        RagdollifiedApi.detachCamera(entityId);
        return RagdollifiedApi.remove(entityId);
    }

    public Optional<RagdollDrag> beginDrag(DragEnd end, DragTarget target) {
        return RagdollifiedApi.beginDrag(entityId, end, target);
    }

    public Optional<RagdollDrag> beginDrag(Map<RagdollPart, Vec3> targets) {
        return RagdollifiedApi.beginDrag(entityId, targets);
    }

    public Optional<RagdollDrag> beginDrag(RagdollPart part, Vec3 target) {
        return RagdollifiedApi.beginDrag(entityId, part, target);
    }

    public boolean attachCamera(CameraAttachment attachment) {
        return attachment != null && RagdollifiedApi.attachCamera(entityId, attachment.part(), attachment.options());
    }

    // Detach this handle's camera only, leaving any other ragdoll's camera alone.
    public void detachCamera() {
        RagdollifiedApi.detachCamera(entityId);
    }
}
