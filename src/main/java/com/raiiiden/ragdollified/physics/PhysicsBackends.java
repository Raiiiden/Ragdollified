package com.raiiiden.ragdollified.physics;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.physics.jbullet.JBulletWorld;

import java.util.Locale;

// Picks the engine and builds a world. The only place in the mod that names a concrete backend.
public final class PhysicsBackends {

    private PhysicsBackends() {
    }

    public static final String JOLT = "jolt";
    public static final String JBULLET = "jbullet";

    // Sticky once resolved, so a mid-session config edit cannot leave two worlds on two engines with
    // bodies built by whichever was current at the time.
    private static volatile String resolvedEngine;

    public static PhysicsWorld create() {
        String requested = resolvedEngine != null
                ? resolvedEngine
                : normalise(RagdollifiedConfig.getPhysicsEngine());

        if (JOLT.equals(requested) && loadJolt()) {
            resolvedEngine = JOLT;
            // Logged unconditionally at INFO, since the fallback below is otherwise silent.
            Ragdollified.LOGGER.info("Ragdollified physics engine: jolt");
            return createJolt();
        }

        if (JOLT.equals(requested)) {
            Ragdollified.LOGGER.warn(
                    "Ragdollified physics engine: jbullet — jolt was configured but is unavailable "
                    + "({}). Ragdolls still work; they are running on the fallback engine.",
                    reasonOrUnknown());
        } else {
            Ragdollified.LOGGER.info("Ragdollified physics engine: jbullet");
        }
        resolvedEngine = JBULLET;
        return new JBulletWorld();
    }

    private static String reasonOrUnknown() {
        String reason = joltFailureReason();
        return reason == null ? "unknown" : reason;
    }

    // The engine actually in use, or null before the first world is built. For the perf log.
    public static String resolvedEngine() {
        return resolvedEngine;
    }

    private static String normalise(String configured) {
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        if (JOLT.equals(value) || JBULLET.equals(value)) return value;
        Ragdollified.LOGGER.warn("Unknown physicsEngine '{}'; expected '{}' or '{}'. Using '{}'.",
                configured, JOLT, JBULLET, JOLT);
        return JOLT;
    }

    // Split out so the Jolt classes are only linked once we have decided to use them: touching one
    // before its native library is loaded is a JVM crash rather than a catchable error.
    private static boolean loadJolt() {
        try {
            return com.raiiiden.ragdollified.physics.jolt.JoltNatives.load();
        } catch (Throwable t) {
            Ragdollified.LOGGER.error("Jolt native loading threw", t);
            return false;
        }
    }

    // Null unless a Jolt load was attempted and failed. Surfaced by the /ragdollified physics
    // command so the reason is reachable without digging through the log.
    public static String joltFailureReason() {
        try {
            return com.raiiiden.ragdollified.physics.jolt.JoltNatives.failureReason();
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static PhysicsWorld createJolt() {
        return new com.raiiiden.ragdollified.physics.jolt.JoltWorld(
                RagdollifiedConfig.getPhysicsSolverThreads());
    }
}
