package com.raiiiden.ragdollified;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cache for mob poses received from clients
 * Stores poses temporarily until the mob dies and ragdoll is created
 */
public class ServerMobPoseCache {

    private static final Map<Integer, CachedPose> SERVER_POSES = new ConcurrentHashMap<>();

    private static class CachedPose {
        final MobPoseCapture.MobPose pose;
        final long timestamp;

        CachedPose(MobPoseCapture.MobPose pose) {
            this.pose = pose;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Store a pose received from client (called by packet handler)
     */
    public static void storePose(int entityId, MobPoseCapture.MobPose pose) {
        SERVER_POSES.put(entityId, new CachedPose(pose));
    }

    /**
     * Get and remove pose when creating ragdoll (called by physics)
     */
    public static MobPoseCapture.MobPose getAndRemovePose(int entityId) {
        CachedPose cached = SERVER_POSES.remove(entityId);
        return cached != null ? cached.pose : null;
    }

    /**
     * Cleanup old poses that were never used
     */
    public static void cleanup() {
        if (SERVER_POSES.isEmpty()) return;

        long now = System.currentTimeMillis();
        SERVER_POSES.entrySet().removeIf(entry -> {
            long age = now - entry.getValue().timestamp;
            return age > 5000; // Remove poses older than 5 seconds
        });
    }
}