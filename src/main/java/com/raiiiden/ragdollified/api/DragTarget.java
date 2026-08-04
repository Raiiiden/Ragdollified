package com.raiiiden.ragdollified.api;

import net.minecraft.world.phys.Vec3;

// Immutable paired-drag input; queued only, the physics worker smooths and applies it.
// Null facing keeps the current drag direction, negative liftOffset picks the end default.
public record DragTarget(Vec3 position, Vec3 facing, float followResponsiveness, float liftOffset,
                         float maxHorizontalSpeed, float maxVerticalSpeed, float maxTurnRateDegrees) {
    // Default anchor lift for an arm-pair drag.
    public static final float DEFAULT_ARM_LIFT = 0.32f;
    // Default anchor lift for a leg-pair drag.
    public static final float DEFAULT_LEG_LIFT = 0.20f;
    // Gap between paired arm targets: matches a humanoid's arms so the grip lands on the wrists.
    public static final float DEFAULT_ARM_SEPARATION = 0.62f;
    // Gap between paired leg targets.
    public static final float DEFAULT_LEG_SEPARATION = 0.28f;
    // Per-second exponential follow responsiveness used by the physics worker.
    public static final float DEFAULT_FOLLOW_RESPONSIVENESS = 10.0f;
    // Speed caps for the smoothed anchor towing limbs.
    public static final float DEFAULT_MAX_HORIZONTAL_SPEED = 4.0f;
    public static final float DEFAULT_MAX_VERTICAL_SPEED = 2.25f;
    // Max change in the left/right offset direction, degrees per second.
    public static final float DEFAULT_MAX_TURN_RATE_DEGREES = 120.0f;

    public DragTarget {
        if (position == null) throw new IllegalArgumentException("position cannot be null");
        if (!Float.isFinite(followResponsiveness) || followResponsiveness <= 0f) {
            followResponsiveness = DEFAULT_FOLLOW_RESPONSIVENESS;
        }
        if (!Float.isFinite(liftOffset) || liftOffset < 0f) liftOffset = -1f;
        if (!Float.isFinite(maxHorizontalSpeed) || maxHorizontalSpeed <= 0f) {
            maxHorizontalSpeed = DEFAULT_MAX_HORIZONTAL_SPEED;
        }
        if (!Float.isFinite(maxVerticalSpeed) || maxVerticalSpeed <= 0f) {
            maxVerticalSpeed = DEFAULT_MAX_VERTICAL_SPEED;
        }
        if (!Float.isFinite(maxTurnRateDegrees) || maxTurnRateDegrees <= 0f) {
            maxTurnRateDegrees = DEFAULT_MAX_TURN_RATE_DEGREES;
        }
    }

    // Compatibility constructor: all defaults, keeps the prior facing.
    public DragTarget(Vec3 position) {
        this(position, null, DEFAULT_FOLLOW_RESPONSIVENESS, -1f,
                DEFAULT_MAX_HORIZONTAL_SPEED, DEFAULT_MAX_VERTICAL_SPEED, DEFAULT_MAX_TURN_RATE_DEGREES);
    }

    public static DragTarget of(Vec3 position, Vec3 facing) {
        return new DragTarget(position, facing, DEFAULT_FOLLOW_RESPONSIVENESS, -1f,
                DEFAULT_MAX_HORIZONTAL_SPEED, DEFAULT_MAX_VERTICAL_SPEED, DEFAULT_MAX_TURN_RATE_DEGREES);
    }

    public DragTarget withPosition(Vec3 newPosition) {
        return new DragTarget(newPosition, facing, followResponsiveness, liftOffset,
                maxHorizontalSpeed, maxVerticalSpeed, maxTurnRateDegrees);
    }
}
