package com.raiiiden.ragdollified.api;

// Camera attachment behavior. The default uses Ragdollified's smoothed transform and block clipping.
public record CameraOptions(boolean smoothed, boolean avoidBlocks) {
    public static final CameraOptions DEFAULT = new CameraOptions(true, true);
}
