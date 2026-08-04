package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.EnumMap;
import java.util.Map;

// Immutable, render-safe ragdoll state returned by getState(int).
@OnlyIn(Dist.CLIENT)
public record RagdollState(int entityId, Vec3 torsoPosition, Map<RagdollPart, Vec3> partPositions,
                           boolean settled, boolean frozen, int ageTicks) {
    static RagdollState from(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snapshot) {
        Map<RagdollPart, Vec3> positions = new EnumMap<>(RagdollPart.class);
        for (RagdollPart part : RagdollPart.values()) {
            int i = part.index;
            if (i < snapshot.positions.length && snapshot.positions[i] != null) {
                javax.vecmath.Vector3f position = snapshot.positions[i];
                positions.put(part, new Vec3(position.x, position.y, position.z));
            }
        }
        javax.vecmath.Vector3f torso = snapshot.cachedTorsoPos;
        return new RagdollState(ragdoll.getId(), new Vec3(torso.x, torso.y, torso.z),
                Map.copyOf(positions), snapshot.settled, snapshot.frozen, snapshot.ageTicks);
    }
}
