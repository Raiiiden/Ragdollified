package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;

// Description of the ragdoll part used as a client camera anchor.
public record CameraAttachment(RagdollPart part, CameraOptions options) {
    public CameraAttachment {
        if (part == null) throw new IllegalArgumentException("part cannot be null");
        if (options == null) options = CameraOptions.DEFAULT;
    }

    public static CameraAttachment head() {
        return new CameraAttachment(RagdollPart.HEAD, CameraOptions.DEFAULT);
    }
}
