package com.raiiiden.ragdollified.physics;

// Step effort as an intent, which each backend translates into its own iteration settings
// (passing Bullet's dials straight to Jolt made light scenes cost more than heavy ones).
public enum StepQuality {

    // Few active bodies, so accuracy is affordable.
    HIGH,

    // The default working point.
    BALANCED,

    // Enough bodies that per-body cost matters more than joint fidelity.
    ECONOMY,

    // A pile. Bodies are mostly resting on each other and near-rest accuracy buys nothing visible.
    PILE
}
