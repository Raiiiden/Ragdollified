package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.RagdollManager;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class DeathRagdollManager {
    private static final Map<Integer, RagdollManager.ClientRagdoll> DEATH_RAGDOLLS = new HashMap<>();
    private static final Minecraft mc = Minecraft.getInstance();

    public static void addOrUpdate(int entityId, RagdollTransform[] transforms, int serverTick) {
        RagdollManager.ClientRagdoll r = DEATH_RAGDOLLS.get(entityId);
        if (r == null) {
            r = new RagdollManager.ClientRagdoll(entityId, entityId);
            DEATH_RAGDOLLS.put(entityId, r);
        }

        // Use client world time for smoother interpolation
        // This syncs with Minecraft's tick system
        long clientTime = mc.level != null ? mc.level.getGameTime() * 50L : System.currentTimeMillis();
        r.pushUpdate(transforms, clientTime);
    }

    public static void remove(int entityId){
        DEATH_RAGDOLLS.remove(entityId);
    }

    @Nullable
    public static RagdollManager.ClientRagdoll get(int entityId){
        return DEATH_RAGDOLLS.get(entityId);
    }

    public static Collection<RagdollManager.ClientRagdoll> getAll(){
        return new ArrayList<>(DEATH_RAGDOLLS.values());
    }
}