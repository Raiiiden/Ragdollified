package com.raiiiden.ragdollified;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cache for mob poses received from clients
 * Continuously updated - always has the latest pose before death
 */
public class ServerMobPoseCache {

    private static final Map<Integer, CachedPose> SERVER_POSES = new ConcurrentHashMap<>();

    private static class CachedPose {
        MobPoseCapture.MobPose pose; // Mutable - gets updated
        long timestamp;

        CachedPose(MobPoseCapture.MobPose pose) {
            this.pose = pose;
            this.timestamp = System.currentTimeMillis();
        }

        void update(MobPoseCapture.MobPose newPose) {
            this.pose = newPose;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Store/update a pose received from client (called by packet handler)
     * Continuously updates - always keeps latest pose
     */
    public static void storePose(int entityId, MobPoseCapture.MobPose pose) {
        CachedPose cached = SERVER_POSES.get(entityId);
        if (cached != null) {
            // Update existing
            cached.update(pose);
        } else {
            // Store new
            SERVER_POSES.put(entityId, new CachedPose(pose));
        }
    }

    /**
     * Get and remove pose when creating ragdoll (called by physics)
     */
    public static MobPoseCapture.MobPose getAndRemovePose(int entityId) {
        CachedPose cached = SERVER_POSES.remove(entityId);
        return cached != null ? cached.pose : null;
    }

    /**
     * Cleanup old poses that were never used (mob didn't die)
     */
    public static void cleanup() {
        if (SERVER_POSES.isEmpty()) return;

        long now = System.currentTimeMillis();
        SERVER_POSES.entrySet().removeIf(entry -> {
            long age = now - entry.getValue().timestamp;
            return age > 10000; // Remove poses older than 10 seconds
        });
    }
}