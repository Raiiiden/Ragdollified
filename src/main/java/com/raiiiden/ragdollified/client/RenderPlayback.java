package com.raiiiden.ragdollified.client;

// Playback clock for one body's physics snapshots (render thread only), anchored to publish time
// rather than partialTick; a new snapshot waits until the current segment finishes, avoiding snaps.
final class RenderPlayback<S extends RenderPlayback.Segment> {

    // A snapshot that knows how many client ticks of motion it carries.
    interface Segment {
        int spanTicks();
    }

    private static final long TICK_NANOS = 50_000_000L;

    // Past this backlog, snap to the newest snapshot and restart the clock instead of draining it.
    private static final long MAX_LAG_NANOS = 4 * TICK_NANOS;

    private S current;
    private S pending;
    private long segmentStartNanos;
    private float phase;

    // Take a newly published snapshot; held until the segment on screen finishes.
    void offer(S latest) {
        if (latest == null || latest == current) return;
        // Overwriting an unplayed pending drops that step. Only reachable when snapshots arrive
        // faster than frames, where a step has to be skipped either way.
        pending = latest;
    }

    // Advance to the given instant and return the segment to draw, or null if nothing has arrived.
    S advance(long nowNanos) {
        if (current == null) {
            if (pending == null) return null;
            current = pending;
            pending = null;
            segmentStartNanos = nowNanos;
        }

        if (nowNanos - segmentStartNanos > MAX_LAG_NANOS) {
            if (pending != null) {
                current = pending;
                pending = null;
            }
            segmentStartNanos = nowNanos;
        }

        long segmentNanos = segmentNanos(current);
        if (pending != null && nowNanos - segmentStartNanos >= segmentNanos) {
            segmentStartNanos += segmentNanos;
            current = pending;
            pending = null;
            segmentNanos = segmentNanos(current);
        }

        float p = (float) (nowNanos - segmentStartNanos) / segmentNanos;
        phase = p < 0f ? 0f : (p > 1f ? 1f : p);
        return current;
    }

    private long segmentNanos(S segment) {
        return Math.max(TICK_NANOS, (long) segment.spanTicks() * TICK_NANOS);
    }

    // The segment last returned by advance, without advancing the clock.
    S current() {
        return current;
    }

    // How far through current the last advance landed, 0 to 1.
    float phase() {
        return phase;
    }
}
