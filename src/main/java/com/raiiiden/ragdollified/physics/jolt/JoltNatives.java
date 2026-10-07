package com.raiiiden.ragdollified.physics.jolt;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.zarith.core.jolt.ZarithJolt;

// Jolt is loaded by Zarith, which carries the one jolt-jni (classes and natives) in the game and
// loads it once for every mod that needs it. Ragdollified bundles none and never calls System.load
// itself: two mods each shipping jolt-jni is a split package that stops the game at launch.
//
// Nothing in the jolt package may be touched until isReady(), since calling an unloaded jolt-jni
// crashes the JVM.
public final class JoltNatives {

    private JoltNatives() {
    }

    public static final String JOLT_JNI_VERSION = ZarithJolt.JOLT_JNI_VERSION;

    private static boolean reported;

    public static boolean isReady() {
        return ZarithJolt.isReady();
    }

    // Null while loading has not been attempted or has succeeded.
    public static String failureReason() {
        return ZarithJolt.failureReason();
    }

    // Idempotent. Returns false and records a reason rather than throwing: a physics engine that
    // will not load must degrade to "no ragdolls", never to a crashed game.
    public static synchronized boolean load() {
        boolean ready = ZarithJolt.load();
        if (!reported) {
            reported = true;
            if (ready) {
                Ragdollified.LOGGER.info("Jolt physics ready via Zarith: {}", ZarithJolt.versionString());
            } else {
                Ragdollified.LOGGER.error("Jolt physics unavailable ({}); falling back to JBullet",
                        ZarithJolt.failureReason());
            }
        }
        return ready;
    }
}
