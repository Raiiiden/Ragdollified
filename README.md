## Integration API

Ragdollified exposes a client-only API at `com.raiiiden.ragdollified.api.RagdollifiedApi`.
All mutations are queued onto Ragdollified's physics worker, so integrations must not access
Bullet objects directly. API actions are local to each client; send your own packet if they
must be visible to other players.

```java
RagdollHandle body = RagdollifiedApi.spawn(livingEntity, SpawnOptions.builder()
        .hideEntity(true)
        .persistent(true)
        .build());

Vec3 stableFacing = player.getLookAngle().multiply(1.0, 0.0, 1.0).normalize();
DragTarget dragTarget = DragTarget.of(target, stableFacing);
body.beginDrag(DragEnd.ARMS, dragTarget)
        .ifPresent(drag -> {
            drag.moveTo(dragTarget.withPosition(nextTarget)); // update anchor, retain facing
            drag.close();
        });

body.attachCamera(CameraAttachment.head());
RagdollifiedApi.raycast(start, end).ifPresent(hit -> use(hit.part()));
body.remove();
```

Paired `DragEnd.ARMS` and `DragEnd.LEGS` drags manage limb offsets, CCD, temporary low
friction, wake-up, smoothing, speed limits, turn limits, and torso following internally.
`DragTarget` exposes default arm/leg lift and separation, smoothing, max follow speed, and
max turn rate constants. `getState`, `hasRagdoll`, `getRagdollIds`, and `remove` provide safe
lifecycle/state access. Persistent handles stay alive until explicitly removed. `detachCamera()`
removes any explicit camera attachment.
`RagdollState` contains copied positions suitable for use on the render thread.
